package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.physics.EntityStack;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.CamafyImpulses;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.camacraft.fullstop.server.physics.BodyPush;
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
 *   lost   = deck velocity at the feet, previous frame, turned with the ship
 *            - the same piece of deck now                                     (m/s)
 *   excess = lost * max(0, 1 - deckGrip * dt / |lost|)      dt = the frame's time span
 * </pre>
 * {@code excess} is added to the rider's own velocity, plus {@code deckThrowLift * |excess|}
 * along its up so ground friction doesn't just skid it to a stop. VS goes on carrying it at the
 * deck's new speed, so in the world it keeps its old speed less the grip: a ship that stops dead
 * throws its crew forward off the bow, into whatever the ship hit (ordinary FullStop impacts).
 * The same rule covers a ship lurching forward (riders slide aft), throwing itself into a turn, or
 * spinning up or down. "The same piece of deck" follows the ship's turn between the frames, and
 * its old velocity is turned with the ship ({@link ShipTracker.State#deckChangeAt}): VS turns the
 * riders it carries with the deck, so a ship turning or spinning STEADILY - a Clockwork rotor at
 * 24 or 256 RPM, a ship holding a curve - changes nothing under them, and they stay put as VS
 * keeps them (it used to count the steady centripetal turn of the deck's velocity as a jolt, and
 * slid everyone on a fast rotor off it every frame).
 * A grounded rider never keeps velocity INTO its deck (a ship rising or landing hard presses it
 * down; the deck holds it).
 *
 * <p>Riders: everything VS carries with this ship (standing on its deck, or just jumped off it)
 * - players, mobs, dropped items, boats and carts with their passengers. Never thrown: anchored
 * riders (standing on a {@code fullstop:sticky} block such as slime or honey, or held by the
 * gravity mod: Camafy's {@link Gravity#isAnchored}, Gravity Unbound's plating and Surface Cling),
 * seated riders unless {@code deckMomentumSeated}, creative-flying players, NoAI mobs,
 * {@code fullstop:physics_blacklist} entities and bodies another mod moves ({@code MOTION} claims:
 * a grappling player) - unless that mod takes velocity changes through Camafy's {@code Impulses}
 * (a free-roaming MKW kart: the throw goes into its own speed, on its driver's client). Ships in
 * grace (new, teleported) throw nobody, and a deck never throws the rider it has just collided with
 * (its landing: the two already move together).
 *
 * <p>A jolt must be real to throw anyone: the change a physics frame brought is judged when the
 * next frame arrives and thrown only as far as it still stands then ({@link ShipTracker.Jolt}) -
 * the deck of a rotor on a physics bearing (Clockwork) jitters for a frame and comes back, and each
 * such spike used to throw its riders - and never by less than {@link #MIN_EXCESS}. A player gets
 * the throw as a change to its own motion on its client ({@link BodyPush}).
 *
 * <p>A thrown rider pushes the ship that threw it no more (landing back on the deck, slamming
 * into the bow) until it has settled again: the momentum it kept is the deck's own, and handing it
 * back would pump the ship (a capsizing raft throwing its heavy rider, the rider landing, the raft
 * flipping further...). It still takes its damage.
 *
 * <p>Cheap when nothing happens: a ship is only looked at when its deck changed speed beyond the
 * grip somewhere (checked at its bounds' corners), which a cruising, resting or gently turning
 * ship never does.
 */
public final class ShipDeckMomentum {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * Retained speed below this (m/s) isn't thrown: a nudge that small is no jolt a rider would
     * lose its footing to, and for a player it would be a packet fighting its own movement.
     */
    static final double MIN_EXCESS = 1.0;
    /** A jolt is only thrown by as much of it as still stands a frame later ({@link ShipTracker.Jolt#persisted}). */
    static final double MIN_PERSISTED = 0.5;
    /** The grip allowance never spans more than this (s): a long lag spike can't hide a crash. */
    private static final double MAX_FRAME_SECONDS = 0.25;
    /** VS keeps carrying an entity this many ticks after it last stood on a ship. */
    private static final int CARRY_TICKS = 25;
    /** How far below the feet (along the rider's down) its footing is probed for sticky blocks. */
    private static final double[] FOOTING_DEPTHS = {0.05, 0.3};
    private static final double FOOTING_INSET = 0.05;

    /** The last throw of each entity, for the debug log and the game tests. */
    private static final Map<Entity, Throw> LAST_THROW = Collections.synchronizedMap(new WeakHashMap<>());
    /** The game time each nearby entity was last seen seated in a vehicle: just out, it isn't judged for a moment. */
    private static final Map<Entity, Long> LAST_SEATED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int JUST_OUT_TICKS = 5;

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
     * Where this ship's riders are this tick, or null when none can be thrown: no deck change up
     * for judging ({@link ShipTracker.State#joltMode}), grace, or no part of its bounds whose
     * velocity changed beyond the grip.
     */
    @Nullable
    static AABB deckBox(ServerLevel level, ShipTracker.State state, AABB bounds, long now) {
        if (state.inGrace(now)) return null;
        if (state.deckQuiet(now)) {
            // VS's physics is catching up after a stall: whatever it does now isn't judged
            ShipTracker.JoltMode mode = state.joltMode(now);
            if (mode != ShipTracker.JoltMode.NONE) state.skipJudged(mode);
            return null;
        }
        ShipTracker.JoltMode mode = state.joltMode(now);
        if (mode == ShipTracker.JoltMode.NONE) return null;
        // the least grip a rider here can have: the local gravity's (none in space)
        double allowance = allowance(state, mode) * Math.min(1.0, Gravity.ambientStrength(level, bounds.getCenter()));
        // the change is linear in position (rigid motion), so the corners of a box around every
        // rider bound it
        AABB reach = bounds.inflate(2.5);
        double worst = 0.0;
        for (int corner = 0; corner < 9; corner++) {
            Vec3 point = corner == 8 ? reach.getCenter() : new Vec3(
                    (corner & 1) == 0 ? reach.minX : reach.maxX,
                    (corner & 2) == 0 ? reach.minY : reach.maxY,
                    (corner & 4) == 0 ? reach.minZ : reach.maxZ);
            worst = Math.max(worst, state.joltAt(point, mode).change().length());
        }
        if (worst <= allowance + MIN_EXCESS) {
            state.markJudged(mode);
            return null;
        }
        // riders stand on the hull and stick out of it; a Gravity Unbound walker stands on its sides
        return reach;
    }

    /** The deck speed change (m/s) riders ride out in the frame judged, at a full 1 g of grip. */
    private static double allowance(ShipTracker.State state, ShipTracker.JoltMode mode) {
        return SERVER.deckGrip.get() * Math.min(state.joltSeconds(mode), MAX_FRAME_SECONDS);
    }

    /**
     * Grip is friction, and friction scales with the rider's weight: its local gravity's
     * strength (a sixth on the Moon, none in VS Genesis's space or an Ad Astra orbit).
     */
    private static double gripShare(Entity entity) {
        return Math.min(1.0, Gravity.strength(entity));
    }

    /**
     * The lift a throw gets only beats ground friction (and keeps the air time the same where
     * gravity is low); in zero gravity it would send every thrown rider off upward for good.
     */
    private static double liftShare(Entity entity) {
        return Math.min(1.0, Gravity.strength(entity));
    }

    /** Throws this ship's riders among {@code nearby} (see the class notes). */
    static void process(ServerLevel level, ShipRef ship, ShipTracker.State state, AABB deckBox, List<Entity> nearby) {
        long now = level.getGameTime();
        ShipTracker.JoltMode mode = state.joltMode(now);
        if (mode == ShipTracker.JoltMode.NONE) return;
        state.markJudged(mode);
        // a piston moving the ship starts and stops it with the head, as it would a block: what stands on it goes along
        if (ShipPistons.moving(level, ship.id(), now)) return;
        double allowance = allowance(state, mode);
        double lift = SERVER.deckThrowLift.get();
        boolean seated = SERVER.deckMomentumSeated.get();
        boolean log = SERVER.debugShipContactLog.get();
        for (Entity entity : nearby) {
            if (!entity.getBoundingBox().intersects(deckBox) || entity.isRemoved()) continue;
            if (entity.isPassenger()) {
                // noted here, whatever else ticks the entity: getting out is judged like a crouch (below)
                LAST_SEATED.put(entity, now);
                // a rider's vehicle is handled as the body (a boat on the deck carries its crew);
                // only a seat that is part of THIS ship throws its occupant, and only if asked -
                // otherwise the seat holds it, and it takes the jolt where it sits
                if (ship.equals(ShipEntities.mountedTo(entity)) && throwable(entity)) {
                    if (seated) throwSeated(ship, state, mode, entity, allowance, lift, now);
                    else hurtSeated(level, ship, state, mode, entity, allowance, now, log);
                }
                continue;
            }
            if (!throwable(entity) || !ridesDeck(ship, entity) || anchored(level, ship, entity)) continue;
            // This deck just collided with this rider (its landing, a hull hit): that exchange left
            // the two moving together - the deck's change there is the rider's own doing, not a jolt
            // to throw it by.
            FullStopCapability riderCap = FullStopCapability.grabCapability(entity);
            if (riderCap != null && riderCap.isShipHitOnCooldown(ship.id(), now)) continue;
            // a body that just crouched, stood up, or got into or out of a vehicle was put in two places this
            // tick (FullStopCapability.tick): the deck didn't jolt, its feet moved. Getting out is also noted
            // here (LAST_SEATED), since a player's capability is fed by its client and may not have ticked yet.
            if (riderCap != null && riderCap.rideOrPoseChangedLately(entity, 2)) continue;
            Long lastSeatedAt = LAST_SEATED.get(entity);
            if (lastSeatedAt != null && now - lastSeatedAt <= JUST_OUT_TICKS) continue;

            GravityFrame frame = FullStopCapability.currentFrame(entity);
            Vec3 feet = entity.position();
            ShipTracker.Jolt jolt = state.joltAt(feet, mode);
            Vec3 lost = jolt.change();
            double grip = allowance * gripShare(entity);
            if (lost.length() <= grip) continue;
            if (jolt.persisted() < MIN_PERSISTED) {
                // a spike: the deck came back the next frame (a rotor's bearing jittering)
                if (log && entity instanceof Player) {
                    LOGGER.info("[FullStop] ship {}'s deck jolted {} by ({}) m/s for one frame and came back ({}% of it stood): "
                            + "no throw", ship.id(), entity.getName().getString(), fmt(lost), Math.round(jolt.persisted() * 100.0));
                }
                continue;
            }
            Vec3 kept = beyondGrip(lost, grip);
            if (entity.onGround()) {
                // the deck holds a grounded rider against being pressed into it
                double into = -frame.vertical(kept);
                if (into > 0.0) kept = kept.add(frame.up().scale(into));
            }
            double keptSpeed = kept.length();
            if (keptSpeed < MIN_EXCESS) continue;
            Vec3 added = kept.add(frame.up().scale(lift * liftShare(entity) * keptSpeed));

            // Added to the rider's own motion by whoever simulates it (BodyPush): a driven vehicle's
            // client, through Camafy's Impulses (an MKW kart's own speed); a player unclamped (its
            // measured velocity stands in for the server's meaningless deltaMovement); anything
            // else here.
            if (!BodyPush.push(entity, added.scale(0.05), frame, VelocityMath.entityVelocity(entity))) continue;
            record(entity, new Throw(now, ship.id(), lost, added, false, feet), ship);
            if (log && entity instanceof Player) {
                LOGGER.info("[FullStop] ship {}'s deck threw {}: it lost ({}) m/s under them beyond the grip of {} m/s "
                                + "({}% still stood a frame later); ({}) m/s sent to their client as a change", ship.id(),
                        entity.getName().getString(), fmt(lost), fmt(grip), Math.round(jolt.persisted() * 100.0), fmt(added));
            }
        }
    }

    /**
     * A seated rider thrown out of its seat (deckMomentumSeated): it leaves the ship, so VS stops
     * carrying it, and takes the seat's world velocity with it along with what it kept.
     */
    private static void throwSeated(ShipRef ship, ShipTracker.State state, ShipTracker.JoltMode mode, Entity entity,
                                    double allowance, double lift, long now) {
        Vec3 at = entity.position();
        ShipTracker.Jolt jolt = state.joltAt(at, mode);
        if (jolt.persisted() < MIN_PERSISTED) return;
        Vec3 lost = jolt.change();
        Vec3 kept = beyondGrip(lost, allowance * gripShare(entity));
        double keptSpeed = kept.length();
        if (keptSpeed < MIN_EXCESS) return;
        GravityFrame frame = FullStopCapability.currentFrame(entity);
        Vec3 world = state.velocityAt(at).add(kept).add(frame.up().scale(lift * liftShare(entity) * keptSpeed));
        entity.stopRiding();
        Gravity.setWorldVelocity(entity, world.scale(0.05), frame);
        entity.hasImpulse = true;
        entity.hurtMarked = true;
        PacketHandler.syncLaunchToPlayer(entity);
        record(entity, new Throw(now, ship.id(), lost, world, true, at), ship);
    }

    /** The last tick each ship struck something at crash speed, per level (Camafy's collision reports). */
    private static final java.util.Map<ServerLevel, it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap> LAST_CRASH =
            new java.util.WeakHashMap<>();
    /** A seat only hurts its rider this soon (ticks) after its ship struck something. */
    private static final int CRASH_WINDOW_TICKS = 10;
    /** A contact slower than this (m/s) is no crash. */
    private static final double CRASH_MPS = 2.0;

    /** Notes the ships that struck terrain or another ship this tick (once per level tick, after the collisions are in). */
    static void noteCrashes(ServerLevel level, long now) {
        List<net.camacraft.camafy.compat.vs.ShipCollision> collisions = net.camacraft.camafy.compat.vs.ShipCollisions.thisTick(level);
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap last = LAST_CRASH.get(level);
        if (last != null && now % 200 == 0) last.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > 200);
        if (collisions.isEmpty()) return;
        if (last == null) {
            last = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
            LAST_CRASH.put(level, last);
        }
        for (net.camacraft.camafy.compat.vs.ShipCollision collision : collisions) {
            double fastest = 0.0;
            for (net.camacraft.camafy.compat.vs.ShipCollision.Contact contact : collision.contacts()) {
                fastest = Math.max(fastest, contact.approachMps());
            }
            if (fastest < CRASH_MPS) continue;
            // ships held together by joints touching each other (a rotor in its ship) is no crash
            if (collision.other() != null && net.camacraft.camafy.compat.vs.Ships.joined(level, collision.ship().id(), collision.other().id())) continue;
            last.put(collision.ship().id(), now);
            if (collision.other() != null) last.put(collision.other().id(), now);
        }
    }

    private static boolean crashedLately(ServerLevel level, ShipRef ship, long now) {
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap last = LAST_CRASH.get(level);
        return last != null && last.containsKey(ship.id()) && now - last.get(ship.id()) <= CRASH_WINDOW_TICKS;
    }

    /**
     * A seated rider held in its seat through a jolt (seat belts: deckMomentumSeated off). It goes wherever the seat
     * goes, so it takes the jolt as an impact instead: the deck's change beyond what it could hold on through, at
     * half the damage (the seat takes the rest, as a vehicle's riders take half of its crash). Only a crash - the ship
     * struck terrain or another ship just now - hurts its crew. A ship turning, spinning or speeding up hard doesn't,
     * however its speed changed: a rotor spinning so fast that it turns more than half a revolution a tick can't be
     * told from one reversing, and its seated rider "crashed" at the seat's full 425 m/s (the owner's test).
     */
    private static void hurtSeated(ServerLevel level, ShipRef ship, ShipTracker.State state, ShipTracker.JoltMode mode, Entity entity,
                                   double allowance, long now, boolean log) {
        if (!(entity instanceof LivingEntity living) || net.camacraft.fullstop.common.physics.rules.DamageImmunityRules.isDamageImmune(living)) return;
        if (PhysicsClaims.handsOff(entity, PhysicsAspect.IMPACT)) return;
        if (!crashedLately(level, ship, now)) return;
        ShipTracker.Jolt jolt = state.joltAt(entity.position(), mode);
        if (jolt.persisted() < MIN_PERSISTED) return;
        Vec3 kept = beyondGrip(jolt.change(), allowance * gripShare(entity));
        double speed = kept.length();
        if (speed < MIN_EXCESS) return;
        GravityFrame frame = FullStopCapability.currentFrame(entity);
        boolean vertical = Math.abs(frame.vertical(kept)) > 0.7 * speed;
        double damage = 0.5 * net.camacraft.fullstop.server.physics.damage.KineticDamageCalculator.damageForSpeedLost(
                entity, speed, vertical, List.of());
        if (damage < 1) return;
        boolean hurt = entity.hurt(net.camacraft.fullstop.server.physics.damage.FullStopDamageSources.seatCrash(entity,
                net.camacraft.fullstop.server.physics.damage.FullStopDamageSources.velocityDisplay(speed), ship.id()), (float) damage);
        if (log || SERVER.debugShipCollisionLog.get()) {
            LOGGER.info("[FullStop] ship {}'s crash held {} in its seat: the seat lost ({}) m/s beyond the grip, {} damage{}",
                    ship.id(), entity.getName().getString(), fmt(kept), fmt(damage), hurt ? "" : " (not taken)");
        }
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
        if (FullStopTags.physicsBlacklisted(entity.getType())) return false;
        for (Entity member : EntityStack.selfAndPassengers(entity)) {
            // another mod moves it - unless it takes velocity changes through Camafy's Impulses
            // (an MKW kart: its own speed fields take the throw)
            if (PhysicsClaims.handsOff(member, PhysicsAspect.MOTION) && !(member == entity && CamafyImpulses.scripted(member))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The ship each player's own client has let go of until it lands ({@code ShipReleasePacket}):
     * the player is in the air, and this server's record of who rides a deck is a few ticks behind.
     */
    private static final Map<Entity, Long> CLIENT_RELEASED = new WeakHashMap<>();

    /** A player's client let go of {@code shipId} ({@link ShipRef#NO_ID}: it landed). Server thread. */
    public static void clientReleased(net.minecraft.server.level.ServerPlayer player, long shipId) {
        if (shipId == ShipRef.NO_ID) CLIENT_RELEASED.remove(player);
        else CLIENT_RELEASED.put(player, shipId);
    }

    /** Whether VS is carrying the entity with this ship: on its deck, or just jumped off it. */
    private static boolean ridesDeck(ShipRef ship, Entity entity) {
        Long released = CLIENT_RELEASED.get(entity);
        if (released != null && released == ship.id()) return false;
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
        // What the ship gives its rider must not come back into the same ship: until the rider
        // has settled again, its landings and slams on this ship push it no more.
        FullStopCapability cap = FullStopCapability.grabCapability(entity);
        if (cap != null) cap.markThrownBy(ship.id(), thrown.tick());
        if (SERVER.debugShipCollisionLog.get()) {
            LOGGER.info("[FullStop] ship {} threw {}{}: deck lost ({}) m/s, rider keeps ({}) m/s",
                    ship.id(), entity.getName().getString(), thrown.seated() ? " out of its seat" : "",
                    fmt(thrown.lost()), fmt(thrown.kept()));
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }
}
