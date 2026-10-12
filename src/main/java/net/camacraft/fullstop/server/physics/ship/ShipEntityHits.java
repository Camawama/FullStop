package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.vs.ShipContact;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.physics.EntityStack;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.physics.VanillaFall;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.camacraft.fullstop.server.physics.BodyPush;
import net.camacraft.fullstop.server.physics.damage.KineticDamageCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * An entity running, flying or falling into a Valkyrien Skies ship: a two-body collision
 * ({@code entityImpacts}), not a wall. VS's resolver stops the entity at the hull as if the ship
 * were fixed in place (and {@link net.camacraft.fullstop.common.physics.ShipHullSweep} makes sure it
 * does at any speed); this runs afterwards, in the entity's own impact pipeline, and replaces the
 * wall impact FullStop would otherwise bill with what really happens between the two bodies
 * ({@link ShipContactPhysics}, the same law as a ship hitting an entity):
 * <ul>
 *   <li>the ship is pushed at the contact point with the impulse the entity delivers;</li>
 *   <li>the entity keeps what a heavier body keeps: its velocity along the contact normal is set
 *   to its old one plus the exchange's kick (a golem hitting a light raft barely slows; a player
 *   hitting a big ship rebounds at the restitution); across the normal it keeps what it had;</li>
 *   <li>its damage is the kinetic damage of the speed it actually lost.</li>
 * </ul>
 * Landings on a deck (the hull meeting the entity from below) are inelastic, weigh what the
 * ship-weight system weighs (carried items included) and push only with {@code enableFallImpact};
 * the lander's own motion is left to VS, which carries it with the deck from now on. Every other
 * contact uses the entity's body mass (as ship hits do) and pushes only with
 * {@code reactionImpulses}.
 *
 * <p>Judged in the world frame against the hull's own motion: the entity's velocity before the
 * impact (the ship it rode put back) against the hull material's velocity at the contact point.
 * A contact the hull brings most of the closing speed to is a ship hit ({@link ShipEntityImpacts}),
 * not this. Never pushes the ship back with what the ship itself did to the entity: a rider its
 * deck just threw ({@link ShipDeckMomentum}) doesn't push that ship until it has settled, and a
 * rider running into its own ship's structure (it stood on that ship within the last few ticks)
 * doesn't push it at all - its running start was never taken from the ship either.
 *
 * <p>The ship's reaction reaches VS's physics thread a step later, so for a few ticks the hull (not
 * yet moving) can block the entity again: that doesn't count as another collision, and the entity's
 * post-collision velocity along the normal is restored instead of being eaten.
 */
public final class ShipEntityHits {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Closing speed (m/s) below which touching a hull is not a collision. */
    static final double MIN_APPROACH = 1.0;
    /** How far along its approach the entity's box looks for the hull it struck (blocks). */
    private static final double PROBE = 0.1;
    /** One exchange per ship per entity per this many ticks (shared with ship hits). */
    private static final int COOLDOWN_TICKS = 10;
    /** How long the post-collision velocity is defended against the not-yet-moving hull. */
    private static final int EXCHANGE_TICKS = 10;
    /** An entity that stood on a ship within this many ticks is that ship's rider. */
    static final int RIDER_TICKS = 5;
    /** Ticks of standing still after which a contact from below is standing, not landing. */
    private static final int LANDING_TICKS = 2;
    /**
     * A body a ship struck ({@link ShipEntityImpacts}) is that ship's to strike again for this long: a contact with it
     * meanwhile is the hull catching the body up, never the body ramming the hull.
     */
    static final int STRUCK_TICKS = Integer.getInteger("fullstop.struckShipTicks", 40);
    /** A body that has been aboard a ship for fewer ticks than this when it meets that ship's hull arrived at it. */
    static final int ARRIVAL_TICKS = Integer.getInteger("fullstop.riderArrivalTicks", 4);

