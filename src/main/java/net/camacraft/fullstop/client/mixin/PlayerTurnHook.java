package net.camacraft.fullstop.client.mixin;

import com.mojang.blaze3d.Blaze3D;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
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

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void turningPlayer(CallbackInfo ci) {
        double time = Blaze3D.getTime();
        double delta = (time - lastMouseEventTime) * 1000 * 20;
        if (minecraft.player == null || DamageImmunityRules.unphysable(minecraft.player)) return;

        FullStopCapability fullstop = FullStopCapability.grabCapability(minecraft.player);
        if (fullstop == null) return;

        if (accumulatedDX != 0 || accumulatedDY != 0) {
            fullstop.setTargetAngle(Double.NaN);
        }

        double rotationCorrection = fullstop.rotationCorrection(delta);

        if (rotationCorrection != 0.0) {
            // Apply the physics-based rotation correction.
            // We do NOT cancel the event, allowing vanilla logic (and other mods)
            // to handle the actual mouse input (accumulatedDX/DY).
            // This ensures we don't eat mouse inputs or break compatibility.
            minecraft.player.turn(rotationCorrection, 0.0);
        }
    }
}