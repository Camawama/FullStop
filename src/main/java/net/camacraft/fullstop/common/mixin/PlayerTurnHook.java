package net.camacraft.fullstop.common.mixin;

import com.mojang.blaze3d.Blaze3D;
import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.physics.Physics;
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

    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    @Final
    @Shadow
    private Minecraft minecraft;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void turningPlayer(CallbackInfo ci) {
        double time = Blaze3D.getTime();
        double delta = (time - lastMouseEventTime) * 1000 * 20;
        if (minecraft.player == null || Physics.unphysable(minecraft.player)) return;

        FullStopCapability fullstop = FullStopCapability.grabCapability(minecraft.player);
        double rotationCorrection = fullstop.rotationCorrection(delta);

        if (rotationCorrection != 0.0) {
            double sensitivity = minecraft.options.sensitivity().get() * 0.6 + 0.2;
            double sensitivity3 = sensitivity * sensitivity * sensitivity;

            minecraft.player.turn(rotationCorrection, accumulatedDY * sensitivity3);
            lastMouseEventTime = time;
            accumulatedDX = 0.0;
            accumulatedDY = 0.0;
            ci.cancel();
        }
    }
}
