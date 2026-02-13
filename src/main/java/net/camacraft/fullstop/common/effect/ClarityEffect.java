package net.camacraft.fullstop.common.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

public class ClarityEffect extends MobEffect {
    public ClarityEffect() {
        super(MobEffectCategory.BENEFICIAL, 0x00FFFF); // Cyan color
    }
}
