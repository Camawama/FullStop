package net.camacraft.fullstop.common.mixin;

import com.mojang.blaze3d.Blaze3D;
import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class PlayerTurnHook {
    @Shadow
    private double lastMouseEventTime;

    @Final
    @Shadow
    private Minecraft minecraft;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void turningPlayer(CallbackInfo ci) {
        double time = Blaze3D.getTime();
        double delta = (time - lastMouseEventTime) * 1000 * 20;
        if (minecraft.player == null) return;

        FullStopCapability fullstop = FullStopCapability.grabCapability(minecraft.player);
        double rotationCorrection = fullstop.rotationCorrection(delta);

        if (Math.abs(rotationCorrection) > 1.0) {
            minecraft.player.turn(rotationCorrection, 0);
            this.lastMouseEventTime = time;
            ci.cancel();
        }
    }
}