    /** The last collision of each entity with a ship, for the debug log and the game tests. */
    private static final Map<Entity, Hit> LAST_HIT = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * One collision: when, with which ship, whether it was a landing, the exchange, the impulse
     * the ship actually got (N s) and the entity's world velocity along the normal after it
     * (m/s; NaN when VS was left to carry it).
     */
    public record Hit(long tick, long shipId, boolean landing, ShipContactPhysics.Exchange exchange, double pushed,
                      double normalSpeedAfter) {
    }

    private ShipEntityHits() {
    }

    /** The last collision {@code entity} had with a ship, or null. */
    @Nullable
    public static Hit lastHit(Entity entity) {
        return LAST_HIT.get(entity);
    }

    /**
     * What the impact pipeline does with this tick's impact.
     *
     * @param handled   the contact with a ship was dealt with here: no wall impact for it
     * @param damage    the damage to deal instead (0: none)
     * @param collision what to hand the damage applier (the hull block, as a solid impact)
     */
    public record Outcome(boolean handled, double damage, Collision collision, boolean legacy) {
        /** Not this class's business: the plain landing push ({@link ShipImpulses#onEntityImpact}) may run. */
        static final Outcome NONE = new Outcome(false, 0.0, Collision.NONE, true);
        /** This class's business, but nothing to do this tick (and no other landing push either). */
        static final Outcome PASS = new Outcome(false, 0.0, Collision.NONE, false);

        static Outcome silent(Collision collision) {
            return new Outcome(true, 0.0, collision, false);
        }
    }

    /** The hull block an entity struck, and how. */
    private record Contact(ShipRef ship, BlockPos pos, Vec3 normal, Vec3 point, Vec3 hullVelocity, double approach) {
    }

