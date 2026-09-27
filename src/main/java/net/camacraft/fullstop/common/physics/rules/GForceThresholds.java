package net.camacraft.fullstop.common.physics.rules;

import net.camacraft.fullstop.common.registry.ModEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * Shared clarity/vertigo math. Clarity raises the g-force an entity can tolerate,
 * vertigo lowers it. Used by the vignette renderer, the audio filter, and the
 * capability's g-force smoothing so all three always agree.
 */
public final class GForceThresholds {
    private GForceThresholds() {
    }

    public record Range(double min, double max) {
    }

    /** Positive = net vertigo (more sensitive), negative = net clarity (more tolerant). */
    public static int netEffectLevel(LivingEntity entity) {
        int clarityLevel = 0;
        int vertigoLevel = 0;

        MobEffectInstance clarity = entity.getEffect(ModEffects.CLARITY.get());
        if (clarity != null) {
            clarityLevel = clarity.getAmplifier() + 1;
        }

        MobEffectInstance vertigo = entity.getEffect(ModEffects.VERTIGO.get());
        if (vertigo != null) {
            vertigoLevel = vertigo.getAmplifier() + 1;
        }

        return vertigoLevel - clarityLevel;
    }

    /** The configured effect thresholds, scaled by the entity's clarity/vertigo. */
    public static Range configured(LivingEntity entity) {
        return effective(entity,
                net.camacraft.fullstop.FullStopConfig.SERVER.minGForceThreshold.get(),
                net.camacraft.fullstop.FullStopConfig.SERVER.maxGForceThreshold.get());
    }

    /** Sustained g at which nausea sets in: halfway between the vignette's start and the full blackout. */
    public static double nauseaLevel(Range range) {
        return range.min() + 0.5 * (range.max() - range.min());
    }

    /** Sustained g at which the blackout is complete and the entity is blinded (G-LOC). */
    public static double blackoutLevel(Range range) {
        return range.max();
    }

    /** The spin-nausea threshold (degrees per tick, smoothed) scaled by clarity/vertigo; 0 when disabled. */
    public static double spinNauseaLevel(LivingEntity entity) {
        double base = net.camacraft.fullstop.FullStopConfig.SERVER.spinNauseaThreshold.get();
        if (base <= 0) return 0;
        return effective(entity, base, base).min();
    }

    /** Applies clarity/vertigo scaling to a configured min/max g-force threshold pair. */
    public static Range effective(LivingEntity entity, double baseMin, double baseMax) {
        int netLevel = netEffectLevel(entity);

        if (netLevel > 0) {
            double multiplier = Math.pow(0.8, netLevel);
            return new Range(baseMin * multiplier, baseMax * multiplier);
        } else if (netLevel < 0) {
            double multiplier = Math.pow(1.25, -netLevel);
            return new Range(baseMin * multiplier, baseMax * multiplier);
        }
        return new Range(baseMin, baseMax);
    }
}
