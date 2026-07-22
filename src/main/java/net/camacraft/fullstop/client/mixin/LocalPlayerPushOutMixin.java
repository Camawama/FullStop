package net.camacraft.fullstop.client.mixin;

import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player swallowed by an engulfing block (sand, gravel — see
 * fullstop:engulfing) is supposed to be BURIED: digging or jumping their way
 * out is the feature. Vanilla's suffocation helper, however, gently shoves the
 * player toward the nearest open space whenever they are inside blocks — which
 * ejected divers straight back out of the dune (one jump freed them, and a
 * horizontal fly-in was simply pushed back out the way it came in). The shove
 * is suppressed while the surrounding blocks are engulfing; every other
 * inside-a-wall situation keeps vanilla behavior.
 *
 * In 1.20.1 this helper lives in LocalPlayer (aiStep), so it is a client-side
 * hook — which is also the authoritative side for player movement.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerPushOutMixin {

    @Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
    private void fullstop$stayBuried(double x, double z, CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        BlockPos feet = self.blockPosition();
        BlockPos eyes = BlockPos.containing(self.getX(), self.getEyeY(), self.getZ());
        if (self.level().getBlockState(feet).is(FullStopTags.ENGULFING)
                || self.level().getBlockState(eyes).is(FullStopTags.ENGULFING)) {
            ci.cancel();
        }
    }
}
