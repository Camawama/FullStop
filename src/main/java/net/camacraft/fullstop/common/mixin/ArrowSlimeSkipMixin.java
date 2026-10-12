package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.physics.SlimeProjectiles;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An arrow or trident that meets slime at a shallow angle skips off it instead of sticking ({@link SlimeProjectiles#skipArrow}).
 * Both sides: an arrow is simulated on its watchers' clients too, and whether it stuck is never sent.
 */
@Mixin(AbstractArrow.class)
public abstract class ArrowSlimeSkipMixin {

    @Inject(method = "onHitBlock(Lnet/minecraft/world/phys/BlockHitResult;)V", at = @At("HEAD"), cancellable = true)
    private void fullstop$skipOffSlime(BlockHitResult hit, CallbackInfo ci) {
        if (SlimeProjectiles.skipArrow((AbstractArrow) (Object) this, hit)) ci.cancel();
    }
}
