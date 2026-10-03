package net.camacraft.fullstop.server.physics.ship;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * The sound of a ship hitting something: BOTH touching blocks' own sounds at the contact - a
 * stone cube landing on grass plays grass and stone - with block-crack particles from both on
 * harder hits. Played from the server ({@code level.playSound} with no player), so every client
 * hears it without a packet of FullStop's own.
 *
 * <p>How it sounds: each block's landing (fall) sound, joined by its hit sound from
 * {@link #HIT_LAYER_MPS} and its break sound from {@link #BREAK_LAYER_MPS}. Louder the faster the
 * hit and the heavier the ship ({@link #volume}), higher the faster and a little
 * deeper the heavier ({@link #pitch}), both scaled by the block's own sound settings, with a little random pitch.
 *
 * <p>Not a machine gun: only contacts that just began make a sound, or one clearly harder than
 * the ship's last (a scrape that suddenly bites); at most {@link #MAX_HITS_PER_SHIP} per ship per
 * tick, and one ship sounds again only after {@link #COOLDOWN_TICKS} unless it hits twice as hard.
 * A ship resting or sliding on the ground is silent.
 */
public final class ShipImpactSounds {

    /** Speed (m/s) from which a hit layers each block's hit sound over its landing sound. */
    static final double HIT_LAYER_MPS = 6.0;
    /** Speed (m/s) from which it also layers the break sound: a crunch. */
    static final double BREAK_LAYER_MPS = 12.0;
    /** Speed (m/s) from which a hit throws block-crack particles. */
    static final double PARTICLE_MPS = 4.0;
    static final int COOLDOWN_TICKS = 5;
    static final int MAX_HITS_PER_SHIP = 2;
    /** A contact that didn't just begin sounds only when this much harder than the ship's last sound. */
    private static final double SCRAPE_JUMP = 1.5;
    /** How long a ship's last sound counts against continuing contacts, ticks. */
    private static final int SCRAPE_MEMORY_TICKS = 20;
    /** Two sounding contacts of one ship this close (m) are one. */
    private static final double SAME_SPOT = 1.0;

    /** Each ship's (or ship pair's) last sound, per level. */
    private static final Map<ServerLevel, Long2ObjectMap<Last>> LAST = new WeakHashMap<>();

    private record Last(long tick, double speed) {}

    /**
     * One contact that may sound: where, how fast (m/s into the contact), and the blocks on either
     * side (the terrain's and the hull's, or both hulls'; either may be unknown).
     */
    public record Hit(Vec3 point, double speed, @Nullable BlockState first, @Nullable BlockState second) {}

    private ShipImpactSounds() {
    }

    // ------------------------------------------------------------------
    // The numbers
    // ------------------------------------------------------------------

    /**
     * Volume before the block's own and the config's: about 0.4 for a bump, 0.85 at 10 m/s, 1.35
     * at 20 m/s (above 1 a sound carries further, it isn't louder), times a weight factor: 0.9 for
     * a one-tonne ship, 1.1 at 100 t, 1.3 from 10,000 t (0.7 for the tiniest). Never falls as
     * speed or mass grows.
     */
    public static double volume(double speedMps, double massKg) {
        double speed = Mth.clamp(speedMps, 0.0, 40.0);
        return Mth.clamp(0.35 + 0.05 * speed, 0.35, 2.0) * heft(massKg);
    }

    /**
     * Pitch before the block's own: about 0.75 for a slow bump, 1.0 at 10 m/s, 1.3 at 20 m/s (a
     * fast hit cracks), for a one-tonne ship; 0.04 lower for every tenfold more mass (a big ship
     * sounds a little heavier at the same speed). Never falls as speed grows, never rises as mass
     * grows.
     */
    public static double pitch(double speedMps, double massKg) {
        double speed = Mth.clamp(speedMps, 0.0, 40.0);
        double tonnesDecades = Mth.clamp(Math.log10(Math.max(massKg, 1.0) / 1000.0), -1.0, 3.0);
        return Mth.clamp(0.7 + 0.03 * speed - 0.04 * tonnesDecades, 0.55, 1.6);
    }

    private static double heft(double massKg) {
        if (!(massKg > 0) || Double.isInfinite(massKg)) return 1.0;
        return Mth.clamp(0.9 + 0.1 * Math.log10(massKg / 1000.0), 0.7, 1.3);
    }

    // ------------------------------------------------------------------
    // Playing
    // ------------------------------------------------------------------

    /**
     * Plays the strongest of {@code hits} for one ship (or one ship pair, keyed by {@code key}) if
     * it deserves a sound this tick. {@code started}: the collision began this tick.
     */
    public static void play(ServerLevel level, long key, double massKg, boolean started, List<Hit> hits, long now) {
        if (!SERVER.impactSounds.get() || hits.isEmpty()) return;
        double min = SERVER.impactSoundMinSpeed.get();

        List<Hit> loud = new ArrayList<>(MAX_HITS_PER_SHIP);
        double strongest = 0.0;
        for (Hit hit : hits) {
            if (hit.speed() < min || (hit.first() == null && hit.second() == null)) continue;
            strongest = Math.max(strongest, hit.speed());
        }
        if (strongest <= 0.0) return;

        Long2ObjectMap<Last> lasts = LAST.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        Last last = lasts.get(key);
        if (last != null) {
            long since = now - last.tick();
            // Continuing contact (resting, sliding, grinding): quiet unless it suddenly bites.
            if (!started && since < SCRAPE_MEMORY_TICKS && strongest < last.speed() * SCRAPE_JUMP) return;
            if (since < COOLDOWN_TICKS && strongest < last.speed() * 2.0) return;
        }

        // Fastest first (Camafy's order); spots close together sound once.
        for (Hit hit : hits) {
            if (loud.size() >= MAX_HITS_PER_SHIP) break;
            if (hit.speed() < min || (hit.first() == null && hit.second() == null)) continue;
            boolean near = false;
            for (Hit played : loud) {
                if (played.point().distanceToSqr(hit.point()) < SAME_SPOT * SAME_SPOT) {
                    near = true;
                    break;
                }
            }
            if (!near) loud.add(hit);
        }

        for (Hit hit : loud) {
            sound(level, hit, massKg);
            if (SERVER.impactParticles.get() && hit.speed() >= PARTICLE_MPS) {
                particles(level, hit.point(), hit.first(), hit.speed());
                particles(level, hit.point(), hit.second(), hit.speed());
            }
        }
        lasts.put(key, new Last(now, strongest));
        if (lasts.size() > 512) {
            lasts.values().removeIf(l -> now - l.tick() > SCRAPE_MEMORY_TICKS);
        }
    }

    private static void sound(ServerLevel level, Hit hit, double massKg) {
        double baseVolume = volume(hit.speed(), massKg) * SERVER.impactSoundVolume.get();
        double basePitch = pitch(hit.speed(), massKg);
        if (!(baseVolume > 0.0)) return;
        SoundType firstType = hit.first() != null ? hit.first().getSoundType() : null;
        SoundType secondType = hit.second() != null ? hit.second().getSoundType() : null;
        if (firstType != null) layer(level, hit, firstType, baseVolume, basePitch);
        // The same material on both sides (stone on stone) sounds once, a little louder.
        if (secondType != null && secondType != firstType) layer(level, hit, secondType, baseVolume, basePitch);
    }

    private static void layer(ServerLevel level, Hit hit, SoundType type, double baseVolume, double basePitch) {
        emit(level, hit.point(), type.getFallSound(), baseVolume * type.getVolume(), basePitch * type.getPitch());
        if (hit.speed() >= HIT_LAYER_MPS) {
            emit(level, hit.point(), type.getHitSound(), 0.7 * baseVolume * type.getVolume(), basePitch * type.getPitch());
        }
        if (hit.speed() >= BREAK_LAYER_MPS) {
            emit(level, hit.point(), type.getBreakSound(), 0.5 * baseVolume * type.getVolume(), 0.9 * basePitch * type.getPitch());
        }
    }

    private static void emit(ServerLevel level, Vec3 at, @Nullable SoundEvent sound, double volume, double pitch) {
        if (sound == null) return;
        float jitter = 0.92f + level.random.nextFloat() * 0.16f;
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.BLOCKS,
                (float) volume, Mth.clamp((float) pitch * jitter, 0.3f, 2.0f));
    }

    private static void particles(ServerLevel level, Vec3 at, @Nullable BlockState state, double speed) {
        if (state == null || state.isAir()) return;
        int count = Mth.clamp((int) Math.round(speed), 4, 24);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z, count,
                0.25, 0.25, 0.25, 0.15);
    }
}