    /**
     * The entity-vs-ship collision for this tick's impact, if the entity struck a ship.
     *
     * @param brokeBlock FullStop's kinetic block breaking punched through a block this tick (then
     *                   that, not this, decided the outcome)
     */
    public static Outcome onEntityImpact(Entity entity, FullStopCapability cap, Collision collision, boolean brokeBlock) {
        if (!(entity.level() instanceof ServerLevel level) || !enabled()) return Outcome.NONE;
        if (entity.isPassenger() || !ShipContactPhysics.isBody(entity) || excluded(entity)) return Outcome.NONE;
        long now = level.getGameTime();
        GravityFrame frame = cap.getFrame();

        Outcome defended = defendExchange(level, entity, cap, now, frame);
        if (defended != null) return defended;
        // The rebound off a hull it just collided with is not a new impact on it (and no wall).
        long recent = cap.recentShipHit(now);
        if (recent != Long.MIN_VALUE && onlyBlocksOf(level, collision, recent)) return Outcome.silent(Collision.NONE);
        if (brokeBlock) return Outcome.PASS;

        Vec3 before = cap.getPreImpactWorldVelocity();
        if (before.lengthSqr() < MIN_APPROACH * MIN_APPROACH) return Outcome.PASS;
        Contact contact = strongestContact(level, entity.getBoundingBox(), before);
        if (contact == null || contact.approach() < MIN_APPROACH) return Outcome.PASS;
        ShipRef ship = contact.ship();
        BlockState hull = level.getBlockState(contact.pos());
        // springy and sticky hulls keep FullStop's bounce handling (and the plain landing push)
        if (hull.isStickyBlock() || hull.getBlock() instanceof BedBlock) return Outcome.NONE;
        Collision struck = new Collision(Collision.CollisionType.SOLID, List.of(hull), List.of(), List.of(contact.pos()),
                List.of(new BlockHitResult(contact.point(), Direction.UP, contact.pos(), false)));

        // Stopped by this hull, not merely passing it: the closing speed has mostly gone.
        Vec3 normal = contact.normal();
        double closingNow = -cap.getCurrentWorldVelocity().subtract(contact.hullVelocity()).dot(normal);
        if (closingNow > 0.5 * contact.approach()) return Outcome.PASS; // next tick, once it has stopped
        // Already exchanged (the second tick of a two-tick impact), or the ship just hit it.
        if (cap.isShipHitOnCooldown(ship.id(), now)) return Outcome.silent(struck);
        // This ship struck the body just now: meeting it again is the hull catching it up (ShipEntityImpacts' business),
        // whatever the body's measured motion says. A car a ship had shoved along read, on a tick its driver's client
        // and the server disagreed about where it was, as ramming that ship at up to 18 m/s, and pushed the ship back
        // and itself ahead again (harness carhit_roll, 2026-10-10: "it keeps rolling into me and pushing me which
        // pushes the ship which pushes me").
        ShipEntityImpacts.Struck byShip = ShipEntityImpacts.lastStruck(entity);
        if (byShip != null && byShip.shipId() == ship.id() && now - byShip.tick() < STRUCK_TICKS) {
            return Outcome.silent(struck);
        }
        // The hull brings most of the closing speed: the ship is hitting the entity, and
        // ShipEntityImpacts deals with that (at the end of this tick).
        double hullIn = contact.hullVelocity().dot(normal);
        if (hullIn >= Math.max(SERVER.pushMinSpeed.get(), -before.dot(normal))) return Outcome.silent(struck);

        // a landing (not in zero gravity, where nothing lands: a floating body meets the deck
        // like any other hull)
        boolean weightless = cap.isWeightless();
        boolean fromBelow = ShipContactPhysics.fromBelow(normal, frame, weightless);
        if (fromBelow && cap.getGroundedTicks() > LANDING_TICKS) return Outcome.PASS; // standing on it
        // its own ship: it stood on this deck just now, or VS is still carrying it with the ship - and it is on that deck:
        // VS takes a body touching a ship's side for one standing on it (a car driven into a ship's side was its "rider"
        // from the first touch on, and never pushed it)
        boolean rider = !fromBelow && (cap.stoodOnBefore(ship.id(), now, RIDER_TICKS) || ShipCompat.carriedBy(entity, ship))
                && onDeck(level, entity, ship, frame)
                // aboard before it met this hull: a car driven at a raft whose deck lies about as high as the road gets its
                // wheels over the raft's edge as it hits it, and VS records it standing there from that tick on; taken for
                // the raft's rider, it pushed nothing and the raft tipped toward it under its weight (harness carhit_ship,
                // 2026-10-10: the raft drifted back toward the car and the car sank with its edge into the pier)
                && (ARRIVAL_TICKS <= 0 || cap.aboardSince(ship.id(), now, ARRIVAL_TICKS));
        // A rider its own ship's gravity field holds (Gravity Unbound's plating on that ship) is moved by the field, not
        // carried by Valkyrien Skies, so nothing of the ship's motion is taken out of what is measured of it: its world
        // velocity, read from its client's positions on a fast deck (packets doubled one tick, none the next), swung
        // between 0 and the deck's 35 m/s, and a car driven on its own plated deck spinning at 60 RPM read as hitting
        // that deck at 19 to 44 m/s (16 to 63 damage, harness car_spin60, on and off since 10-02). Its own deck's
        // structure is no hit for it.
        if (rider && heldByShipField(entity)) return Outcome.silent(struck);
        double entityKg = fromBelow ? deckKg(entity) : ShipContactPhysics.bodyKg(entity);
        double shipKg = ship.isStatic() ? Double.POSITIVE_INFINITY : ship.physicalMass();
        if (!(shipKg > 0.0)) shipKg = Double.POSITIVE_INFINITY;
        ShipContactPhysics.Exchange exchange = ShipContactPhysics.resolve(ship, shipKg, contact.point(), normal,
                contact.approach(), entityKg, frame, weightless);

        // Damage: the speed the entity really lost, against the hull block.
        double damage = KineticDamageCalculator.damageForSpeedLost(entity, exchange.speedLost(),
                Math.abs(frame.vertical(normal)) > 0.7, List.of(hull));

        // The entity: what it keeps along the normal (a landing is left to VS, which carries it on).
        boolean moved = false;
        if (!fromBelow && !rider) {
            Vec3 left = setNormalVelocity(entity, cap, frame, normal, before.dot(normal) + exchange.kick());
            if (left != null) {
                moved = true;
                cap.markShipExchange(ship.id(), now, EXCHANGE_TICKS, left, normal);
            }
        }

        // The ship: pushed at the contact, unless the push would come back from what it did itself.
        Vec3 pushed = Vec3.ZERO;
        // Pulled in by a gravity field on the ship itself (Gravity Unbound's plating on it), a body landing on it or
        // bumping into it is the ship's own business: the field pulled the ship toward the body just as hard, and the
        // two came together with nothing to show for it. Pushed, a ship in space drove itself along each time someone
        // was drawn onto its plating (and hard, with a loaded inventory).
        boolean ownField = Gravity.ridesShipField(entity);
        double impulse = ownField ? 0.0 : pushImpulse(cap, ship, now, fromBelow, rider, contact.approach(), exchange);
        if (fromBelow && impulse > 0.0) {
            // a landing tops up to its full impulse over its two ticks, never pushes twice
            double total = impulse;
            impulse = total - cap.getRecentShipImpulse(entity.tickCount);
            cap.markShipImpulse(entity.tickCount, 5, total);
        }
        if (impulse > 0.0) {
            pushed = ShipImpulses.applyCapped(level, ship, normal.scale(-impulse), contact.point(), contact.approach());
        }
        cap.markShipHit(ship.id(), now, COOLDOWN_TICKS);
        LAST_HIT.put(entity, new Hit(now, ship.id(), fromBelow, exchange, pushed.length(),
                moved ? before.dot(normal) + exchange.kick() : Double.NaN));

        if (SERVER.debugShipCollisionLog.get()) {
            LOGGER.info("[FullStop] {} hit ship {} {}: approach {} m/s, entity {} kg vs ship {} kg (effective), e {}, lost {} m/s, "
                            + "kick {} m/s{}, ship pushed {} N s, normal ({}), damage {}{}",
                    entity.getName().getString(), ship.id(), fromBelow ? "landing" : "", fmt(exchange.approach()),
                    fmt(exchange.entityKg()), Double.isInfinite(exchange.shipKg()) ? "inf" : fmt(exchange.shipKg()),
                    fmt(exchange.restitution()), fmt(exchange.speedLost()), fmt(exchange.kick()), moved ? "" : " (left to VS)",
                    fmt(pushed.length()), String.format(Locale.ROOT, "%.2f,%.2f,%.2f", normal.x, normal.y, normal.z), fmt(damage),
                    rider ? " (own ship: no push)" : ownField ? " (drawn in by its own field: no push)"
                            : cap.isShipFeedbackBlocked(ship.id(), now) ? " (thrown by it: no push)" : "");
        }
        return new Outcome(true, damage, struck, false);
    }

