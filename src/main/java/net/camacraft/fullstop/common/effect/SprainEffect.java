package net.camacraft.fullstop.common.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

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

        if (!canBypass && entity.onGround()) {
            // --- Slow horizontal movement every tick ---
            double slowFactor = 0.4 - (amplifier * 0.1);
            if (slowFactor < 0.1) slowFactor = 0.1;

            entity.setDeltaMovement(
                    entity.getDeltaMovement().x * slowFactor,
                    entity.getDeltaMovement().y,
                    entity.getDeltaMovement().z * slowFactor
            );
        }

        // Always suppress the "jumping" flag to prevent the intent to jump
        entity.setJumping(false);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return true; // Run every tick
    }
}
