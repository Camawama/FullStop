package net.camacraft.fullstop.common.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.camacraft.fullstop.common.physics.PlayerLandingBounce;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An elytra glide goes on through a bounce off slime on the server too ({@link PlayerLandingBounce#serverKeepsGlide}):
 * the two places vanilla ends a glide on the ground, the server's replay of a player's move ({@code travel}) and the
 * glide's own check ({@code updateFallFlying}). @WrapOperation so other mods wrapping the same calls (Gravity Unbound)
 * still compose with this one.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityGlideMixin {

    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setSharedFlag(IZ)V"))
    private void fullstop$glideOnThroughSlime(LivingEntity self, int flag, boolean value, Operation<Void> original) {
        if (flag == 7 && !value && PlayerLandingBounce.serverKeepsGlide(self)) return;
        original.call(self, flag, value);
    }

    @WrapOperation(method = "updateFallFlying", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;onGround()Z"))
    private boolean fullstop$slimeIsNoLanding(LivingEntity self, Operation<Boolean> original) {
        boolean grounded = original.call(self);
        return grounded && !PlayerLandingBounce.serverKeepsGlide(self);
    }
}