    /**
     * Whether a block of {@code ship} is right under the entity's feet (along its gravity's down, at the middle or a
     * corner of its footprint): it stands on that ship's deck, and a hull of that ship it runs into is its own ship's.
     * VS's own record of what a body stands on also names a ship it only touched from the side.
     */
    static boolean onDeck(ServerLevel level, Entity entity, ShipRef ship, @Nullable GravityFrame frame) {
        Vec3 up = frame != null ? frame.up() : GravityFrame.WORLD_UP;
        net.minecraft.world.phys.AABB box = entity.getBoundingBox();
        Vec3 centre = box.getCenter();
        // the bottom of the box along the frame's up, then a little under it
        double half = 0.5 * (Math.abs(up.x) * box.getXsize() + Math.abs(up.y) * box.getYsize() + Math.abs(up.z) * box.getZsize());
        Vec3 feet = centre.subtract(up.scale(half + 0.2));
        double r = 0.45 * Math.min(box.getXsize(), box.getZsize());
        Vec3 a = Math.abs(up.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = a.subtract(up.scale(a.dot(up))).normalize();
        Vec3 v = up.cross(u);
        for (int i = 0; i < 5; i++) {
            Vec3 at = i == 0 ? feet : feet.add(u.scale((i & 1) == 0 ? r : -r)).add(v.scale((i & 2) == 0 ? r : -r));
            Vec3 local = ship.toShip(at);
            if (local == null) continue;
            BlockState state = ShipTerrainImpacts.loadedState(level, BlockPos.containing(local));
            if (state != null && !state.isAir()) return true;
        }
        return false;
    }

    /** Whether the gravity mod holds the body, or anyone riding it, in a field mounted on a ship. */
    private static boolean heldByShipField(Entity root) {
        for (Entity member : EntityStack.selfAndPassengers(root)) {
            if (Gravity.ridesShipField(member)) return true;
        }
        return false;
    }

    /** Whether every block this tick's rays struck (none counts too) belongs to ship {@code shipId}. */
    private static boolean onlyBlocksOf(ServerLevel level, Collision collision, long shipId) {
        for (BlockPos pos : collision.impactedPositions) {
            ShipRef owner = ShipCompat.isShipBlock(level, pos) ? Ships.managing(level, pos) : null;
            if (owner == null || owner.id() != shipId) return false;
        }
        return true;
    }

    /** Whether entities hitting ships is on at all. */
    static boolean enabled() {
        return ShipCompat.collisionsActive() && SERVER.entityImpacts.get();
    }

    /** Never collided with here (see ShipEntityImpacts' exclusions): FullStop or another mod leaves it be. */
    private static boolean excluded(Entity root) {
        if (DamageImmunityRules.unphysableIgnoringAi(root) || root.isSpectator()) return true;
        // (creative players collide like anyone else, as ships hit them too; they are never hurt)
        if (root.getType().is(FullStopTags.SHIP_IMMUNE)) return true;
        // another mod owns its impacts (a CONTACT claim is about entities, and a hull isn't one)
        for (Entity member : EntityStack.selfAndPassengers(root)) {
            if (PhysicsAspect.IMPACT.in(PhysicsClaims.live(member))) return true;
        }
        return false;
    }

    /** What a lander weighs on a deck: the ship-weight system's mass, or its body if that says nothing. */
    private static double deckKg(Entity entity) {
        double kg = ShipWeightHandler.stackWeightKg(entity);
        return kg > 0.0 ? kg : ShipContactPhysics.bodyKg(entity);
    }

    /** The impulse (N s) the ship gets, after the switches and the feedback rules; 0 for none. */
    private static double pushImpulse(FullStopCapability cap, ShipRef ship, long now, boolean fromBelow, boolean rider,
                                      double approach, ShipContactPhysics.Exchange exchange) {
        if (Double.isInfinite(exchange.shipKg()) || rider || cap.isShipFeedbackBlocked(ship.id(), now)) return 0.0;
        if (fromBelow) {
            if (!ShipCompat.weightActive() || !SERVER.enableFallImpact.get()) return 0.0;
            double minSpeed = VanillaFall.impactSpeedForFallDistance(SERVER.minFallDistance.get());
            if (approach < Math.max(minSpeed, 0.5)) return 0.0;
            return exchange.impulse() * SERVER.fallImpactMultiplier.get();
        }
        return SERVER.reactionImpulses.get() ? exchange.impulse() : 0.0;
    }

    /**
     * The hull block the entity's box meets when moved a little along its approach, of every
     * ship there, with the fastest closing speed.
     */
    @Nullable
    private static Contact strongestContact(ServerLevel level, AABB box, Vec3 before) {
        AABB probe = box.expandTowards(before.normalize().scale(PROBE)).inflate(1.0e-3);
        Contact best = null;
        for (ShipRef ship : Ships.intersecting(level, probe)) {
            for (ShipContact contact : ship.contacts(probe)) {
                Vec3 point = contactPoint(ship, contact, box);
                if (point == null) continue;
                Vec3 hull = ship.velocityAt(point);
                if (hull == null) continue;
                double approach = -before.subtract(hull).dot(contact.normal());
                if (best == null || approach > best.approach()) {
                    best = new Contact(ship, contact.pos(), contact.normal(), point, hull, approach);
                }
            }
        }
        return best;
    }

    /** The point of the struck face nearest the entity's centre, in world space. */
    @Nullable
    private static Vec3 contactPoint(ShipRef ship, ShipContact contact, AABB box) {
        Vec3 local = ship.toShip(box.getCenter());
        if (local == null) return null;
        BlockPos pos = contact.pos();
        Direction face = contact.face();
        double x = clampTo(local.x, pos.getX()), y = clampTo(local.y, pos.getY()), z = clampTo(local.z, pos.getZ());
        double plane = pos.get(face.getAxis()) + (face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : 0.0);
        switch (face.getAxis()) {
            case X -> x = plane;
            case Y -> y = plane;
            case Z -> z = plane;
        }
        return ship.toWorld(new Vec3(x, y, z));
    }

    private static double clampTo(double value, int cell) {
        return Math.max(cell, Math.min(cell + 1.0, value));
    }

    /**
     * Sets the entity's world velocity along {@code normal} to {@code normalSpeed} (m/s), keeping
     * the rest, and makes sure it reaches whoever simulates the entity.
     *
     * @return the world velocity (m/s) it now has, or null when its motion is another mod's
     */
    @Nullable
    private static Vec3 setNormalVelocity(Entity entity, FullStopCapability cap, GravityFrame frame, Vec3 normal,
                                          double normalSpeed) {
        Vec3 carry = ShipCompat.shipDragVelocityMps(entity);
        // A server-side player's (or a driven vehicle's) deltaMovement is meaningless - its client
        // moves it: the measured velocity stands in for its own.
        boolean clientMoved = entity instanceof Player || entity.getControllingPassenger() instanceof Player;
        Vec3 own = clientMoved ? cap.getCurrentScaledVelocity()
                : net.camacraft.camafy.compat.scale.EntityScale.realVelocity(entity, Gravity.getWorldVelocity(entity, frame)).scale(20.0);
        Vec3 world = own.add(carry);
        double change = normalSpeed - world.dot(normal);
        // as a change, to whoever simulates the body (BodyPush)
        if (!BodyPush.push(entity, normal.scale(change * 0.05), frame, own)) return null;
        return world.add(normal.scale(change));
    }

    /**
     * Within a few ticks of an exchange, the ship's reaction may not have moved the hull yet: if
     * the entity is up against that ship again and has lost what the collision left it along the
     * normal, that is restored (no damage, no second push).
     */
    @Nullable
    private static Outcome defendExchange(ServerLevel level, Entity entity, FullStopCapability cap, long now, GravityFrame frame) {
        long shipId = cap.shipExchangeShip(now);
        if (shipId == Long.MIN_VALUE) return null;
        Vec3 kept = cap.shipExchangeVelocity(shipId, now);
        ShipRef ship = Ships.byId(level, shipId);
        if (kept == null || ship == null) return null;
        Vec3 normal = cap.shipExchangeNormal();
        double wanted = kept.dot(normal);
        Vec3 own = entity instanceof Player ? cap.getCurrentScaledVelocity()
                : net.camacraft.camafy.compat.scale.EntityScale.realVelocity(entity, Gravity.getWorldVelocity(entity, frame)).scale(20.0);
        double have = own.add(ShipCompat.shipDragVelocityMps(entity)).dot(normal);
        if (have >= wanted - 0.5) return null;
        // against the hull again: pushed toward it by a little is enough to touch
        if (!ship.touchesBlock(entity.getBoundingBox().move(normal.scale(-0.05)))) return null;
        setNormalVelocity(entity, cap, frame, normal, wanted);
        return Outcome.silent(Collision.NONE);
    }

    // ------------------------------------------------------------------
    // Kinetic block breaking against a hull
    // ------------------------------------------------------------------

    /**
     * An entity's impact on one hull block, as FullStop's kinetic block breaking needs it: the
     * closing speed into the block's face (relative to the hull there) and the energy the
     * collision can spend on the block - the kinetic energy of that closing speed with the
     * REDUCED mass of entity and ship (in FullStop's energy units): what a two-body collision
     * dissipates at most. A light raft can't be smashed by ramming it; it gets shoved. Against
     * a big ship it is the entity's own energy, as against a world wall.
     *
     * @param rider the entity stood on this ship just now (its motion is VS's; leave it alone)
     */
    public record BlockImpact(ShipRef ship, Vec3 normal, Vec3 point, double closingBpt, double energyUnits, boolean rider,
                              double entityKg, double reducedKg) {
    }

    /** {@link BlockImpact} for a raycast hit on a ship block, or null when it isn't one or can't be resolved. */
    @Nullable
    public static BlockImpact blockImpact(ServerLevel level, Entity entity, BlockHitResult hit, Vec3 impactVelocityBpt,
                                          double entityUnits) {
        if (!ShipCompat.isShipBlock(level, hit.getBlockPos())) return null;
        ShipRef ship = Ships.managing(level, hit.getBlockPos());
        if (ship == null) return null;
        Vec3 normal = ship.faceNormal(hit.getDirection());
        Vec3 point = hit.getLocation();
        Vec3 hull = ship.velocityAt(point);
        if (normal == null || hull == null) return null;
        FullStopCapability cap = FullStopCapability.grabCapability(entity);
        long now = level.getGameTime();
        boolean rider = ((cap != null && cap.stoodOnBefore(ship.id(), now, RIDER_TICKS)) || ShipCompat.carriedBy(entity, ship))
                && onDeck(level, entity, ship, cap != null ? cap.getFrame() : FullStopCapability.currentFrame(entity));
        Vec3 world = cap != null ? cap.getPreImpactWorldVelocity() : impactVelocityBpt.scale(20.0);
        double closingMps = -world.subtract(hull).dot(normal);
        double entityKg = entityUnits * EntityStackUtils.KG_PER_UNIT;
        double shipKg = ship.isStatic() ? Double.POSITIVE_INFINITY : ship.physicalMass();
        if (!(shipKg > 0.0)) shipKg = Double.POSITIVE_INFINITY;
        double effective = ShipInertia.effectiveMassKg(ship, shipKg, point, normal);
        double reducedUnits = Double.isInfinite(effective) ? entityUnits
                : EntityStackUtils.kgToUnits(entityKg * effective / (entityKg + effective));
        double closingBpt = closingMps * 0.05;
        double energy = 0.5 * reducedUnits * Math.max(0.0, closingBpt) * Math.max(0.0, closingBpt);
        return new BlockImpact(ship, normal, point, closingBpt, energy, rider, entityKg,
                reducedUnits * EntityStackUtils.KG_PER_UNIT);
    }

    /**
     * The entity punched through a hull block and lost {@code speedLostMps} doing it: the ship takes
     * that momentum at the block - never more than a two-body collision at that closing speed could
     * hand over (reduced mass times closing speed: a light raft is knocked, not flung) - unless the
     * entity is its rider or the ship itself threw it. It counts as the entity's collision with
     * that ship (no second one right after, and the deck doesn't throw it for the jolt it gave).
     */
    public static void punchedThrough(ServerLevel level, Entity entity, BlockImpact impact, Vec3 direction, double speedLostMps) {
        if (!enabled() || impact.rider() || !(speedLostMps > 0.0)) return;
        FullStopCapability cap = FullStopCapability.grabCapability(entity);
        long now = level.getGameTime();
        if (cap != null) cap.markShipHit(impact.ship().id(), now, COOLDOWN_TICKS);
        if (!SERVER.reactionImpulses.get() || (cap != null && cap.isShipFeedbackBlocked(impact.ship().id(), now))) return;
        double impulse = Math.min(impact.entityKg() * speedLostMps, impact.reducedKg() * Math.max(0.0, impact.closingBpt()) * 20.0);
        ShipImpulses.applyCapped(level, impact.ship(), direction.normalize().scale(impulse), impact.point(),
                Math.max(speedLostMps, Math.abs(impact.closingBpt()) * 20.0));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
