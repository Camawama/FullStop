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
