package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.physics.EntityStack;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
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
 * Riders keep the momentum a Valkyrien Skies deck loses. VS carries whatever stands on a ship by
 * moving it with the ship each tick, never through its velocity, so when a ship stops dead its
 * riders used to stop dead with it. Here, each tick that brings a new physics frame
 * ({@link ShipTracker}), every rider gets back the velocity the deck under its feet lost beyond
 * what it can hold on through:
 * <pre>
 *   lost   = deck velocity at the feet, previous frame - the same piece of deck now  (m/s)
 *   excess = lost * max(0, 1 - deckGrip * dt / |lost|)      dt = the frame's time span
 * </pre>
 * {@code excess} is added to the rider's own velocity, plus {@code deckThrowLift * |excess|}
 * along its up so ground friction doesn't just skid it to a stop. VS goes on carrying it at the
 * deck's new speed, so in the world it keeps its old speed less the grip: a ship that stops dead
 * throws its crew forward off the bow, into whatever the ship hit (ordinary FullStop impacts).
 * The same rule covers a ship lurching forward (riders slide aft), swerving, or spinning up.
 * "The same piece of deck" follows the ship's turn between the frames, so a fast spin flings too.
 * A grounded rider never keeps velocity INTO its deck (a ship rising or landing hard presses it
 * down; the deck holds it).
 *
 * <p>Riders: everything VS carries with this ship (standing on its deck, or just jumped off it)
 * - players, mobs, dropped items, boats and carts with their passengers. Never thrown: anchored
 * riders (standing on a {@code fullstop:sticky} block such as slime or honey, or held by the
 * gravity mod: Camafy's {@link Gravity#isAnchored}, Gravity Unbound's plating and Surface Cling),
 * seated riders unless {@code deckMomentumSeated}, creative-flying players, NoAI mobs,
 * {@code fullstop:physics_blacklist} entities and bodies another mod moves ({@code MOTION} claims:
 * MKW karts, grappling players). Ships in grace (new, teleported) throw nobody.
 *
 * <p>Cheap when nothing happens: a ship is only looked at when its deck changed speed beyond the
 * grip somewhere (checked at its bounds' corners), which a cruising, resting or gently turning
 * ship never does.
 */
public final class ShipDeckMomentum {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Retained speed below this (m/s) isn't worth a velocity write, and for a player a packet. */
    static final double MIN_EXCESS = 0.25;
    /** The grip allowance never spans more than this (s): a long lag spike can't hide a crash. */
    private static final double MAX_FRAME_SECONDS = 0.25;
    /** VS keeps carrying an entity this many ticks after it last stood on a ship. */
    private static final int CARRY_TICKS = 25;
    /** How far below the feet (along the rider's down) its footing is probed for sticky blocks. */
    private static final double[] FOOTING_DEPTHS = {0.05, 0.3};
    private static final double FOOTING_INSET = 0.05;

    /** The last throw of each entity, for the debug log and the game tests. */
    private static final Map<Entity, Throw> LAST_THROW = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * One throw: when, by which ship, the deck's lost velocity and what the rider kept (m/s,
     * world; a seated rider's whole new velocity), and where the rider was.
     */
    public record Throw(long tick, long shipId, Vec3 lost, Vec3 kept, boolean seated, Vec3 at) {}

    private ShipDeckMomentum() {
    }

    /** The last throw {@code entity} got, or null. */
    @Nullable
    public static Throw lastThrow(Entity entity) {
        return LAST_THROW.get(entity);
    }

    /** Deck momentum is switched on (and VS compat with it). */
    static boolean enabled() {
        return SERVER.deckMomentum.get();
    }

    /**
     * Where this ship's riders are this tick, or null when none can be thrown: no new physics
     * frame, grace, or no part of its bounds whose velocity changed beyond the grip.
     */
    @Nullable
    static AABB deckBox(ShipTracker.State state, AABB bounds, long now) {
        if (!state.newFrame() || state.inGrace(now)) return null;
        double allowance = allowance(state);
        // the change is linear in position (rigid motion), so the corners of a box around every
        // rider bound it
        AABB reach = bounds.inflate(2.5);
        double worst = 0.0;
        for (int corner = 0; corner < 9; corner++) {
            Vec3 point = corner == 8 ? reach.getCenter() : new Vec3(
                    (corner & 1) == 0 ? reach.minX : reach.maxX,
                    (corner & 2) == 0 ? reach.minY : reach.maxY,
                    (corner & 4) == 0 ? reach.minZ : reach.maxZ);
            worst = Math.max(worst, state.prevMaterialVelocityAt(point).subtract(state.velocityAt(point)).length());
        }
        if (worst <= allowance + MIN_EXCESS) return null;
        // riders stand on the hull and stick out of it; a Gravity Unbound walker stands on its sides
        return reach;
    }

    /** The deck speed change (m/s) riders ride out in the latest frame. */
    private static double allowance(ShipTracker.State state) {
        return SERVER.deckGrip.get() * Math.min(state.frameSeconds(), MAX_FRAME_SECONDS);
    }

    /** Throws this ship's riders among {@code nearby} (see the class notes). */
    static void process(ServerLevel level, ShipRef ship, ShipTracker.State state, AABB deckBox, List<Entity> nearby) {
        long now = level.getGameTime();
        double allowance = allowance(state);
        double lift = SERVER.deckThrowLift.get();
        boolean seated = SERVER.deckMomentumSeated.get();
        for (Entity entity : nearby) {
            if (!entity.getBoundingBox().intersects(deckBox) || entity.isRemoved()) continue;
            if (entity.isPassenger()) {
                // a rider's vehicle is handled as the body (a boat on the deck carries its crew);
                // only a seat that is part of THIS ship throws its occupant, and only if asked
                if (seated && ship.equals(ShipEntities.mountedTo(entity)) && throwable(entity)) {
                    throwSeated(ship, state, entity, allowance, lift, now);
                }
                continue;
            }
            if (!throwable(entity) || !ridesDeck(ship, entity) || anchored(level, ship, entity)) continue;

            GravityFrame frame = FullStopCapability.currentFrame(entity);
            Vec3 feet = entity.position();
            Vec3 lost = state.prevMaterialVelocityAt(feet).subtract(state.velocityAt(feet));
            Vec3 kept = beyondGrip(lost, allowance);
            if (entity.onGround()) {
                // the deck holds a grounded rider against being pressed into it
                double into = -frame.vertical(kept);
                if (into > 0.0) kept = kept.add(frame.up().scale(into));
            }
            double keptSpeed = kept.length();
            if (keptSpeed < MIN_EXCESS) continue;
            Vec3 added = kept.add(frame.up().scale(lift * keptSpeed));

            // The rider's own motion, in world axes, blocks per tick. A server-side player's
            // deltaMovement is meaningless (the client moves it), so the measured one stands in.
            Vec3 ownBpt = entity instanceof Player ? VelocityMath.entityVelocity(entity).scale(0.05)
                    : Gravity.getWorldVelocity(entity, frame);
            Gravity.setWorldVelocity(entity, ownBpt.add(added.scale(0.05)), frame);
            entity.hasImpulse = true;
            entity.hurtMarked = true;
            PacketHandler.syncMotionToControllingDriver(entity);
            PacketHandler.syncLaunchToPlayer(entity);
            record(entity, new Throw(now, ship.id(), lost, added, false, feet), ship);
        }
    }

    /**
     * A seated rider thrown out of its seat (deckMomentumSeated): it leaves the ship, so VS stops
     * carrying it, and takes the seat's world velocity with it along with what it kept.
     */
    private static void throwSeated(ShipRef ship, ShipTracker.State state, Entity entity, double allowance, double lift,
                                    long now) {
        Vec3 at = entity.position();
        Vec3 lost = state.prevMaterialVelocityAt(at).subtract(state.velocityAt(at));
        Vec3 kept = beyondGrip(lost, allowance);
        double keptSpeed = kept.length();
        if (keptSpeed < MIN_EXCESS) return;
        GravityFrame frame = FullStopCapability.currentFrame(entity);
        Vec3 world = state.velocityAt(at).add(kept).add(frame.up().scale(lift * keptSpeed));
        entity.stopRiding();
        Gravity.setWorldVelocity(entity, world.scale(0.05), frame);
        entity.hasImpulse = true;
        entity.hurtMarked = true;
        PacketHandler.syncLaunchToPlayer(entity);
        record(entity, new Throw(now, ship.id(), lost, world, true, at), ship);
    }

    /** {@code lost} less the grip allowance, along its own direction; zero within it. */
    static Vec3 beyondGrip(Vec3 lost, double allowance) {
        double speed = lost.length();
        if (!(speed > allowance) || !Double.isFinite(speed)) return Vec3.ZERO;
        return lost.scale(1.0 - allowance / speed);
    }

    /** Whether FullStop may throw this entity at all (see the class notes). */
    private static boolean throwable(Entity entity) {
        if (!entity.isAlive() || entity.isSpectator() || entity.noPhysics) return false;
        if (entity instanceof Player player && (player.getAbilities().flying || player.isSpectator())) return false;
        if (entity instanceof Mob mob && mob.isNoAi()) return false;
        if (entity instanceof LivingEntity living && living.isDeadOrDying()) return false;
        if (entity.getType().is(FullStopTags.PHYSICS_BLACKLIST)) return false;
        for (Entity member : EntityStack.selfAndPassengers(entity)) {
            if (PhysicsClaims.handsOff(member, PhysicsAspect.MOTION)) return false;
        }
        return true;
    }

    /** Whether VS is carrying the entity with this ship: on its deck, or just jumped off it. */
    private static boolean ridesDeck(ShipRef ship, Entity entity) {
        return ShipEntities.isCarried(entity) && ship.equals(ShipEntities.standingOn(entity, CARRY_TICKS));
    }

    /**
     * Held fast to the deck: the gravity mod anchors it (plating, Surface Cling), or it stands
     * on a {@code fullstop:sticky} block of this ship (slime, honey).
     */
    private static boolean anchored(ServerLevel level, ShipRef ship, Entity entity) {
        if (SERVER.enableGravityUnboundCompat.get() && Gravity.isAnchored(entity)) return true;
        return entity.onGround() && onStickyBlock(level, ship, entity);
    }

    /**
     * Whether a sticky block of this ship is right under the entity's feet, along its own down
     * (so a Gravity Unbound walker on a slime-covered hull side counts): the centre and four
     * inset corners of its footprint, probed a little below the feet in ship space.
     */
    static boolean onStickyBlock(ServerLevel level, ShipRef ship, Entity entity) {
        GravityFrame frame = FullStopCapability.currentFrame(entity);
        Vec3 down = frame.up().scale(-1.0);
        AABB box = entity.getBoundingBox();
        Vec3 feet = entity.position();
        // the footprint: the box's extent across the rider's up, around its feet
        double halfWidth = Math.max(0.0, Math.min(box.getXsize(), box.getZsize()) * 0.5 - FOOTING_INSET);
        Vec3 side = Math.abs(down.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 a = down.cross(side).normalize().scale(halfWidth);
        Vec3 b = down.cross(a).normalize().scale(halfWidth);
        Vec3[] footprint = {Vec3.ZERO, a.add(b), a.subtract(b), b.subtract(a), a.add(b).scale(-1)};
        for (Vec3 offset : footprint) {
            for (double depth : FOOTING_DEPTHS) {
                Vec3 local = ship.toShip(feet.add(offset).add(down.scale(depth)));
                if (local == null) return false;
                if (level.getBlockState(BlockPos.containing(local)).is(FullStopTags.STICKY)) return true;
            }
        }
        return false;
    }

    private static void record(Entity entity, Throw thrown, ShipRef ship) {
        LAST_THROW.put(entity, thrown);
        if (SERVER.debugShipCollisionLog.get()) {
            LOGGER.info("[FullStop] ship {} threw {}{}: deck lost ({}) m/s, rider keeps ({}) m/s",
                    ship.id(), entity.getName().getString(), thrown.seated() ? " out of its seat" : "",
                    fmt(thrown.lost()), fmt(thrown.kept()));
        }
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }
}
