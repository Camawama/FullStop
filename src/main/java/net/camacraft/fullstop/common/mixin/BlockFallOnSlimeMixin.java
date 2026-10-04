package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.physics.PlayerLandingBounce;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The local player's landing on slime when vanilla names another block as the one it stands on: under a Gravity Unbound
 * field on a Valkyrien Skies ship (pulled onto its underside), Valkyrien Skies answers "the block it is on" by looking
 * down the world's way, and this plain block's hook stopped the landing on the slime that is really under its feet.
 * Every block without a hook of its own lands here ({@code Block.updateEntityAfterFallOn}); the slime's own hook is
 * {@code SlimeBlockNoBounceMixin}.
 */
@Mixin(Block.class)
public abstract class BlockFallOnSlimeMixin {

    @Inject(method = "updateEntityAfterFallOn", at = @At("HEAD"), cancellable = true)
    private void fullstop$landOnSlimeUnderFeet(BlockGetter level, Entity entity, CallbackInfo ci) {
        if (!PlayerLandingBounce.ownsLanding(entity) || entity.getDeltaMovement().y >= 0.0D) return;
        PlayerLandingBounce.Surface surface = PlayerLandingBounce.underFeet(entity);
        if (surface != null && PlayerLandingBounce.bounce(entity, surface)) ci.cancel();
    }
}
