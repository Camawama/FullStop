package net.camacraft.fullstop.common.effects;

import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.Physics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class SprainEffect extends MobEffect {

    public SprainEffect() {
        super(MobEffectCategory.HARMFUL, 0x8B5A2B);
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        boolean canBypass = false;

        if (entity instanceof Player player) {
            // Creative flying / Elytra flight bypass
            canBypass = player.isFallFlying() ||
                    (player.getAbilities().flying && player.isCreative());
        }

        if (entity.hasEffect(MobEffects.LEVITATION) || entity.isFallFlying()) {
            canBypass = true;
        }

        if (!canBypass) {
            // --- Prevent jumping / bunny hopping ---
            if (entity.getDeltaMovement().y > 0) {
                // Stop all upward velocity
                entity.setDeltaMovement(
                        entity.getDeltaMovement().x,
                        0,
                        entity.getDeltaMovement().z
                );
            }

            // --- Slow horizontal movement every tick ---
            double slowFactor = 0.4 - (amplifier * 0.1);
            if (slowFactor < 0.1) slowFactor = 0.1;

            entity.setDeltaMovement(
                    entity.getDeltaMovement().x * slowFactor,
                    entity.getDeltaMovement().y, // Y stays clamped
                    entity.getDeltaMovement().z * slowFactor
            );
        }

        // Always suppress the "jumping" flag
        entity.setJumping(false);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return true; // Run every tick
    }
}