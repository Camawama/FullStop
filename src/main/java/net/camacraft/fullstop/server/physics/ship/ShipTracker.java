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
 * <p><b>Checked against the pose.</b> A frame's velocity and spin are VS's report, unless the
 * frame's own move contradicts it: VS reported (almost) no motion, yet the ship moved on as it had
 * been moving - farther than {@link #POSE_SLACK} times the reported motion could carry it, about as
 * fast and the same way as before. The frame's motion is then the move over its time span. VS
 * reports a rotor that a Clockwork phys bearing spins as not turning at all for a frame now and
 * then while it turns on steadily; taken at its word, the deck under a rider lost its whole speed
 * and got it back, a throw each time.
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
    /**
     * Real time (s) without a new physics frame, for a ship that was moving, that counts as VS's
     * physics stalling (a hitch of its thread, a lag spike) rather than the ship coming to rest: six
     * of VS's 60 Hz steps. Real time, not game ticks: a server ticking faster than VS's physics (a
     * game test server, a lagging one catching up) goes several ticks between frames all the time.
     */
    static final double STALL_SECONDS = 0.1;
    /**
     * Real time (s) after a stall in which no deck change is judged ({@link State#deckQuiet}): what
     * VS's physics does while it catches up isn't something the ship did to its riders. A Clockwork
     * phys bearing drives its rotor to a target angle that went on turning through the stall, so the
     * rotor lurches round at several times its speed for a frame or two and settles back; judged as
     * jolts, each such hitch threw everyone off a steadily spinning rotor.
     */
    static final double STALL_QUIET_SECONDS = 0.3;
    /**
     * A frame's move must be this many times what its reported motion could carry it over the
     * frame's time span before the report counts as contradicted (see the class doc).
     */
    static final double POSE_SLACK = 3.0;
    /** The smallest move (blocks, radians) a frame's report is checked against. */
    static final double POSE_MIN_MOVE = 0.01;
    /** Below this (m/s at the hull's edge, rad/s) a ship's latest frame counts as at rest. */
    static final double REST_SPEED = 0.05;
    /** Frames of motion each ship keeps for crashes reported late ({@link State#recentClosing}). */
    static final int RECENT_FRAMES = 6;
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
        /** And the frame before that (deck jolts are confirmed against it: see {@link #joltAt}). */
        Vec3 prev2Position;
        Vec3 prev2Velocity;
        Vec3 prev2AngularVelocity;
        Quaterniond prev2Rotation;
        /** And the one before that: a deck change is judged against the two frames after it (see {@link #joltAt}). */
        Vec3 prev3Position;
        Vec3 prev3Velocity;
        Vec3 prev3AngularVelocity;
        Quaterniond prev3Rotation;
        /** Seconds the frame before the previous one spanned, and the tick it was first seen. */
        double prev2FrameSeconds = TICK_SECONDS;
        long prev2FrameTick;
        /**
         * The last {@link #RECENT_FRAMES} frames (the newest at {@code recentHead}): centre of mass,
         * velocity, spin and the tick each was first seen. For a crash between two ships that
         * reaches the game several frames late ({@link #recentClosing}).
         */
        final Vec3[] recentPosition = new Vec3[RECENT_FRAMES];
        final Vec3[] recentVelocity = new Vec3[RECENT_FRAMES];
        final Vec3[] recentAngularVelocity = new Vec3[RECENT_FRAMES];
        final long[] recentTick = new long[RECENT_FRAMES];
        int recentCount;
        int recentHead = -1;
        /** Seconds the previous frame spanned, and the tick it was first seen. */
        double prevFrameSeconds = TICK_SECONDS;
        long prevFrameTick;
        /** New frames since the ship was (re)found: 1 makes the previous frame real, 2 the one before. */
        int framesSeen;
        /** The frame (by the tick it was first seen) whose deck change has been judged (see {@link #joltMode}). */
        long judgedFrameTick = Long.MIN_VALUE;
        /**
         * The frame deck changes are judged from ({@link #joltAt}): the latest judged frame whose
         * change lasted. A frame whose change came straight back (a spike) doesn't become it, so
         * the frame after a spike is judged against the motion before the spike, not against the
         * spike itself: a rotor's bearing jumping to 4/3 of its spin for a frame and back is no
         * jolt either way. Kept through a stall's quiet time ({@link #deckQuiet}), so what the ship
         * does after it is judged against how it moved before it. Null until a frame is judged,
         * and not used once older than {@link #BASE_MAX_AGE_TICKS}.
         */
        Frame base;
        long baseTick;
        /** What {@link #base} becomes when the next frame arrives: riders are judged from the old one until then. */
        Frame nextBase;
        long nextBaseTick;
        /** The tick the frame before {@link #prev2FrameTick}'s was first seen. */
        long prev3FrameTick;
        /** Whether this tick brought a new physics frame. */
        boolean newFrame;
        /** No deck change is judged before this {@link System#nanoTime}: VS's physics is catching up after a stall ({@link #deckQuiet}). */
        long deckQuietUntilNanos = Long.MIN_VALUE;
        /** Seconds between the previous frame and the latest (at least one tick). */
        double frameSeconds = TICK_SECONDS;
        /** Game tick and {@link System#nanoTime} at which the latest frame was first seen. */
        long frameTick;
        long frameNanos;
        /**
         * VS's own report of the latest frame's motion: what tells a new frame from the same one,
         * where {@link #velocity} and {@link #angularVelocity} may have been taken from the pose.
         */
        Vec3 reportedVelocity;
        Vec3 reportedAngularVelocity;

        private State(long now, long nanos, Vec3 position, Vec3 velocity, Vec3 angularVelocity, Quaterniond rotation,
                      double mass) {
            this.firstSeen = now;
            this.lastSeen = now;
            this.frameTick = now;
            this.frameNanos = nanos;
            this.position = this.prevPosition = this.prev2Position = this.prev3Position = position;
            this.velocity = this.prevVelocity = this.prev2Velocity = this.prev3Velocity = velocity;
            this.angularVelocity = this.prevAngularVelocity = this.prev2AngularVelocity = this.prev3AngularVelocity = angularVelocity;
            this.rotation = rotation;
            this.prevRotation = new Quaterniond(rotation);
            this.prev2Rotation = new Quaterniond(rotation);
            this.prev3Rotation = new Quaterniond(rotation);
            this.prevFrameTick = now;
            this.prev2FrameTick = now;
            this.prev3FrameTick = now;
            this.mass = mass;
            this.reportedVelocity = velocity;
            this.reportedAngularVelocity = angularVelocity;
            remember(position, velocity, angularVelocity, now);
        }

        /** Adds a frame to the recent ones. */
        void remember(Vec3 position, Vec3 velocity, Vec3 angularVelocity, long tick) {
            recentHead = (recentHead + 1) % RECENT_FRAMES;
            recentPosition[recentHead] = position;
            recentVelocity[recentHead] = velocity;
            recentAngularVelocity[recentHead] = angularVelocity;
            recentTick[recentHead] = tick;
            recentCount = Math.min(recentCount + 1, RECENT_FRAMES);
        }

        /**
         * The fastest the two ships' material closed along {@code normal} (unit, from
         * {@code other} toward this ship) at {@code point}, m/s, over the frames both saw in the
         * last {@code windowTicks} ticks (each of this ship's frames against the other's frame of
         * the same tick, or the one before it). Two ships' crash can reach the game several
         * frames after they met, by when both have long bounced apart; the frames just before it
         * still show how fast they met. 0 when no frames pair up.
         */
        public double recentClosing(State other, Vec3 point, Vec3 normal, long now, int windowTicks) {
            double fastest = 0.0;
            for (int i = 0; i < recentCount; i++) {
                int a = Math.floorMod(recentHead - i, RECENT_FRAMES);
                long tick = recentTick[a];
                if (tick < now - windowTicks) break;
                int b = other.recentAtOrBefore(tick);
                if (b < 0) continue;
                Vec3 va = recentVelocity[a].add(recentAngularVelocity[a].cross(point.subtract(recentPosition[a])));
                Vec3 vb = other.recentVelocity[b].add(other.recentAngularVelocity[b].cross(point.subtract(other.recentPosition[b])));
                fastest = Math.max(fastest, -va.subtract(vb).dot(normal));
            }
            return fastest;
        }

        /**
         * The fastest this ship's material moved into a surface at {@code point}, along
         * {@code normal} (unit, out of the surface toward the ship), m/s, over its frames of the
         * last {@code windowTicks} ticks: how fast it met the surface, even when the crash reaches
         * the game a frame or two after the solver bounced it off. 0 when none moved into it.
         */
        public double recentInto(Vec3 point, Vec3 normal, long now, int windowTicks) {
            double fastest = 0.0;
            for (int i = 0; i < recentCount; i++) {
                int a = Math.floorMod(recentHead - i, RECENT_FRAMES);
                if (recentTick[a] < now - windowTicks) break;
                Vec3 v = recentVelocity[a].add(recentAngularVelocity[a].cross(point.subtract(recentPosition[a])));
                fastest = Math.max(fastest, -v.dot(normal));
            }
            return fastest;
        }

        /** The index of the newest recent frame first seen at or before {@code tick}, or -1. */
        private int recentAtOrBefore(long tick) {
            for (int i = 0; i < recentCount; i++) {
                int j = Math.floorMod(recentHead - i, RECENT_FRAMES);
                if (recentTick[j] <= tick) return j;
            }
            return -1;
        }

        public boolean inGrace(long now) {
            return now < graceUntil;
        }

        /**
         * Whether deck changes are left unjudged right now because VS's physics stalled and is
         * catching up ({@link #STALL_QUIET_SECONDS}). Collisions are unaffected: only what riders feel.
         */
        public boolean deckQuiet(long now) {
            return deckQuietUntilNanos != Long.MIN_VALUE && System.nanoTime() - deckQuietUntilNanos < 0;
        }

        public long graceLeft(long now) {
            return Math.max(0, graceUntil - now);
        }

        /** The latest frame's centre of mass, world space. */
        public Vec3 position() {
            return position;
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

        /** As {@link #prevVelocityAt}, for the frame before that. */
        public Vec3 prev2VelocityAt(Vec3 worldPoint) {
            Vec3 r = worldPoint.subtract(prev2Position);
            return prev2Velocity.add(prev2AngularVelocity.cross(r));
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

        /**
         * How much the ship's motion changed under a rider at {@code worldPoint} in the latest
         * frame, m/s: the previous frame's velocity of that piece of deck, turned with the ship
         * into the latest frame (as VS turns the riders it carries), less its velocity now.
         *
         * <p>Compared in the ship's own frame, a ship turning or spinning steadily about any fixed
         * axis changes nothing - its deck moves the same way under every rider from one frame to
         * the next, only turned - so riders VS carries round a spinning ship (a Clockwork rotor
         * at any speed) or through a steady turn are left alone, as VS leaves them. What does
         * change is a stop, a lurch, a spin speeding up or slowing ({@code alpha x r}), a turn
         * starting or ending.
         */
        public Vec3 deckChangeAt(Vec3 worldPoint) {
            Vector3d local = local(worldPoint);
            return toWorld(localVelocity(prevVelocity, prevAngularVelocity, prevRotation, local)
                    .sub(localVelocity(velocity, angularVelocity, rotation, local)));
        }

        /**
         * Which deck change is up for judging this tick, if any. A jolt is only real if it stays:
         * the deck of a rotor on a physics bearing (Clockwork) jitters for a frame and comes back,
         * and each such spike used to throw its riders. So the change a frame brought is judged
         * when the NEXT frame arrives ({@link JoltMode#CONFIRMED}: kept only as far as it still
         * stands then), or, when no frame follows for {@link #SETTLE_TICKS} ticks (a ship stopped
         * for good, a lagging server), on its own ({@link JoltMode#SETTLED}). Each frame's change
         * is judged once.
         */
        public JoltMode joltMode(long now) {
            if (newFrame && framesSeen >= 3 && prev2FrameTick != judgedFrameTick) return JoltMode.CONFIRMED;
            // No next frame to confirm against: a ship that came to rest stays in its last state, so
            // its last change is judged on its own. A ship still moving in its last frame hasn't
            // stopped - VS's physics has stalled - and its next frames will say what really happened.
            if (!newFrame && framesSeen >= 1 && now - frameTick >= SETTLE_TICKS && frameTick != judgedFrameTick
                    && atRest()) {
                return JoltMode.SETTLED;
            }
            return JoltMode.NONE;
        }

        /** Whether the latest frame has the ship still: no speed, no spin. */
        boolean atRest() {
            return velocity.length() < REST_SPEED && angularVelocity.length() < REST_SPEED;
        }

        /**
         * The acceleration (m/s^2, world) of the ship's material at {@code worldPoint}, from the
         * latest two physics frames: the centre of mass speeding up or turning, the spin speeding up
         * ({@code alpha x r}) and the centripetal pull of the spin ({@code w x (w x r)}) - what a
         * rider SEATED there feels, the seat holding it to the ship. A tick without a new frame
         * keeps the latest frame's value (VS's physics runs on its own clock); zero until the ship
         * has brought two frames.
         */
        public Vec3 accelerationAt(Vec3 worldPoint) {
            if (framesSeen < 1) {
                Frame only = new Frame(position, velocity, angularVelocity, rotation); // the spin's pull alone
                return ShipTracker.accelerationAt(only, only, frameSeconds, worldPoint);
            }
            return ShipTracker.accelerationAt(new Frame(prevPosition, prevVelocity, prevAngularVelocity, prevRotation),
                    new Frame(position, velocity, angularVelocity, rotation), frameSeconds, worldPoint);
        }

        /** The frame {@code mode} judged has been dealt with (see {@link #joltMode}). */
        public void markJudged(JoltMode mode) {
            if (mode == JoltMode.CONFIRMED) {
                judgedFrameTick = prev2FrameTick;
                // the judged frame becomes the base if its change lasted; a spike leaves it where it was
                Frame from = earlier();
                Frame judged = new Frame(prev2Position, prev2Velocity, prev2AngularVelocity, prev2Rotation);
                boolean lasted = lasted(from, judged, new Frame(prevPosition, prevVelocity, prevAngularVelocity, prevRotation),
                        new Frame(position, velocity, angularVelocity, rotation));
                nextBase = lasted ? judged : from;
                nextBaseTick = lasted ? prev2FrameTick : earlierTick();
            } else if (mode == JoltMode.SETTLED) {
                judgedFrameTick = frameTick;
                nextBase = new Frame(position, velocity, angularVelocity, rotation);
                nextBaseTick = frameTick;
            }
        }

        /**
         * As {@link #markJudged}, for a frame left unjudged ({@link #deckQuiet}): the base stays the
         * motion from before the stall, however long the catch-up takes.
         */
        public void skipJudged(JoltMode mode) {
            if (mode == JoltMode.CONFIRMED) judgedFrameTick = prev2FrameTick;
            else if (mode == JoltMode.SETTLED) judgedFrameTick = frameTick;
            if (mode != JoltMode.NONE) {
                nextBase = earlier();
                nextBaseTick = frameTick;
            }
        }

        /** The frame the frame up for judging ({@link JoltMode#CONFIRMED}) is judged from: the {@link #base}, or the one before it. */
        private Frame earlier() {
            if (base != null && frameTick - baseTick <= BASE_MAX_AGE_TICKS) return base;
            return new Frame(prev3Position, prev3Velocity, prev3AngularVelocity, prev3Rotation);
        }

        private long earlierTick() {
            return base != null && frameTick - baseTick <= BASE_MAX_AGE_TICKS ? baseTick : prev3FrameTick;
        }

        /** Seconds of the frame {@code mode} judges (what the grip allowance spans). */
        public double joltSeconds(JoltMode mode) {
            if (mode == JoltMode.CONFIRMED) return prev2FrameSeconds;
            // settled: the span of every frame not judged yet
            if (judgedFrameTick < prev2FrameTick) return prev2FrameSeconds + prevFrameSeconds + frameSeconds;
            if (judgedFrameTick < prevFrameTick) return prevFrameSeconds + frameSeconds;
            return frameSeconds;
        }

        /**
         * The frame a settled ship's change is judged from: the one before the first frame not yet
         * judged. A change still waiting for its two confirming frames when the ship came to rest
         * (a crash spread over two frames) is judged along with the rest.
         */
        private Frame settledFrom() {
            if (judgedFrameTick < prev2FrameTick) return new Frame(prev3Position, prev3Velocity, prev3AngularVelocity, prev3Rotation);
            if (judgedFrameTick < prevFrameTick) return new Frame(prev2Position, prev2Velocity, prev2AngularVelocity, prev2Rotation);
            return new Frame(prevPosition, prevVelocity, prevAngularVelocity, prevRotation);
        }

        /**
         * The deck change at a rider standing at {@code worldPoint} (m/s, world axes as now), in
         * the ship's own frame ({@link #deckChangeAt}): the change the judged frame brought, and
         * how much of it still stands (the same for {@link JoltMode#SETTLED}). A change is judged
         * two frames after it came, and stands only as far as it stood in BOTH of them: a Clockwork
         * phys bearing drives its rotor to a target angle, and as VS's physics steps fall two to one
         * tick and four to the next, the rotor's spin swings by a third either way and back within a
         * frame or two - a change that a single later frame could still show, and that threw everyone
         * standing on a steadily spinning rotor again and again. The change is measured from the
         * {@link #base}, the last frame whose own change lasted, so the frame after such a spike
         * isn't judged against the spike.
         */
        public Jolt joltAt(Vec3 worldPoint, JoltMode mode) {
            Frame now = new Frame(position, velocity, angularVelocity, rotation);
            Frame before = new Frame(prevPosition, prevVelocity, prevAngularVelocity, prevRotation);
            if (mode == JoltMode.CONFIRMED) {
                Frame earlier = earlier();
                Frame judged = new Frame(prev2Position, prev2Velocity, prev2AngularVelocity, prev2Rotation);
                Jolt next = joltBetween(earlier, judged, before, worldPoint);
                Jolt after = joltBetween(earlier, judged, now, worldPoint);
                return next.persisted() <= after.persisted() ? next : new Jolt(next.change(), after.standing());
            }
            Vec3 change = joltBetween(settledFrom(), now, now, worldPoint).change();
            return new Jolt(change, change);
        }

        /** A world point (now) in ship axes, relative to the centre of mass: glued to the deck, it is the same in every frame. */
        private Vector3d local(Vec3 worldPoint) {
            Vector3d r = new Vector3d(worldPoint.x - position.x, worldPoint.y - position.y, worldPoint.z - position.z);
            rotation.transformInverse(r);
            return r;
        }

        private Vec3 toWorld(Vector3d local) {
            rotation.transform(local);
            return new Vec3(local.x, local.y, local.z);
        }
    }

    /** A {@link State#base} older than this (ticks) isn't judged from: the frame before the judged one is. */
    static final int BASE_MAX_AGE_TICKS = 20;
    /** The share of a frame's change that must still stand in both frames after it for the change to have lasted. */
    static final double BASE_LASTED = 0.5;
    /** The lever (blocks) a spin change is weighed at against a velocity change in {@link #lasted}. */
    static final double BASE_RADIUS = 2.0;

    /**
     * Whether the change of the ship's motion from {@code from} to {@code judged} lasted: at least
     * {@link #BASE_LASTED} of it still stands in both {@code next} and {@code after}. Motion is
     * compared in the ship's own axes (velocity, and spin at {@link #BASE_RADIUS}), where a
     * steady turn or spin doesn't change it; a frame that changed nothing has lasted.
     */
    static boolean lasted(Frame from, Frame judged, Frame next, Frame after) {
        double[] base = bodyMotion(from);
        double[] change = minus(bodyMotion(judged), base);
        double size = dot(change, change);
        if (size < 1.0e-8) return true;
        double stoodNext = dot(minus(bodyMotion(next), base), change) / size;
        double stoodAfter = dot(minus(bodyMotion(after), base), change) / size;
        return Math.min(stoodNext, stoodAfter) >= BASE_LASTED;
    }

    private static double[] bodyMotion(Frame f) {
        Vector3d v = new Vector3d(f.velocity().x, f.velocity().y, f.velocity().z);
        Vector3d w = new Vector3d(f.angularVelocity().x, f.angularVelocity().y, f.angularVelocity().z);
        f.rotation().transformInverse(v);
        f.rotation().transformInverse(w);
        return new double[]{v.x, v.y, v.z, BASE_RADIUS * w.x, BASE_RADIUS * w.y, BASE_RADIUS * w.z};
    }

    private static double[] minus(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] - b[i];
        return out;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    /** Ticks without a new frame after which a frame's deck change is judged on its own ({@link State#joltMode}). */
    static final int SETTLE_TICKS = 3;

    /** Which deck change is judged this tick: see {@link State#joltMode}. */
    public enum JoltMode { NONE, CONFIRMED, SETTLED }

    /**
     * A deck change at a rider (m/s, world): what the judged frame brought, and what of it still
     * stands now. A spike that came back has {@code standing} near zero.
     */
    public record Jolt(Vec3 change, Vec3 standing) {
        /** The share of {@code change} that still stands (1 when all of it does, 0 when it came back). */
        public double persisted() {
            double length = change.length();
            return length > 1.0e-9 ? standing.dot(change) / (length * length) : 1.0;
        }
    }

    /** One physics frame of a ship: centre of mass (world), velocity (m/s), spin (rad/s), ship-to-world rotation. */
    public record Frame(Vec3 position, Vec3 velocity, Vec3 angularVelocity, Quaterniond rotation) {}

    /**
     * The deck change at a rider standing at {@code worldPoint} now (m/s, world axes as in
     * {@code now}), in the ship's own frame: {@code change} is what the deck under it lost from
     * {@code earlier} to {@code judged}, {@code standing} what it has lost from {@code earlier}
     * to {@code now}. The rider is glued to the deck, so it is the same piece of deck in every
     * frame: its point in ship axes, relative to the centre of mass, is taken from {@code now}.
     */
    public static Jolt joltBetween(Frame earlier, Frame judged, Frame now, Vec3 worldPoint) {
        Vector3d local = new Vector3d(worldPoint.x - now.position().x, worldPoint.y - now.position().y,
                worldPoint.z - now.position().z);
        now.rotation().transformInverse(local);
        Vector3d e = localVelocity(earlier.velocity(), earlier.angularVelocity(), earlier.rotation(), local);
        Vector3d j = localVelocity(judged.velocity(), judged.angularVelocity(), judged.rotation(), local);
        Vector3d n = localVelocity(now.velocity(), now.angularVelocity(), now.rotation(), local);
        Vector3d change = new Vector3d(e).sub(j);
        Vector3d standing = e.sub(n);
        now.rotation().transform(change);
        now.rotation().transform(standing);
        return new Jolt(new Vec3(change.x, change.y, change.z), new Vec3(standing.x, standing.y, standing.z));
    }

    /**
     * The acceleration (m/s^2, world) of a rigid body's material at {@code worldPoint} (where it
     * is in frame {@code now}), from two frames {@code seconds} apart: {@code a + alpha x r +
     * w x (w x r)}, with {@code a} and {@code alpha} the frames' differences in velocity and spin.
     * A body turning steadily at {@code w} about an axis through its centre gives {@code w^2 r}
     * toward the axis.
     */
    public static Vec3 accelerationAt(Frame before, Frame now, double seconds, Vec3 worldPoint) {
        double dt = Math.max(seconds, 1.0e-3);
        Vec3 r = worldPoint.subtract(now.position());
        Vec3 linear = now.velocity().subtract(before.velocity()).scale(1.0 / dt);
        Vec3 alpha = now.angularVelocity().subtract(before.angularVelocity()).scale(1.0 / dt);
        Vec3 w = now.angularVelocity();
        return linear.add(alpha.cross(r)).add(w.cross(w.cross(r)));
    }

    /**
     * The velocity of the material of a rigid body at {@code local} (a point in its axes,
     * relative to its centre of mass), in the body's axes: {@code rot^-1 (v + w x rot local)}.
     * In these terms a body turning steadily about any fixed axis doesn't change at all.
     */
    static Vector3d localVelocity(Vec3 v, Vec3 w, Quaterniond rotation, Vector3d local) {
        Vector3d r = new Vector3d(local);
        rotation.transform(r);
        Vector3d out = new Vector3d(w.y * r.z - w.z * r.y + v.x, w.z * r.x - w.x * r.z + v.y, w.x * r.y - w.y * r.x + v.z);
        rotation.transformInverse(out);
        return out;
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
        Vec3 reportedVelocity = velocity;
        Vec3 reportedOmega = omega;
        // the mass VS's physics moves (a scaled ship's is its blocks' times the cube of its scale): what every
        // push and two-body exchange FullStop sizes from this state answers to
        double mass = ship.physicalMass();
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
            state.prevPosition = state.prev2Position = state.prev3Position = position;
            state.prevVelocity = state.prev2Velocity = state.prev3Velocity = velocity;
            state.prevAngularVelocity = state.prev2AngularVelocity = state.prev3AngularVelocity = omega;
            state.prevRotation = new Quaterniond(rotation);
            state.prev2Rotation = new Quaterniond(rotation);
            state.prev3Rotation = new Quaterniond(rotation);
            state.prev2FrameTick = now;
            state.prev3FrameTick = now;
            state.base = state.nextBase = null;
            state.recentCount = 0;
            state.remember(position, velocity, omega, now);
            state.framesSeen = 0;
            state.prevFrameTick = now;
            state.newFrame = false;
            state.frameSeconds = TICK_SECONDS;
            state.frameTick = now;
            state.frameNanos = nanos;
        } else {
            boolean fresh = !position.equals(state.position) || !velocity.equals(state.reportedVelocity)
                    || !omega.equals(state.reportedAngularVelocity) || !rotation.equals(state.rotation);
            boolean jumped = false;
            String why = null;
            if (fresh) {
                double gapSeconds = (nanos - state.frameNanos) * 1.0e-9;
                if (gapSeconds >= STALL_SECONDS && state.framesSeen >= 1 && !state.atRest()) {
                    // VS's physics stalled while the ship moved and has just resumed
                    state.deckQuietUntilNanos = nanos + (long) (STALL_QUIET_SECONDS * 1.0e9);
                    if (net.camacraft.fullstop.FullStopConfig.SERVER.debugShipCollisionLog.get()) {
                        LOGGER.info("[FullStop] ship {}: no physics frame for {} s while moving; deck jolts ignored for {} s "
                                        + "while it catches up", ship.id(), String.format(java.util.Locale.ROOT, "%.2f", gapSeconds),
                                STALL_QUIET_SECONDS);
                    }
                }
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
                    // VS's report of the frame's motion, unless the frame's own move contradicts it
                    Vec3 poseOmega = checkedSpin(state.angularVelocity, state.rotation, omega, rotation, seconds);
                    Vec3 poseVelocity = checkedVelocity(state.velocity, state.position, velocity, position, seconds);
                    if ((poseOmega != omega || poseVelocity != velocity)
                            && net.camacraft.fullstop.FullStopConfig.SERVER.debugShipCollisionLog.get()) {
                        LOGGER.info("[FullStop] ship {}: VS reported {} for a frame in which it moved on as before; "
                                + "taking its move: {}", ship.id(), motion(velocity, omega), motion(poseVelocity, poseOmega));
                    }
                    omega = poseOmega;
                    velocity = poseVelocity;
                    // a burst of physics catching up spans more than the clocks say: how long the
                    // motion itself took, when there is enough of it to tell
                    seconds = Math.max(seconds, physicsSeconds(state.position, state.velocity, state.rotation,
                            state.angularVelocity, position, velocity, rotation, omega));
                }
                if (state.nextBase != null) {
                    state.base = state.nextBase;
                    state.baseTick = state.nextBaseTick;
                    state.nextBase = null;
                }
                state.prev3FrameTick = state.prev2FrameTick;
                state.prev3Position = state.prev2Position;
                state.prev3Velocity = state.prev2Velocity;
                state.prev3AngularVelocity = state.prev2AngularVelocity;
                state.prev3Rotation = state.prev2Rotation;
                state.prev2FrameSeconds = state.prevFrameSeconds;
                state.prev2FrameTick = state.prevFrameTick;
                state.prev2Position = state.prevPosition;
                state.prev2Velocity = state.prevVelocity;
                state.prev2AngularVelocity = state.prevAngularVelocity;
                state.prev2Rotation = state.prevRotation;
                state.prevFrameSeconds = state.frameSeconds;
                state.prevFrameTick = state.frameTick;
                state.prevPosition = state.position;
                state.prevVelocity = state.velocity;
                state.prevAngularVelocity = state.angularVelocity;
                state.prevRotation = state.rotation;
                state.frameSeconds = Math.max(TICK_SECONDS, seconds);
                state.frameTick = now;
                state.frameNanos = nanos;
                state.framesSeen++;
                state.remember(position, velocity, omega, now);
            }
            state.newFrame = fresh;
            if (!fresh) {
                // the same frame: keep its motion as checked
                velocity = state.velocity;
                omega = state.angularVelocity;
            }

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
        state.reportedVelocity = reportedVelocity;
        state.reportedAngularVelocity = reportedOmega;
        state.rotation = rotation;
        state.mass = mass;
        state.lastSeen = now;
        return state;
    }

    /**
     * A frame's spin (rad/s, world): {@code reported}, unless the frame's turn from
     * {@code rotationBefore} to {@code rotation} over {@code seconds} contradicts it (see the class
     * doc), when it is that turn over {@code seconds}.
     */
    static Vec3 checkedSpin(Vec3 spinBefore, Quaterniond rotationBefore, Vec3 reported, Quaterniond rotation, double seconds) {
        // the turn in world axes: before -> now
        Quaterniond turn = new Quaterniond(rotation).mul(new Quaterniond(rotationBefore).conjugate());
        if (turn.w < 0.0) turn.set(-turn.x, -turn.y, -turn.z, -turn.w);
        double angle = 2.0 * Math.acos(Math.min(1.0, turn.w));
        double sin = Math.sqrt(Math.max(0.0, 1.0 - turn.w * turn.w));
        if (angle < POSE_MIN_MOVE || sin < 1.0e-9 || seconds <= 0.0) return reported;
        Vec3 pose = new Vec3(turn.x / sin, turn.y / sin, turn.z / sin).scale(angle / seconds);
        return checked(spinBefore, reported, pose, angle, seconds);
    }

    /** As {@link #checkedSpin}, for the centre of mass's velocity (m/s). */
    static Vec3 checkedVelocity(Vec3 velocityBefore, Vec3 positionBefore, Vec3 reported, Vec3 position, double seconds) {
        Vec3 moved = position.subtract(positionBefore);
        double distance = moved.length();
        if (distance < POSE_MIN_MOVE || seconds <= 0.0) return reported;
        return checked(velocityBefore, reported, moved.scale(1.0 / seconds), distance, seconds);
    }

    /**
     * {@code pose} (the frame's move over its span) in place of {@code reported} when the move is
     * more than {@link #POSE_SLACK} times what {@code reported} could carry the ship in that span
     * and is the motion the ship had before: between half and twice as fast, within about 45
     * degrees of the same way. Anything else - a ship that stopped or turned hard, one set moving
     * or turned from rest - keeps VS's report.
     */
    private static Vec3 checked(Vec3 before, Vec3 reported, Vec3 pose, double moved, double seconds) {
        if (moved <= POSE_SLACK * reported.length() * seconds) return reported;
        double b = before.length();
        double p = pose.length();
        if (b < 1.0e-6 || p < 0.5 * b || p > 2.0 * b || pose.dot(before) < 0.7 * p * b) return reported;
        return pose;
    }

    private static String motion(Vec3 velocity, Vec3 omega) {
        return String.format(java.util.Locale.ROOT, "v (%.2f, %.2f, %.2f) m/s, spin (%.2f, %.2f, %.2f) rad/s",
                velocity.x, velocity.y, velocity.z, omega.x, omega.y, omega.z);
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

    /** The latest state recorded for ship {@code id} (this tick's or an earlier one), or null. */
    @Nullable
    public State latest(long id) {
        return states.get(id);
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
        states.long2ObjectEntrySet().removeIf(e -> {
            if (loaded.contains(e.getLongKey())) return false;
            ShipInertia.forget(e.getLongKey());
            return true;
        });
    }
}
