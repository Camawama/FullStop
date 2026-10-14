package net.camacraft.fullstop.server.physics;

import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.network.ShockTrainPacket;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Sonic booms, heard where and when the shock wave reaches each listener - and the continuous
 * shock train of a supersonic rotor.
 *
 * <p>Anything faster than sound - an entity, or any part of a Valkyrien Skies ship's hull (the tips of a fast rotor,
 * {@code shipSonicBooms}) - drags a shock cone behind it, and it is heard as one boom when that cone sweeps over the
 * listener. Every place the body passes while supersonic sends out a wave at the speed of sound; the boom is the first
 * of those waves to reach the listener. So it is heard when the shock gets there - a body passing 300 blocks off booms
 * almost a second later - and from where that wave came from (a positional sound there, not at the listener's own
 * ears). Each listener hears one boom per supersonic stretch: a rotor spinning past the speed of sound booms once for
 * everyone near it, not over and over. A stretch ends only once the body has stayed well under the barrier for a
 * second ({@link #SUBSONIC_SHARE}, {@link #SUBSONIC_TICKS}): speed jitter at the barrier, a rotor slowed for a moment
 * by what it hit, or a ship in FullStop's grace after a hard crash ({@link #keepAlive}) can't start new ones, while a
 * body swung back and forth past it booms on each swing. Its own riders and passengers hear nothing: the shock trails
 * behind them. Where there is no air to carry it nothing booms and no cone forms (Camafy's {@code Air.ambient}: VS
 * Genesis's space, Ad Astra's orbits, the Moon...), and a body flying out of the air ends its stretch there.</p>
 *
 * <p><b>The shock train.</b> A supersonic rotor doesn't boom over and over: each tip drags its shock round with it,
 * and a listener is swept by one shock per supersonic tip per turn, a train of booms at the blade-passing rate - at
 * a few a second the harsh buzzing roar of a supersonic propeller (the XF-84H "Thunderscreech"). While a source's
 * stretch is live and it pulses ({@link #train}: turns a second times supersonic tips, from {@code ShipSonicBooms}),
 * every listener within {@link #TRAIN_RANGE} is sent the train's rate and place ({@link ShockTrainPacket}: at first,
 * every {@link #TRAIN_RESEND_TICKS}, and when the rate or the tips' speed changes) and plays one crack per shock at that cadence; when the
 * stretch ends, or the listener moves out of range, it is told to stop. A body flying straight and fast pulses
 * nothing: it booms once as it passes. Riders hear a rotor's train (the hub of a rotor is swept by its tips' shocks
 * like anywhere else) but not the train of a ship that itself flies supersonic (they are inside its cone).</p>
 *
 * <p>As a body goes supersonic a ring of vapour puffs out around it, across its motion (a vapour cone).</p>
 */
public final class SonicBooms {

    /** The speed of sound (m/s). {@code -Dfullstop.test.speedOfSound} lowers it for tests: VS won't turn a hull that fast in a test world. */
    public static final double SOUND_MPS = Double.parseDouble(System.getProperty("fullstop.test.speedOfSound", "343.0"));
    private static final double SOUND_BPT = SOUND_MPS / 20.0;
    /** Under this share of the speed of sound for {@link #SUBSONIC_TICKS} ticks running, a supersonic stretch is over. */
    public static final double SUBSONIC_SHARE = 0.9;
    public static final int SUBSONIC_TICKS = 20;
    /** Over the barrier this many ticks running before a stretch begins: a one-tick spike booms nothing. */
    private static final int SUSTAIN_TICKS = 2;
    /**
     * A driven source ({@link Key#driven}: a ship, a Create contraption) must be over the barrier this many ticks AND
     * {@link #DRIVEN_SUSTAIN_NANOS} of real time before its stretch begins. What drives it can fall behind for a moment
     * and catch up faster than it really turns (the owner, 2026-10-12: a rotor on a phys bearing "will lag slightly and
     * when it catches up, it counts as a large increase in speed which can sometimes trigger a false sonic boom"); a
     * real one stays past the barrier. The waves of the wait are kept, so a real boom still comes from where and when
     * the barrier was crossed.
     */
    static final int DRIVEN_SUSTAIN_TICKS = 10;
    static final long DRIVEN_SUSTAIN_NANOS = 400_000_000L;
    /**
     * The server catching up after a lag runs its ticks back to back, and a rotor driven by the game's ticks (a Clockwork
     * phys bearing's target angle, a contraption) then turns faster in real time than it ever does: while the last
     * {@link #CLOCK_TICKS} ticks took less than {@link #CATCH_UP_SHARE} of their 50 ms each, and for
     * {@link #CATCH_UP_SETTLE_NANOS} after, a driven source's speed over the barrier counts for nothing.
     */
    private static final int CLOCK_TICKS = 20;
    private static final double CATCH_UP_SHARE = 0.8;
    private static final long CATCH_UP_SETTLE_NANOS = 500_000_000L;
    /**
     * A listener this far (blocks) from where the shock came from still hears it: the server's {@code sonicBoomRange}
     * (768 by default; it was 192, the owner: "the sound should be heard from even further away but low passed the
     * further away it is. It should still be loud from further away though"). The client plays it loud far off, deeper
     * and low-passed with the distance it travelled ({@code client.sound.SonicBoomSounds}).
     */
    public static double hearingRange() {
        return net.camacraft.fullstop.FullStopConfig.SERVER_SPEC.isLoaded()
                ? net.camacraft.fullstop.FullStopConfig.SERVER.sonicBoomRange.get() : 768.0;
    }

    /** How long (ticks) a wave sent out while supersonic is followed: long enough to cross the hearing range. */
    private static int waveTicks() {
        return (int) Math.ceil(hearingRange() / SOUND_BPT) + 2;
    }

    /** A source not heard from for this long (ticks) is forgotten, its stretch ended. */
    private static final int FORGET_TICKS = 40;
    private static final double VAPOUR_RANGE = 128.0;
    /** The shock train is heard this far (blocks) from the source (the cracks' own range in sounds.json). */
    public static final double TRAIN_RANGE = 192.0;
    /** A source's shock cone is drawn for listeners this close (blocks) to it. */
    public static final double CONE_RANGE = 320.0;
    /** A listener is told the train's rate and place again every so many ticks. */
    private static final int TRAIN_RESEND_TICKS = 10;
    /**
     * The train's gain (0..1). Its range is the sound's own: {@code attenuation_distance} 192 in {@code sounds.json},
     * the same as {@link #TRAIN_RANGE}, with vanilla's linear falloff (full at the source, half 96 blocks off). Not a
     * volume over 1: the client fades the loop in from silence, and the sound engine takes a sound's range from the
     * volume it STARTS at (times its attenuation distance), so a loop starting quiet with a 16-block sound was
     * inaudible past 16 blocks - and one starting at 0 was never played at all.
     */
    private static final float TRAIN_LOUDNESS = 1.0f;

    private SonicBooms() {
    }

    /**
     * A source: an entity or a ship, by id. {@code driven}: something else moves it (a ship's physics and what turns
     * it, a body FullStop doesn't simulate: {@code #fullstop:physics_blacklist}, Create's contraptions), so its speed
     * can leap for a moment when that catches up after a hitch ({@link #DRIVEN_SUSTAIN_TICKS}).
     */
    public record Key(boolean ship, long id, boolean driven) {
        /** A ship's key, or an entity's that isn't driven. */
        public Key(boolean ship, long id) {
            this(ship, id, ship);
        }

        public static Key entity(Entity entity) {
            return new Key(false, entity.getId(),
                    entity.getType().is(net.camacraft.fullstop.common.physics.rules.FullStopTags.PHYSICS_BLACKLIST));
        }

        public static Key ship(long shipId) {
            return new Key(true, shipId, true);
        }
    }

    private record Wave(long tick, Vec3 from) {}

    /** One supersonic stretch of a source: the waves it sent out, and who has heard it. */
    private static final class Stretch {
        final ArrayDeque<Wave> waves = new ArrayDeque<>();
        final Set<UUID> heard = new HashSet<>();
        Predicate<ServerPlayer> aboard = p -> false;
    }

    /** The shock train a source makes while its stretch is live: its rate (pulses a second, 0: none) and where it is. */
    private static final class Train {
        double rate;
        /** The fastest tip's speed over the speed of sound. */
        double mach = 1.0;
        Vec3 at = Vec3.ZERO;
        boolean ridersHear;
        /**
         * The ring its supersonic tips sweep, when known ({@link #train(ServerLevel, Key, Vec3, double, double, boolean, Vec3, Vec3, double, double)}):
         * its centre on the axis, the axis (unit), and the radii between which the blades are past the barrier. Each
         * listener hears the train from the point of that ring nearest them; null: from {@code at}.
         */
        @Nullable
        Vec3 ringCentre;
        @Nullable
        Vec3 ringAxis;
        double ringInner;
        double ringOuter;

        /** Where {@code listener} hears this train from: the nearest point of the tips' ring, or {@code at}. */
        Vec3 heardFrom(Vec3 ears) {
            if (ringCentre == null || ringAxis == null) return at;
            Vec3 d = ears.subtract(ringCentre);
            Vec3 radial = d.subtract(ringAxis.scale(d.dot(ringAxis)));
            double r = radial.length();
            if (r < 1.0E-6) return ringCentre;
            double onRing = Math.max(ringInner, Math.min(ringOuter, r));
            return ringCentre.add(radial.scale(onRing / r));
        }
    }

    private static final class Source {
        int overTicks;
        int underTicks;
        /** When (System.nanoTime) its current run over the barrier began. */
        long overSinceNanos;
        /** The waves of a run over the barrier whose stretch hasn't begun yet (a driven source's wait). */
        final ArrayDeque<Wave> pending = new ArrayDeque<>();
        long seen;
        /** Stretches begun since the source was first seen (for the tests). */
        int stretches;
        @Nullable
        Stretch live;
        final List<Stretch> ending = new ArrayList<>(1);
        @Nullable
        Train train;
        /** Who is playing the train, and the tick each was last told about it. */
        final Map<UUID, Long> listening = new HashMap<>();
        /** The rate and Mach number the listeners were last told. */
        double sentRate = -1.0;
        double sentMach = -1.0;
        /** Its fastest point's speed over the speed of sound, and where that point was (world), as last observed. */
        double mach;
        Vec3 lastAt = Vec3.ZERO;
        /** Its supersonic tips in its own frame (a ship's shipyard, a contraption's local space); empty: the whole body. */
        List<Vec3> coneTips = List.of();
        /** Who is drawing its shock cone, and the tick each was last told; the Mach number they were told. */
        final Map<UUID, Long> coneWatching = new HashMap<>();
        double sentConeMach = -1.0;
    }

    private static final Map<ServerLevel, Map<Key, Source>> SOURCES = new WeakHashMap<>();

    /**
     * One tick of a possible source: where its fastest point is (world), how fast that point moves (m/s), and who rides
     * it (they hear nothing). Returns whether the source is being followed - supersonic, or its last stretch's waves
     * still on their way - so a caller can skip it cheaply otherwise.
     */
    public static boolean observe(ServerLevel level, Key key, Vec3 at, Vec3 velocityMps, Predicate<ServerPlayer> aboard) {
        double speed = velocityMps.length();
        Map<Key, Source> sources = SOURCES.get(level);
        Source source = sources == null ? null : sources.get(key);
        if (source == null) {
            if (!(speed >= SOUND_MPS) || !hasAir(level, at)) return false;
            source = new Source();
            SOURCES.computeIfAbsent(level, l -> new HashMap<>()).put(key, source);
        }
        long now = level.getGameTime();
        long nanos = System.nanoTime();
        source.seen = now;
        source.mach = speed / SOUND_MPS;
        source.lastAt = at;
        boolean wasLive = source.live != null;
        if (speed >= SOUND_MPS) {
            if (key.driven() && !wasLive && catchingUp(level, nanos)) {
                // a driven rotor sped up by the server's catch-up: its run over the barrier starts again afterwards
                source.overTicks = 0;
                source.pending.clear();
            } else {
                if (source.overTicks == 0) source.overSinceNanos = nanos;
                source.overTicks++;
                if (!wasLive) {
                    source.pending.addLast(new Wave(now, at));
                    if (source.pending.size() > MAX_PENDING) source.pending.removeFirst();
                }
            }
            source.underTicks = 0;
        } else if (speed < SUBSONIC_SHARE * SOUND_MPS) {
            source.underTicks++;
            source.overTicks = 0;
            source.pending.clear();
        }
        boolean sustained = key.driven()
                ? source.overTicks >= DRIVEN_SUSTAIN_TICKS && nanos - source.overSinceNanos >= DRIVEN_SUSTAIN_NANOS
                : source.overTicks >= SUSTAIN_TICKS;
        if (!wasLive && sustained && hasAir(level, at)) {
            source.live = new Stretch();
            // the run's waves so far, this tick's included: the boom comes from where the barrier was crossed
            source.live.waves.addAll(source.pending);
            source.pending.clear();
            source.stretches++;
            vapourCone(level, at, velocityMps);
        }
        // out of the air (a ship climbing into space): the stretch ends there, its waves already on their way still arrive
        if (source.live != null && !hasAir(level, at)) {
            source.ending.add(source.live);
            source.live = null;
        }
        Stretch live = source.live;
        if (live != null) {
            live.aboard = aboard;
            if (wasLive && speed >= SOUND_MPS) live.waves.addLast(new Wave(now, at));
            if (source.underTicks >= SUBSONIC_TICKS) {
                source.ending.add(live);
                source.live = null;
            }
        }
        return true;
    }

    /**
     * A source that can't be measured this tick (a ship in FullStop's grace after a jump, or whose blocks aren't known)
     * is still there: its stretch goes on, unheard, rather than ending and booming again when it can be measured.
     */
    public static void keepAlive(ServerLevel level, Key key) {
        Map<Key, Source> sources = SOURCES.get(level);
        Source source = sources == null ? null : sources.get(key);
        if (source == null) return;
        source.seen = level.getGameTime();
        if (source.live == null) {
            // unmeasured, it can't be sustaining a run over the barrier either: one begins again once it can be measured
            source.overTicks = 0;
            source.pending.clear();
        }
    }

    /** The most waves a run over the barrier keeps while its stretch waits to begin. */
    private static final int MAX_PENDING = 200;

    /** The server tick each slot of {@link #CLOCK_NANOS} was taken at, and when (System.nanoTime). */
    private static final int[] CLOCK_TICK = new int[CLOCK_TICKS + 1];
    private static final long[] CLOCK_NANOS = new long[CLOCK_TICKS + 1];
    private static int clockLast = Integer.MIN_VALUE;
    private static long catchUpUntilNanos = Long.MIN_VALUE;

    /**
     * Whether the server is catching up after a lag ({@link #CATCH_UP_SHARE}): its last {@link #CLOCK_TICKS} ticks ran
     * well faster than real time, or did so in the last {@link #CATCH_UP_SETTLE_NANOS}. Server thread.
     */
    static boolean catchingUp(ServerLevel level, long nanos) {
        // the game-test server runs every tick back to back by design: never "catching up" there
        if (level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return false;
        int tick = level.getServer().getTickCount();
        if (tick != clockLast) {
            int slot = Math.floorMod(tick, CLOCK_TICK.length);
            CLOCK_TICK[slot] = tick;
            CLOCK_NANOS[slot] = nanos;
            int oldSlot = Math.floorMod(tick - CLOCK_TICKS, CLOCK_TICK.length);
            if (clockLast != Integer.MIN_VALUE && CLOCK_TICK[oldSlot] == tick - CLOCK_TICKS
                    && nanos - CLOCK_NANOS[oldSlot] < (long) (CLOCK_TICKS * 50_000_000L * CATCH_UP_SHARE)) {
                catchUpUntilNanos = nanos + CATCH_UP_SETTLE_NANOS;
            }
            clockLast = tick;
        }
        return catchUpUntilNanos != Long.MIN_VALUE && nanos - catchUpUntilNanos < 0;
    }

    /** Whether the source is in a supersonic stretch right now. */
    public static boolean isLive(ServerLevel level, Key key) {
        Map<Key, Source> sources = SOURCES.get(level);
        Source source = sources == null ? null : sources.get(key);
        return source != null && source.live != null;
    }

    /** The shock train's rate the source last asked for (pulses a second; 0 for none or one never seen); for the tests. */
    public static double trainRate(ServerLevel level, Key key) {
        Map<Key, Source> sources = SOURCES.get(level);
        Source source = sources == null ? null : sources.get(key);
        return source == null || source.train == null ? 0.0 : source.train.rate;
    }

    /** How many supersonic stretches the source has begun (0 for one never seen); for the tests. */
    public static int stretchesStarted(ServerLevel level, Key key) {
        Map<Key, Source> sources = SOURCES.get(level);
        Source source = sources == null ? null : sources.get(key);
        return source == null ? 0 : source.stretches;
    }

    /**
     * The shock train a source makes this tick (see the class notes): {@code pulsesPerSecond} shocks sweep a listener
     * each second (0: none), from {@code at} (world). Only heard while the source's stretch is live.
     *
     * @param tipSpeed   how fast the fastest tip moves (m/s): the cracks' pitch and bite grow past the barrier
     * @param ridersHear whether the listeners riding the source hear it too (a rotor's hub: yes; a ship flying
     *                   supersonic as a whole: no, they are inside its cone)
     */
    public static void train(ServerLevel level, Key key, Vec3 at, double pulsesPerSecond, double tipSpeed, boolean ridersHear) {
        Map<Key, Source> sources = SOURCES.get(level);
        Source source = sources == null ? null : sources.get(key);
        if (source == null) return;
        if (source.train == null) source.train = new Train();
        source.train.rate = Math.max(0.0, pulsesPerSecond);
        source.train.mach = Math.max(0.0, tipSpeed / SOUND_MPS);
        source.train.at = at;
        source.train.ridersHear = ridersHear;
        source.train.ringCentre = null;
        source.train.ringAxis = null;
    }

    /**
     * As {@link #train(ServerLevel, Key, Vec3, double, double, boolean)} for a rotor whose sweep is known: its supersonic
     * blades sweep the ring about {@code axis} (unit) through {@code ringCentre}, from {@code inner} to {@code outer}
     * blocks out, and each listener hears the shocks from the point of that ring nearest them (a listener by the tips of a
     * long arm hears them there, loud, not from its far-off hub). {@code at} (the hub) still decides who is near it.
     */
    public static void train(ServerLevel level, Key key, Vec3 at, double pulsesPerSecond, double tipSpeed, boolean ridersHear,
                             Vec3 ringCentre, Vec3 axis, double inner, double outer) {
        train(level, key, at, pulsesPerSecond, tipSpeed, ridersHear);
        Map<Key, Source> sources = SOURCES.get(level);
        Source source = sources == null ? null : sources.get(key);
        if (source == null || source.train == null || !(axis.lengthSqr() > 1.0E-12) || !(outer > 0.0)) return;
        source.train.ringCentre = ringCentre;
        source.train.ringAxis = axis.normalize();
        source.train.ringInner = Math.max(0.0, Math.min(inner, outer));
        source.train.ringOuter = outer;
    }

    /**
     * The supersonic tips of a source's shock cone this tick, in its own frame ({@link MachConePacket}): a ship's tips in
     * its shipyard, a contraption's in its local space; empty for a body flying supersonic as a whole.
     */
    public static void coneTips(ServerLevel level, Key key, List<Vec3> localTips) {
        Map<Key, Source> sources = SOURCES.get(level);
        Source source = sources == null ? null : sources.get(key);
        if (source != null) source.coneTips = localTips;
    }

    /** No air, no sound (Camafy's {@code Air.ambient}; with an older Camafy, no gravity: Genesis's space, an orbit). */
    private static boolean hasAir(ServerLevel level, Vec3 at) {
        return net.camacraft.fullstop.common.compat.AirCompat.hasAir(level, at);
    }

    /** After every source has been observed this tick: whom the shock waves reach now, and who hears the trains. */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        // every tick, so the clock knows when the server runs its ticks back to back
        catchingUp(level, System.nanoTime());
        Map<Key, Source> sources = SOURCES.get(level);
        if (sources == null || sources.isEmpty()) return;
        long now = level.getGameTime();
        Iterator<Map.Entry<Key, Source>> it = sources.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Source> entry = it.next();
            Source source = entry.getValue();
            if (source.live != null && now - source.seen > 2) {
                // gone (unloaded, removed): its last waves still travel
                source.ending.add(source.live);
                source.live = null;
            }
            if (source.live != null) {
                arrive(level, source.live, now);
                expire(source.live, now);
            }
            source.ending.removeIf(stretch -> {
                arrive(level, stretch, now);
                expire(stretch, now);
                return stretch.waves.isEmpty();
            });
            serveTrain(level, entry.getKey(), source, now);
            serveCone(level, entry.getKey(), source, now);
            if (source.live == null && source.ending.isEmpty() && now - source.seen > FORGET_TICKS) {
                stopTrain(entry.getKey(), source, level);
                stopCone(entry.getKey(), source, level);
                it.remove();
            }
        }
    }

    private static void expire(Stretch stretch, long now) {
        int waveTicks = waveTicks();
        while (!stretch.waves.isEmpty() && now - stretch.waves.peekFirst().tick() > waveTicks) stretch.waves.removeFirst();
    }

    /**
     * Booms for every listener the stretch's shock has reached by now and who hasn't heard it: the first of its waves
     * to get there (within hearing range) is the boom, from where that wave came from.
     */
    private static void arrive(ServerLevel level, Stretch stretch, long now) {
        if (stretch.waves.isEmpty()) return;
        double range = hearingRange();
        for (ServerPlayer listener : level.players()) {
            // spectators hear booms too (they watch; only the advancement is for players in the game)
            if (stretch.heard.contains(listener.getUUID()) || stretch.aboard.test(listener)) continue;
            Vec3 ear = listener.getEyePosition();
            Wave first = null;
            double firstArrival = Double.POSITIVE_INFINITY, firstDistance = 0.0;
            for (Wave wave : stretch.waves) {
                double distance = wave.from().distanceTo(ear);
                if (distance > range) continue;
                double arrival = wave.tick() + distance / SOUND_BPT;
                if (arrival < firstArrival) {
                    firstArrival = arrival;
                    first = wave;
                    firstDistance = distance;
                }
            }
            if (first == null || firstArrival > now) continue;
            stretch.heard.add(listener.getUUID());
            boom(level, listener, first.from(), firstDistance, range);
        }
    }

    /**
     * Who plays the source's train this tick: everyone within range while the stretch is live and it pulses (riders
     * only when the source says so), told at first, again every so often and when the rate changes; everyone else
     * who was playing it is told to stop.
     */
    private static void serveTrain(ServerLevel level, Key key, Source source, long now) {
        Train train = source.train;
        Stretch live = source.live;
        boolean on = train != null && live != null && train.rate > 0.0;
        if (!on && source.listening.isEmpty()) return;
        boolean rateChanged = on && (Math.abs(train.rate - source.sentRate) > 0.05 * Math.max(source.sentRate, 0.1)
                || Math.abs(train.mach - source.sentMach) > 0.05 * Math.max(source.sentMach, 0.1));
        for (ServerPlayer listener : level.players()) {
            UUID id = listener.getUUID();
            Long last = source.listening.get(id);
            // where this listener hears it from: the nearest point of the tips' ring when known, else the source's place
            Vec3 from = on ? train.heardFrom(listener.getEyePosition()) : Vec3.ZERO;
            boolean should = on && listener.getEyePosition().distanceToSqr(from) <= TRAIN_RANGE * TRAIN_RANGE
                    && (train.ridersHear || !live.aboard.test(listener));
            if (on && !listener.isSpectator() && listener.getEyePosition().distanceToSqr(train.at)
                    <= net.camacraft.fullstop.advancement.FullStopTriggers.THUNDERSCREECH_RANGE_SQ) {
                net.camacraft.fullstop.advancement.FullStopTriggers.trainNear(listener, now); // "Thunderscreech"
            }
            if (should) {
                if (last == null || rateChanged || now - last >= TRAIN_RESEND_TICKS) {
                    PacketHandler.sendToPlayer(listener, new ShockTrainPacket(key.id(), key.ship(), true, (float) train.rate,
                            from.x, from.y, from.z, TRAIN_LOUDNESS, (float) train.mach));
                    source.listening.put(id, now);
                }
            } else if (last != null) {
                source.listening.remove(id);
                PacketHandler.sendToPlayer(listener, new ShockTrainPacket(key.id(), key.ship(), false, 0.0f, 0.0, 0.0, 0.0, 0.0f, 0.0f));
            }
        }
        if (on) {
            source.sentRate = train.rate;
            source.sentMach = train.mach;
        }
        // listeners who left the server
        source.listening.keySet().removeIf(id -> level.getServer().getPlayerList().getPlayer(id) == null);
    }

    private static void stopTrain(Key key, Source source, ServerLevel level) {
        for (UUID id : source.listening.keySet()) {
            ServerPlayer listener = level.getServer().getPlayerList().getPlayer(id);
            if (listener != null) {
                PacketHandler.sendToPlayer(listener, new ShockTrainPacket(key.id(), key.ship(), false, 0.0f, 0.0, 0.0, 0.0, 0.0f, 0.0f));
            }
        }
        source.listening.clear();
    }

    private static void boom(ServerLevel level, ServerPlayer listener, Vec3 from, double distance, double range) {
        // a little deeper the farther it travelled (the client also low-passes it by the distance)
        float far = (float) Math.min(1.0, distance / range);
        float crack = (0.72f + (level.random.nextFloat() - level.random.nextFloat()) * 0.06f) * (1.0f - 0.15f * far);
        PacketHandler.sendToPlayer(listener, new net.camacraft.fullstop.common.network.SonicBoomPacket(from.x, from.y, from.z,
                (float) distance, (float) range, crack, level.random.nextLong()));
        if (!listener.isSpectator()) net.camacraft.fullstop.advancement.FullStopTriggers.BOOM.trigger(listener);
    }

    /**
     * Who draws the source's shock cone this tick: every player within {@link #CONE_RANGE} of it while its stretch is
     * live (riders too: it is outside them), told at first, every ten ticks and when its Mach number changes; the rest
     * who were drawing it are told to stop.
     */
    private static void serveCone(ServerLevel level, Key key, Source source, long now) {
        boolean on = source.live != null && source.mach >= 1.0;
        if (!on && source.coneWatching.isEmpty()) return;
        boolean changed = on && Math.abs(source.mach - source.sentConeMach) > 0.05 * Math.max(source.sentConeMach, 0.1);
        for (ServerPlayer listener : level.players()) {
            UUID id = listener.getUUID();
            Long last = source.coneWatching.get(id);
            boolean should = on && listener.getEyePosition().distanceToSqr(source.lastAt) <= CONE_RANGE * CONE_RANGE;
            if (should) {
                if (last == null || changed || now - last >= TRAIN_RESEND_TICKS) {
                    PacketHandler.sendToPlayer(listener, new net.camacraft.fullstop.common.network.MachConePacket(key.id(), key.ship(), true,
                            (float) source.mach, source.coneTips));
                    source.coneWatching.put(id, now);
                }
            } else if (last != null) {
                source.coneWatching.remove(id);
                PacketHandler.sendToPlayer(listener, new net.camacraft.fullstop.common.network.MachConePacket(key.id(), key.ship(), false, 0.0f, List.of()));
            }
        }
        if (on) source.sentConeMach = source.mach;
        source.coneWatching.keySet().removeIf(id -> level.getServer().getPlayerList().getPlayer(id) == null);
    }

    private static void stopCone(Key key, Source source, ServerLevel level) {
        for (UUID id : source.coneWatching.keySet()) {
            ServerPlayer listener = level.getServer().getPlayerList().getPlayer(id);
            if (listener != null) {
                PacketHandler.sendToPlayer(listener, new net.camacraft.fullstop.common.network.MachConePacket(key.id(), key.ship(), false, 0.0f, List.of()));
            }
        }
        source.coneWatching.clear();
    }

    /**
     * The tips a shock cone is drawn at: of a body's {@code locals} (its own frame) with their world
     * {@code pointsWorldVelocity} ({@code {world point, velocity m/s}}, same order), those moving at {@code threshold}
     * or faster, fastest first, each at least three blocks from the ones already taken; at most
     * {@link net.camacraft.fullstop.common.network.MachConePacket#MAX_TIPS}.
     */
    public static List<Vec3> coneTipsOf(List<Vec3> locals, List<Vec3[]> pointsWorldVelocity, double threshold) {
        List<Integer> fast = new ArrayList<>();
        for (int i = 0; i < Math.min(locals.size(), pointsWorldVelocity.size()); i++) {
            if (pointsWorldVelocity.get(i)[1].lengthSqr() >= threshold * threshold) fast.add(i);
        }
        fast.sort((a, b) -> Double.compare(pointsWorldVelocity.get(b)[1].lengthSqr(), pointsWorldVelocity.get(a)[1].lengthSqr()));
        List<Vec3> taken = new ArrayList<>();
        List<Vec3> takenWorld = new ArrayList<>();
        for (int i : fast) {
            Vec3 world = pointsWorldVelocity.get(i)[0];
            boolean near = false;
            for (Vec3 w : takenWorld) {
                if (w.distanceToSqr(world) < 9.0) {
                    near = true;
                    break;
                }
            }
            if (near) continue;
            taken.add(locals.get(i));
            takenWorld.add(world);
            if (taken.size() >= net.camacraft.fullstop.common.network.MachConePacket.MAX_TIPS) break;
        }
        return taken;
    }

    /** Two supersonic tips within this angle of each other round the spin axis are one tip. */
    private static final double TIP_SPREAD = Math.toRadians(45.0);

    /**
     * How many separate tips of a spinning body are past {@code threshold} (m/s): of its {@code points} (each
     * {@code {world point, velocity m/s}}), those moving that fast, grouped by their direction from {@code centre}
     * round the spin axis (within {@link #TIP_SPREAD}: one tip). 0 when it hardly spins: then its speed is its travel,
     * and there is no train. Used for ships ({@code ShipSonicBooms}) and Create's contraptions.
     */
    public static int supersonicTips(List<Vec3[]> points, Vec3 centre, Vec3 angularVelocity, double threshold) {
        double spin = angularVelocity.length();
        if (spin < 1.0e-3) return 0;
        Vec3 axis = angularVelocity.scale(1.0 / spin);
        Vec3 e1 = (Math.abs(axis.y) < 0.9 ? axis.cross(new Vec3(0.0, 1.0, 0.0)) : axis.cross(new Vec3(1.0, 0.0, 0.0))).normalize();
        Vec3 e2 = axis.cross(e1);
        List<Double> angles = new ArrayList<>();
        for (Vec3[] point : points) {
            if (point[1].lengthSqr() < threshold * threshold) continue;
            Vec3 r = point[0].subtract(centre);
            angles.add(Math.atan2(r.dot(e2), r.dot(e1)));
        }
        if (angles.isEmpty()) return 0;
        if (angles.size() == 1) return 1;
        angles.sort(null);
        int gaps = 0;
        for (int i = 1; i < angles.size(); i++) {
            if (angles.get(i) - angles.get(i - 1) > TIP_SPREAD) gaps++;
        }
        if (angles.get(0) + 2.0 * Math.PI - angles.get(angles.size() - 1) > TIP_SPREAD) gaps++;
        return Math.max(1, gaps);
    }

    /**
     * The outermost block corners of a body built of blocks, in its own frame: for each of 26 directions, the corner of
     * its blocks reaching farthest that way. The fastest point of a turning body is one of these, or next to one. Fed
     * every block ({@link #add}), then read ({@link #points}). Used for ships and Create's contraptions.
     */
    public static final class Outermost {
        private static final double[][] DIRS = directions();
        private final double[] reach = new double[26];
        private final Vec3[] corner = new Vec3[26];

        public Outermost() {
            java.util.Arrays.fill(reach, Double.NEGATIVE_INFINITY);
        }

        private static double[][] directions() {
            double[][] dirs = new double[26][];
            int n = 0;
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        dirs[n++] = new double[]{x, y, z};
                    }
                }
            }
            return dirs;
        }

        /** One block of the body, by its cell. */
        public void add(int x, int y, int z) {
            for (int i = 0; i < 26; i++) {
                double[] d = DIRS[i];
                double cx = x + (d[0] > 0 ? 1.0 : 0.0), cy = y + (d[1] > 0 ? 1.0 : 0.0), cz = z + (d[2] > 0 ? 1.0 : 0.0);
                double r = cx * d[0] + cy * d[1] + cz * d[2];
                if (r > reach[i]) {
                    reach[i] = r;
                    corner[i] = new Vec3(cx, cy, cz);
                }
            }
        }

        /** The outermost corners found, each once. */
        public List<Vec3> points() {
            List<Vec3> points = new ArrayList<>(26);
            for (Vec3 c : corner) {
                if (c != null && !points.contains(c)) points.add(c);
            }
            return points;
        }
    }

    /** A ring of vapour puffing out across the body's motion where it went supersonic. */
    private static void vapourCone(ServerLevel level, Vec3 at, Vec3 velocity) {
        Vec3 along = velocity.normalize();
        Vec3 side = Math.abs(along.y) < 0.9 ? along.cross(new Vec3(0.0, 1.0, 0.0)).normalize() : along.cross(new Vec3(1.0, 0.0, 0.0)).normalize();
        Vec3 other = along.cross(side);
        int puffs = 24;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(at) > VAPOUR_RANGE * VAPOUR_RANGE) continue;
            for (int i = 0; i < puffs; i++) {
                double angle = 2.0 * Math.PI * i / puffs;
                Vec3 out = side.scale(Math.cos(angle)).add(other.scale(Math.sin(angle)));
                Vec3 p = at.add(out.scale(0.5));
                level.sendParticles(player, ParticleTypes.CLOUD, true, p.x, p.y, p.z, 0, out.x, out.y, out.z, 0.35);
            }
        }
    }
}
