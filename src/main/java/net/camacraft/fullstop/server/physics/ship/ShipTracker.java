package net.camacraft.fullstop.server.physics.ship;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * What FullStop remembers about each loaded ship of a level from one tick to the next: its
 * motion in the latest physics frame and in the frame before (the speed it hit something WITH -
 * by the time the game tick sees a crash, VS's solver has already stopped the ship), and whether
 * it is still in its grace period.
 *
 * <p><b>Physics frames, not ticks.</b> Valkyrien Skies simulates on its own thread at 60 Hz and
 * the game tick picks up whatever frame is newest. A tick can bring no new frame at all (a server
 * ticking faster than physics - the game-test server, or a late frame), and the tick after it
 * then brings two ticks' worth. So "before" is the previous FRAME, a tick that brought nothing
 * new changes nothing, and each frame knows how much time it spans ({@link State#frameSeconds}:
 * the game time between the two frames' ticks, the real time if that was longer, as on a lagging
 * server, or the time the motion itself took if longer still, as when physics catches up in a
 * burst). A tick brings a new frame when the ship's position, rotation, velocity or spin differs
 * in any bit from the tick before.
 *
 * <p>Grace: a ship FullStop sees for the first time (just assembled, loaded with its chunk,
 * pasted from a schematic, split off another ship) is harmless for {@code newShipGraceTicks},
 * and one that jumped - a teleport, or its mass changing by more than a quarter in one tick - for
 * {@link #JUMP_GRACE_TICKS}. Nothing a ship does in its grace counts as a collision. A jump is a
 * new frame whose centre of mass lies off the path its velocity (the two frames' mean) could have
 * carried it along over up to {@link #JUMP_TIME_SLACK} times the frame's time span (and at least
 * {@link #JUMP_MIN_WINDOW_SECONDS}): a frame that didn't come, two that came at once, or a burst
 * of physics catching up is never taken for a teleport, at any speed.
 *
 * <p>Server thread only.
 */
public final class ShipTracker {

    /** Grace after a ship teleports or its mass jumps. */
    static final int JUMP_GRACE_TICKS = 10;
    /** One game tick, seconds. */
    static final double TICK_SECONDS = 0.05;
    /**
     * A frame may carry the ship this many times as far as its time span says before it is a
     * jump, and at least {@link #JUMP_MIN_WINDOW_SECONDS} of travel: VS's physics runs on its own
     * clock and catches up in bursts (measured on the game-test server: a ship pushed to 72 m/s
     * moved 12.7 m in one frame seen 0.05 s after the last, 0.18 s of physics), and "real time" is
     * only measured between game ticks.
     */
    static final double JUMP_TIME_SLACK = 1.5;
    /** See {@link #JUMP_TIME_SLACK}. */
    static final double JUMP_MIN_WINDOW_SECONDS = 0.5;
    /** The smallest displacement off the expected path that counts as a jump (blocks). */
    static final double JUMP_MIN_DISTANCE = 2.0;
    /** Cap on {@link State#sweepSeconds}. */
    static final double MAX_SWEEP_SECONDS = 0.25;
    private static final int PRUNE_INTERVAL_TICKS = 100;
    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final Map<ServerLevel, ShipTracker> TRACKERS = new WeakHashMap<>();

    private final Long2ObjectOpenHashMap<State> states = new Long2ObjectOpenHashMap<>();

    private ShipTracker() {
    }

    public static ShipTracker of(ServerLevel level) {
        return TRACKERS.computeIfAbsent(level, l -> new ShipTracker());
    }

    /** One ship's remembered motion. Positions are the centre of mass, world space. */
    public static final class State {
        final long firstSeen;
        long lastSeen;
        long graceUntil;
        /** The latest physics frame: centre of mass, motion (m/s, rad/s) and ship-to-world rotation. */
        Vec3 position;
        Vec3 velocity;
        Vec3 angularVelocity;
        Quaterniond rotation;
        double mass;
        /** The physics frame before it; equal to the latest one until a second frame arrives. */
        Vec3 prevPosition;
        Vec3 prevVelocity;
        Vec3 prevAngularVelocity;
        Quaterniond prevRotation;
        /** Whether this tick brought a new physics frame. */
        boolean newFrame;
        /** Seconds between the previous frame and the latest (at least one tick). */
        double frameSeconds = TICK_SECONDS;
        /** Game tick and {@link System#nanoTime} at which the latest frame was first seen. */
        long frameTick;
        long frameNanos;

        private State(long now, long nanos, Vec3 position, Vec3 velocity, Vec3 angularVelocity, Quaterniond rotation,
                      double mass) {
            this.firstSeen = now;
            this.lastSeen = now;
            this.frameTick = now;
            this.frameNanos = nanos;
            this.position = this.prevPosition = position;
            this.velocity = this.prevVelocity = velocity;
            this.angularVelocity = this.prevAngularVelocity = angularVelocity;
            this.rotation = rotation;
            this.prevRotation = new Quaterniond(rotation);
            this.mass = mass;
        }

        public boolean inGrace(long now) {
            return now < graceUntil;
        }

        public long graceLeft(long now) {
            return Math.max(0, graceUntil - now);
        }

        /** The latest frame's velocity of the centre of mass, m/s. */
        public Vec3 velocity() {
            return velocity;
        }

        /** The latest frame's spin, rad/s. */
        public Vec3 angularVelocity() {
            return angularVelocity;
        }

        /** Whether this tick brought a new physics frame (motion changes only on those ticks). */
        public boolean newFrame() {
            return newFrame;
        }

        /** Seconds the latest frame advanced the ship past the previous one (at least one tick). */
        public double frameSeconds() {
            return frameSeconds;
        }

        /**
         * How far ahead (seconds of the ship's motion) a check made this tick must look for what
         * the hull will reach before the next frame can show it: a tick, plus every tick already
         * gone by without a new frame (the next one carries them all), or the latest frame's own
         * span when that was longer (a lagging server); at most {@link #MAX_SWEEP_SECONDS}. A
         * fast hull whose frames come in pairs would otherwise jump clean over a small entity.
         */
        public double sweepSeconds(long now) {
            double pending = TICK_SECONDS * (1 + Math.max(0, now - frameTick));
            return Math.min(MAX_SWEEP_SECONDS, Math.max(pending, frameSeconds));
        }

        /** Kg; +infinity when VS reports none (an unresolved or weightless ship). */
        public double mass() {
            return mass > 0 && Double.isFinite(mass) ? mass : Double.POSITIVE_INFINITY;
        }

        /** The latest frame's velocity of the ship's material at a world point, m/s: v + w x r. */
        public Vec3 velocityAt(Vec3 worldPoint) {
            Vec3 r = worldPoint.subtract(position);
            return velocity.add(angularVelocity.cross(r));
        }

        /**
         * The previous frame's velocity of the ship's material at a world point, m/s: v + w x r
         * about that frame's centre of mass. What the hull at that point was doing just before a
         * crash.
         */
        public Vec3 prevVelocityAt(Vec3 worldPoint) {
            Vec3 r = worldPoint.subtract(prevPosition);
            return prevVelocity.add(prevAngularVelocity.cross(r));
        }

        /**
         * The previous frame's velocity of the piece of the ship that is at {@code worldPoint}
         * now, m/s: the point is carried back through the ship's turn between the two frames
         * before {@code v + w x r} is taken. {@code prevMaterialVelocityAt(p) - velocityAt(p)} is
         * then the velocity that piece of deck lost in the latest frame - on a ship spinning
         * steadily, the centripetal change too, which the fixed-point {@link #prevVelocityAt}
         * can't see.
         */
        public Vec3 prevMaterialVelocityAt(Vec3 worldPoint) {
            Vector3d r = new Vector3d(worldPoint.x - position.x, worldPoint.y - position.y, worldPoint.z - position.z);
            rotation.transformInverse(r);
            prevRotation.transform(r);
            return prevVelocity.add(prevAngularVelocity.cross(new Vec3(r.x, r.y, r.z)));
        }
    }

    /**
     * Records this tick's motion of {@code ship} (called once per ship per tick, before any
     * collision handling) and returns its state, or null when VS can't resolve its motion.
     */
    @Nullable
    public State observe(ShipRef ship, long now, int newShipGraceTicks) {
        Vec3 position = ship.position();
        Vec3 velocity = ship.velocity();
        Vec3 omega = ship.angularVelocity();
        Quaterniond rotation = ship.rotation();
        if (position == null || velocity == null || omega == null || rotation == null) return null;
        double mass = ship.mass();
        long nanos = System.nanoTime();

        State state = states.get(ship.id());
        if (state == null) {
            state = new State(now, nanos, position, velocity, omega, rotation, mass);
            state.graceUntil = now + newShipGraceTicks;
            states.put(ship.id(), state);
            return state;
        }
        if (state.lastSeen == now) return state;

        if (state.lastSeen < now - 1) {
            // Not seen for a while (its chunks unloaded): back like a new ship.
            state.graceUntil = Math.max(state.graceUntil, now + newShipGraceTicks);
            state.prevPosition = position;
            state.prevVelocity = velocity;
            state.prevAngularVelocity = omega;
            state.prevRotation = new Quaterniond(rotation);
            state.newFrame = false;
            state.frameSeconds = TICK_SECONDS;
            state.frameTick = now;
            state.frameNanos = nanos;
        } else {
            boolean fresh = !position.equals(state.position) || !velocity.equals(state.velocity)
                    || !omega.equals(state.angularVelocity) || !rotation.equals(state.rotation);
            boolean jumped = false;
            String why = null;
            if (fresh) {
                double gameSeconds = (now - state.frameTick) * TICK_SECONDS;
                double realSeconds = (nanos - state.frameNanos) * 1.0e-9;
                double seconds = Math.max(gameSeconds, realSeconds);
                jumped = offPath(state.position, state.velocity, position, velocity, seconds);
                if (jumped) {
                    why = String.format(java.util.Locale.ROOT, "moved (%.3f, %.3f, %.3f) in %.3f s at (%.2f, %.2f, %.2f) -> "
                                    + "(%.2f, %.2f, %.2f) m/s", position.x - state.position.x, position.y - state.position.y,
                            position.z - state.position.z, seconds, state.velocity.x, state.velocity.y, state.velocity.z,
                            velocity.x, velocity.y, velocity.z);
                }
                if (!jumped) {
                    // a burst of physics catching up spans more than the clocks say: how long the
                    // motion itself took, when there is enough of it to tell
                    seconds = Math.max(seconds, physicsSeconds(state.position, state.velocity, state.rotation,
                            state.angularVelocity, position, velocity, rotation, omega));
                }
                state.prevPosition = state.position;
                state.prevVelocity = state.velocity;
                state.prevAngularVelocity = state.angularVelocity;
                state.prevRotation = state.rotation;
                state.frameSeconds = Math.max(TICK_SECONDS, seconds);
                state.frameTick = now;
                state.frameNanos = nanos;
            }
            state.newFrame = fresh;

            // Blocks merged in or split off: the hull changed under us.
            double before = state.mass;
            if (before > 0 && Double.isFinite(before) && mass > 0 && Double.isFinite(mass)
                    && Math.abs(mass - before) > 0.25 * before) {
                jumped = true;
                why = String.format(java.util.Locale.ROOT, "mass %.0f -> %.0f kg", before, mass);
            }
            if (jumped) {
                state.graceUntil = Math.max(state.graceUntil, now + JUMP_GRACE_TICKS);
                if (net.camacraft.fullstop.FullStopConfig.SERVER.debugShipCollisionLog.get()) {
                    LOGGER.info("[FullStop] ship {} jumped at t{}: {}; grace until t{}", ship.id(), now, why, state.graceUntil);
                }
            }
        }
        state.position = position;
        state.velocity = velocity;
        state.angularVelocity = omega;
        state.rotation = rotation;
        state.mass = mass;
        state.lastSeen = now;
        return state;
    }

    /**
     * Whether the centre of mass arrived somewhere its motion couldn't have carried it in one
     * frame: farther than {@link #JUMP_MIN_DISTANCE} (or half a tick of travel) from the path
     * {@code mean velocity * t} for {@code t} up to {@link #JUMP_TIME_SLACK} times the frame's
     * time span.
     */
    static boolean offPath(Vec3 from, Vec3 velocityBefore, Vec3 to, Vec3 velocityAfter, double seconds) {
        Vec3 moved = to.subtract(from);
        Vec3 mean = velocityBefore.add(velocityAfter).scale(0.5);
        double speed = Math.max(velocityBefore.length(), velocityAfter.length());
        double allowed = Math.max(JUMP_MIN_DISTANCE, 0.5 * speed * TICK_SECONDS);
        double meanSqr = mean.lengthSqr();
        double window = Math.max(JUMP_TIME_SLACK * seconds, JUMP_MIN_WINDOW_SECONDS);
        double t = meanSqr > 1.0e-12 ? Mth.clamp(moved.dot(mean) / meanSqr, 0.0, window) : 0.0;
        return moved.distanceToSqr(mean.scale(t)) > allowed * allowed;
    }

    /**
     * How much physics time one frame's motion took, estimated from the motion itself: the
     * distance along the mean velocity over that velocity, and the turn over the mean spin (the
     * larger), each at most {@link #JUMP_MIN_WINDOW_SECONDS}; 0 for a ship that barely moves.
     */
    static double physicsSeconds(Vec3 from, Vec3 velocityBefore, Quaterniond rotationBefore, Vec3 omegaBefore,
                                 Vec3 to, Vec3 velocityAfter, Quaterniond rotationAfter, Vec3 omegaAfter) {
        double seconds = 0.0;
        Vec3 mean = velocityBefore.add(velocityAfter).scale(0.5);
        double meanSqr = mean.lengthSqr();
        if (meanSqr > 0.25) {
            seconds = Math.max(seconds, to.subtract(from).dot(mean) / meanSqr);
        }
        double spin = omegaBefore.add(omegaAfter).scale(0.5).length();
        if (spin > 0.05) {
            Quaterniond turn = new Quaterniond(rotationBefore).conjugate().mul(rotationAfter);
            double angle = 2.0 * Math.acos(Math.min(1.0, Math.abs(turn.w)));
            seconds = Math.max(seconds, angle / spin);
        }
        return Mth.clamp(seconds, 0.0, JUMP_MIN_WINDOW_SECONDS);
    }

    /** The state recorded for ship {@code id} this tick, or null. */
    @Nullable
    public State get(long id, long now) {
        State state = states.get(id);
        return state != null && state.lastSeen == now ? state : null;
    }

    /** Forgets ships that are no longer loaded (every few seconds). */
    public void prune(ServerLevel level, long now) {
        if (now % PRUNE_INTERVAL_TICKS != 0 || states.isEmpty()) return;
        LongSet loaded = Ships.loadedIds(level);
        states.long2ObjectEntrySet().removeIf(e -> !loaded.contains(e.getLongKey()));
    }
}
