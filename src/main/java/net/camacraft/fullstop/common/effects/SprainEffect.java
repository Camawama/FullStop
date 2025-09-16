package net.camacraft.fullstop.common.effects;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class SprainEffect extends MobEffect {

    public SprainEffect() {
        super(MobEffectCategory.HARMFUL, 0x8B5A2B);
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        boolean isPlayer = entity instanceof Player;
        boolean canBypass = false;

        if (isPlayer) {
            Player player = (Player) entity;
            // Creative flying players should be unaffected
            canBypass = player.isFallFlying() || (player.getAbilities().flying && player.isCreative());
        }

        if (!canBypass) {
            // Prevent upward jumping
            if (entity.getDeltaMovement().y > 0) {
                entity.setDeltaMovement(
                        entity.getDeltaMovement().x,
                        Math.min(0, entity.getDeltaMovement().y), // clamp Y at 0
                        entity.getDeltaMovement().z
                );
            }

            // Slow down horizontal movement
            double slowFactor = 0.4 - (amplifier * 0.1);
            if (slowFactor < 0.1) slowFactor = 0.1; // avoid freezing or reversing

            entity.setDeltaMovement(
                    entity.getDeltaMovement().x * slowFactor,
                    entity.getDeltaMovement().y,
                    entity.getDeltaMovement().z * slowFactor
            );
        }

        entity.setJumping(false);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return true; // Run every tick
    }
}
