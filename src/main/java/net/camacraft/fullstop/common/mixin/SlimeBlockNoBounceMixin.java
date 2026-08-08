package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SlimeBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Disables vanilla slime block bounce so FullStop's BounceHandler owns it.
 * Cancellable inject instead of @Overwrite for compatibility with other mods.
 */
@Mixin(SlimeBlock.class)
public abstract class SlimeBlockNoBounceMixin {

    @Inject(method = "updateEntityAfterFallOn", at = @At("HEAD"), cancellable = true)
    private void fullstop$suppressVanillaBounce(BlockGetter level, Entity entity, CallbackInfo ci) {
        // Entities FullStop never simulates (dropped items, XP orbs) keep their
        // vanilla bounce — suppressing it for them replaced it with nothing.
        if (DamageImmunityRules.unphysableIgnoringAi(entity)) return;

        entity.setDeltaMovement(entity.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
        ci.cancel();
    }
}
