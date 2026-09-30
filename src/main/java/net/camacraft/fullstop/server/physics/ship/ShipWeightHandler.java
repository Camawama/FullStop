package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.vs.PointForce;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipForces;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.physics.EntityStack;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.util.math.Units;
import net.camacraft.camafy.weight.Weights;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Ship weight, ported from the retired WeightRS mod: entities standing on a Valkyrien Skies ship
 * push it down with {@code m * g}, jumping off kicks it the other way, and landing on it slams
 * it. How heavy things are is Camafy's weight model ({@link Weights}); the forces reach the ship
 * through Camafy's one shared physics hook ({@link ShipForces}).
 *
 * <p>Standing weight runs once per level tick from {@link ShipPhysicsServer}, over the entities
 * it already queried around each ship. Landings of entities FullStop simulates come from its own
 * impact tracking ({@link ShipImpulses#onEntityImpact}), which works for players, mobs, boats and
 * carts alike and isn't cancelled along with vanilla fall damage; this class only tracks the
 * entities FullStop doesn't simulate (dropped items, blacklisted entities, bodies another mod owns
 * outright such as MKW's karts - their weight is a force ON the ship, which is still ours).
 * Jumps come from {@link LivingEvent.LivingJumpEvent}.
 *
 * <p>Jumps and landings are two-body exchanges ({@link ShipContactPhysics}) with the ship's
 * effective mass at the rider's feet ({@link ShipInertia}): a heavy rider jumping off or landing
 * on the edge of a small raft rocks it, it doesn't fling it - pushing a light deck as if it were
 * as stiff as its whole mass is what used to spin small rafts up under heavy riders. Standing
 * weight is the static support force {@code m*g} at the feet, only while the rider stands.
 */
public final class ShipWeightHandler {

    /** Camafy continuous-force key for the standing weights, replaced every tick. */
    private static final ResourceLocation STANDING = new ResourceLocation(FullStop.MODID, "ship_weight");

    /**
     * Standing forces lapse after this many game ticks without a refresh, so a ship never keeps
     * carrying weight that stopped being reported (weight switched off, ship left behind).
     */
    private static final int STANDING_TTL_TICKS = 2;

    /** Below this vertical speed (blocks/tick) a self-tracked landing is a settle, not an impact. */
    private static final double LANDING_MIN_FALL_BPT = 0.15;

    /**
     * The self-tracked entities' vertical motion: {y, this tick's vy (blocks/tick), tick, last
     * tick's vy}.
     */
    private static final Map<Entity, double[]> TRACKED = new WeakHashMap<>();

    private ShipWeightHandler() {
    }

    /** The bounds whose entities can weigh on {@code shipBox}: overhangs beside and below, and fallers above. */
    static AABB weightQueryBox(AABB shipBox) {
        return shipBox.inflate(2.0, 2.0, 2.0).expandTowards(0.0, 8.0, 0.0);
    }

    /**
     * One ship's standing weight for this tick, from the entities near it. {@code weighed} holds
     * the entities already weighing on another ship this tick: an entity straddling two ships'
     * bounds only weighs on one.
     */
    static void process(ServerLevel level, ShipRef ship, AABB shipBox, List<Entity> nearby, Set<Integer> weighed) {
        AABB query = weightQueryBox(shipBox);
        long now = level.getGameTime();

        boolean items = SERVER.enableItemEntityPhysics.get();
        boolean players = SERVER.includePlayers.get();
        boolean standingWeight = SERVER.enableStandingWeight.get();
        boolean landings = SERVER.enableFallImpact.get();
        double gravity = SERVER.gravity.get();
        double minKg = SERVER.minPhysicsWeightKg.get();
        double maxNewtons = SERVER.maxForceNewtons.get();
        boolean gravityFields = SERVER.enableGravityUnboundCompat.get() && Gravity.available();

        List<PointForce> standing = new ArrayList<>();
        for (Entity entity : nearby) {
            if (!isCandidate(entity) || !entity.getBoundingBox().intersects(query)) continue;
            boolean isItem = entity instanceof ItemEntity;
            if (isItem && !items) continue;
            if (!players && entity instanceof Player) continue;

            // FullStop's impact pipeline sees the landings of what it simulates; the rest
            // (items, blacklisted and fully claimed bodies) is tracked here.
            double previousVy = 0.0;
            boolean selfTracked = isItem || notSimulated(entity);
            if (selfTracked) {
                previousVy = trackVertical(entity, now);
            }

            if (!entity.onGround() || weighed.contains(entity.getId())) continue;
            // On THIS ship: a block of it right under the feet, or VS carrying the entity with it
            // (a Gravity Unbound hull walker stands on a wall, which the world-down probe misses).
            if (!ship.isUnderfoot(entity) && !ship.equals(ShipEntities.standingOn(entity, 2))) continue;
            weighed.add(entity.getId());
            // Held down by a gravity field on the ship itself (Gravity Unbound's plating on it): its weight is the ship
            // pulling on it and it pressing back - the ship's own business, as the crew of a ship in space weigh
            // nothing to it. Counted, a ship in zero gravity drove itself along with whoever stood on its plating.
            if (Gravity.ridesShipField(entity)) continue;

            double kg = stackWeightKg(entity);
            if (kg < minKg) continue;

            Vec3 pos = entity.position();
            // Its weight is its local gravity's: none in VS Genesis's space or an Ad Astra orbit, a
            // sixth on the Moon (Camafy's strength counts every source, whatever the Gravity
            // Unbound compat switch says; that switch only picks the direction of the pull).
            Vec3 down = gravityFields ? Gravity.pullDirection(entity) : GravityFrame.WORLD_UP.scale(-1);
            double strength = Gravity.strength(entity);
            double newtons = strength < Gravity.WEIGHTLESS_BELOW ? 0.0 : Math.min(kg * gravity * strength, maxNewtons);
            if ((isItem || standingWeight) && newtons > 0.0) {
                standing.add(PointForce.at(down.scale(newtons), pos));
            }

            // A self-tracked landing: its momentum goes into the deck (two-body: never harder than
            // the lander was moving), unless the ship itself just threw it.
            if (selfTracked && landings && previousVy < -LANDING_MIN_FALL_BPT) {
                FullStopCapability cap = FullStopCapability.grabCapability(entity);
                if (cap == null || !cap.isShipFeedbackBlocked(ship.id(), now)) {
                    double impactSpeed = Units.bptToMps(-previousVy);
                    double impulse = deckImpulse(ship, pos, down.scale(-1.0), impactSpeed, kg);
                    ShipImpulses.applyCapped(level, ship, down.scale(impulse * SERVER.fallImpactMultiplier.get()), pos);
                }
                landed(entity);
            }
        }

        // Seated riders: a seat that is part of this ship (a VS helm, a Create or Clockwork seat)
        // carries its occupant's weight into the hull just as the deck carries a standing one.
        if (standingWeight) {
            for (Entity rider : seatedRiders(level, nearby)) {
                if (weighed.contains(rider.getId()) || !rider.isAlive() || rider.isSpectator()) continue;
                if (!players && rider instanceof Player) continue;
                if (!ship.equals(ShipEntities.mountedTo(rider))) continue;
                weighed.add(rider.getId());
                if (Gravity.ridesShipField(rider)) continue; // its gravity is the ship's own field: see above
                double kg = stackWeightKg(rider);
                if (kg < minKg) continue;
                Vec3 down = gravityFields ? Gravity.pullDirection(rider) : GravityFrame.WORLD_UP.scale(-1);
                double strength = Gravity.strength(rider);
                double newtons = strength < Gravity.WEIGHTLESS_BELOW ? 0.0 : Math.min(kg * gravity * strength, maxNewtons);
                if (newtons > 0.0) {
                    standing.add(PointForce.at(down.scale(newtons), seatPoint(ship, rider)));
                }
            }
        }

        // Replaces last tick's set; an empty list clears it.
        ShipForces.setContinuous(level, ship.id(), STANDING, standing, STANDING_TTL_TICKS);
    }

    /**
     * Everything that may sit in a seat of a ship near {@code nearby}: the level's players (a
     * seat entity lives in the shipyard, so a seated player isn't always among the entities found
     * around the ship in the world) and the riders found there.
     */
    private static List<Entity> seatedRiders(ServerLevel level, List<Entity> nearby) {
        List<Entity> out = new ArrayList<>();
        for (Player player : level.players()) {
            if (player.isPassenger()) out.add(player);
        }
        for (Entity entity : nearby) {
            if (entity.isPassenger() && !(entity instanceof Player)) out.add(entity);
        }
        return out;
    }

    /** Where a seated rider's weight goes into the ship: its seat, in the world. */
    private static Vec3 seatPoint(ShipRef ship, Entity rider) {
        Entity seat = rider.getVehicle();
        if (seat != null && Ships.inShipyard(seat.level(), seat.getX(), seat.getY(), seat.getZ())) {
            Vec3 world = ship.toWorld(seat.position());
            if (world != null) return world;
        }
        return rider.position();
    }

    /** Clears this ship's standing weight (it has no bounds this tick). */
    static void clear(ServerLevel level, ShipRef ship) {
        ShipForces.clearContinuous(level, ship.id(), STANDING);
    }

    static boolean isCandidate(Entity entity) {
        return entity.isAlive() && !entity.isSpectator() && !entity.isPassenger();
    }

    /** Whether FullStop's own physics never ticks this entity (so its landings aren't measured there). */
    private static boolean notSimulated(Entity entity) {
        return DamageImmunityRules.unphysableIgnoringAi(entity)
                || PhysicsClaims.cached(entity) == PhysicsAspect.ALL;
    }

    /**
     * Records this tick's vertical speed of a self-tracked entity and returns LAST tick's
     * (blocks/tick) - on the landing tick that is the speed it fell with. Items read their own
     * motion (as WeightRS did); anything else is measured from positions, so client-driven bodies
     * (a player's kart) read right too, with the ship's carry taken out so riding a sinking deck
     * isn't a fall.
     */
    private static double trackVertical(Entity entity, long now) {
        double y = entity.getY();
        double[] track = TRACKED.get(entity);
        if (track == null) {
            TRACKED.put(entity, new double[]{y, 0.0, now, 0.0});
            return 0.0;
        }
        if (track[2] == now) return track[3];
        boolean continuous = track[2] == now - 1;
        double previousVy = continuous ? track[1] : 0.0;
        double vy;
        if (entity instanceof ItemEntity) {
            vy = entity.getDeltaMovement().y;
        } else {
            vy = continuous ? (y - track[0]) - Units.mpsToBpt(ShipCompat.shipDragVelocityMps(entity).y) : 0.0;
        }
        TRACKED.put(entity, new double[]{y, vy, now, previousVy});
        return previousVy;
    }

    /** A landing was billed: the fall it came from can't be billed again next tick. */
    private static void landed(Entity entity) {
        double[] track = TRACKED.get(entity);
        if (track != null) {
            track[1] = 0.0;
        }
    }

    // ------------------------------------------------------------------
    // Jump impulse
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onLivingJump(LivingEvent.LivingJumpEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) return;
        if (!ShipCompat.weightActive() || !SERVER.enableJumpImpulse.get()) return;
        if (!SERVER.includePlayers.get() && entity instanceof Player) return;

        ShipRef ship = ShipEntities.shipUnderfoot(entity);
        if (ship == null || Gravity.ridesShipField(entity)) return; // pushing off in the ship's own field: see process
        double kg = stackWeightKg(entity);
        if (kg < SERVER.minPhysicsWeightKg.get()) return;

        // The jump's speed along the entity's up (a Gravity Unbound field may tilt it).
        GravityFrame frame = FullStopCapability.currentFrame(entity);
        double jumpBpt = frame.vertical(Gravity.getWorldVelocity(entity, frame));
        if (jumpBpt <= 0.0) {
            jumpBpt = 0.42; // vanilla base jump power, if the velocity isn't set yet
        }
        double jumpSpeed = Units.bptToMps(jumpBpt);
        Vec3 down = frame.up().scale(-1);
        double impulse = deckImpulse(ship, entity.position(), frame.up(), jumpSpeed, kg);
        ShipImpulses.applyCapped(level, ship, down.scale(impulse * SERVER.jumpImpulseMultiplier.get()), entity.position());
    }

    /**
     * The impulse (N s) between a deck and {@code kg} leaving or landing on it at {@code speed}
     * (m/s, relative to the deck) along {@code up} at {@code at}: the two-body exchange with the
     * ship's effective mass there. Against a big ship it is {@code kg * speed}; a light raft
     * gives way, and is pushed no faster at that point than the rider was moving.
     */
    private static double deckImpulse(ShipRef ship, Vec3 at, Vec3 up, double speed, double kg) {
        double shipKg = ship.isStatic() ? Double.POSITIVE_INFINITY : ship.mass();
        if (!(shipKg > 0.0)) shipKg = Double.POSITIVE_INFINITY;
        double effective = ShipInertia.effectiveMassKg(ship, shipKg, at, up);
        return ShipContactPhysics.resolve(speed, kg, effective, 0.0).impulse();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Weight of an entity plus everything riding it, in kg (what it carries included); riding
     * players only count when {@code includePlayers} is on.
     */
    static double stackWeightKg(Entity entity) {
        if (SERVER.includePlayers.get()) {
            return Weights.massWithPassengersKg(entity);
        }
        return EntityStack.sum(entity, e -> e instanceof Player ? 0.0 : Weights.massKg(e));
    }
}
