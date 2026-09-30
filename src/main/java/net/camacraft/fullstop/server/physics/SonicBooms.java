package net.camacraft.fullstop.server.physics;

import net.camacraft.camafy.compat.gravity.Gravity;
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
 * Sonic booms, heard where and when the shock wave reaches each listener.
 *
 * <p>Anything faster than sound - an entity, or any part of a Valkyrien Skies ship's hull (the tips of a fast rotor,
 * {@code shipSonicBooms}) - drags a shock cone behind it, and it is heard as one boom when that cone sweeps over the
 * listener. Every place the body passes while supersonic sends out a wave at the speed of sound; the boom is the first
 * of those waves to reach the listener. So it is heard when the shock gets there - a body passing 300 blocks off booms
 * almost a second later - and from where that wave came from (a positional sound there, not at the listener's own
 * ears). Each listener hears one boom per supersonic stretch: a rotor spinning past the speed of sound booms once for
 * everyone near it, not over and over. A stretch ends when the body stays under the barrier for a moment
 * ({@link #SUBSONIC_TICKS}), so speed jitter at the barrier can't start new ones, while a body swung back and forth past
 * it booms on each swing. Its own riders and passengers hear nothing: the shock trails behind them. Where there is no
 * air to carry it (VS Genesis's space, an orbit: no gravity there) nothing booms.</p>
 *
 * <p>As a body goes supersonic a ring of vapour puffs out around it, across its motion (a vapour cone).</p>
 */
public final class SonicBooms {

    /** The speed of sound (m/s). {@code -Dfullstop.test.speedOfSound} lowers it for tests: VS won't turn a hull that fast in a test world. */
    public static final double SOUND_MPS = Double.parseDouble(System.getProperty("fullstop.test.speedOfSound", "343.0"));
    private static final double SOUND_BPT = SOUND_MPS / 20.0;
    /** Under this share of the speed of sound for {@link #SUBSONIC_TICKS} ticks running, a supersonic stretch is over. */
    private static final double SUBSONIC_SHARE = 0.95;
    private static final int SUBSONIC_TICKS = 4;
    /** Over the barrier this many ticks running before a stretch begins: a one-tick spike booms nothing. */
    private static final int SUSTAIN_TICKS = 2;
    /** A listener this far (blocks) from where the shock came from still hears it. */
    public static final double HEARING_RANGE = 192.0;
    /** How long (ticks) a wave sent out while supersonic is followed: long enough to cross the hearing range. */
    private static final int WAVE_TICKS = (int) Math.ceil(HEARING_RANGE / SOUND_BPT) + 2;
    /** A source not heard from for this long (ticks) is forgotten, its stretch ended. */
    private static final int FORGET_TICKS = 40;
    /** The boom carries twice the hearing range (vanilla's linear falloff): full near its source, half at the range's edge. */
    private static final float VOLUME = (float) (2.0 * HEARING_RANGE / 16.0);
    private static final double VAPOUR_RANGE = 128.0;

    private SonicBooms() {
    }

    /** A source: an entity or a ship, by id. */
    public record Key(boolean ship, long id) {
        public static Key entity(Entity entity) {
            return new Key(false, entity.getId());
        }

        public static Key ship(long shipId) {
            return new Key(true, shipId);
        }
    }

    private record Wave(long tick, Vec3 from) {}

    /** One supersonic stretch of a source: the waves it sent out, and who has heard it. */
    private static final class Stretch {
        final ArrayDeque<Wave> waves = new ArrayDeque<>();
        final Set<UUID> heard = new HashSet<>();
        Predicate<ServerPlayer> aboard = p -> false;
    }

    private static final class Source {
        int overTicks;
        int underTicks;
        long seen;
        @Nullable
        Stretch live;
        final List<Stretch> ending = new ArrayList<>(1);
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
        source.seen = now;
        if (speed >= SOUND_MPS) {
            source.overTicks++;
            source.underTicks = 0;
        } else if (speed < SUBSONIC_SHARE * SOUND_MPS) {
            source.underTicks++;
            source.overTicks = 0;
        }
        if (source.live == null && source.overTicks >= SUSTAIN_TICKS && hasAir(level, at)) {
            source.live = new Stretch();
            vapourCone(level, at, velocityMps);
        }
        Stretch live = source.live;
        if (live != null) {
            live.aboard = aboard;
            if (speed >= SOUND_MPS) live.waves.addLast(new Wave(now, at));
            if (source.underTicks >= SUBSONIC_TICKS) {
                source.ending.add(live);
                source.live = null;
            }
        }
        return true;
    }

    /** No air, no sound: nothing booms where there is no gravity (VS Genesis's space, an orbit). */
    private static boolean hasAir(ServerLevel level, Vec3 at) {
        return Gravity.ambientStrength(level, at) >= Gravity.WEIGHTLESS_BELOW;
    }

    /** After every source has been observed this tick: whom the shock waves reach now. */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        Map<Key, Source> sources = SOURCES.get(level);
        if (sources == null || sources.isEmpty()) return;
        long now = level.getGameTime();
        Iterator<Source> it = sources.values().iterator();
        while (it.hasNext()) {
            Source source = it.next();
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
            if (source.live == null && source.ending.isEmpty() && now - source.seen > FORGET_TICKS) it.remove();
        }
    }

    private static void expire(Stretch stretch, long now) {
        while (!stretch.waves.isEmpty() && now - stretch.waves.peekFirst().tick() > WAVE_TICKS) stretch.waves.removeFirst();
    }

    /**
     * Booms for every listener the stretch's shock has reached by now and who hasn't heard it: the first of its waves
     * to get there (within hearing range) is the boom, from where that wave came from.
     */
    private static void arrive(ServerLevel level, Stretch stretch, long now) {
        if (stretch.waves.isEmpty()) return;
        for (ServerPlayer listener : level.players()) {
            if (listener.isSpectator() || stretch.heard.contains(listener.getUUID()) || stretch.aboard.test(listener)) continue;
            Vec3 ear = listener.getEyePosition();
            Wave first = null;
            double firstArrival = Double.POSITIVE_INFINITY, firstDistance = 0.0;
            for (Wave wave : stretch.waves) {
                double distance = wave.from().distanceTo(ear);
                if (distance > HEARING_RANGE) continue;
                double arrival = wave.tick() + distance / SOUND_BPT;
                if (arrival < firstArrival) {
                    firstArrival = arrival;
                    first = wave;
                    firstDistance = distance;
                }
            }
            if (first == null || firstArrival > now) continue;
            stretch.heard.add(listener.getUUID());
            boom(level, listener, first.from(), firstDistance);
        }
    }

    private static void boom(ServerLevel level, ServerPlayer listener, Vec3 from, double distance) {
        // a little deeper the farther it travelled
        float far = (float) (distance / HEARING_RANGE);
        float crack = (0.72f + (level.random.nextFloat() - level.random.nextFloat()) * 0.06f) * (1.0f - 0.15f * far);
        send(listener, SoundEvents.GENERIC_EXPLODE, from, crack, level.random.nextLong());
        send(listener, SoundEvents.WARDEN_SONIC_BOOM, from, 2.0f - 0.4f * far, level.random.nextLong());
    }

    private static void send(ServerPlayer listener, SoundEvent sound, Vec3 at, float pitch, long seed) {
        listener.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.NEUTRAL,
                at.x, at.y, at.z, VOLUME, pitch, seed));
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
