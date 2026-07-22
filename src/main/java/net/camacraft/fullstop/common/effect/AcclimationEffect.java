package net.camacraft.fullstop.common.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * High-altitude counterpart to Water Breathing: while active, thin air costs no
 * air supply at all (PressureHandler skips the altitude drain and the exposure
 * ramp doesn't build). Brewed from an Awkward potion + phantom membrane.
 */
public class AcclimationEffect extends MobEffect {
    public AcclimationEffect() {
        super(MobEffectCategory.BENEFICIAL, 0xA8D8EA);
    }
}
