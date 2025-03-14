package net.camacraft.fullstop.common.effects;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

public class NoJumpEffect extends MobEffect {

    public NoJumpEffect() {
        // Use MobEffectCategory.HARMFUL for debuffs
        super(MobEffectCategory.HARMFUL, 0xA9A9A9); // Color: Dark Gray (example)
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        // No direct effect needed; the jump prevention will be handled elsewhere
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        // Return false to avoid ticking continuously
        return false;
    }
}
