package net.camacraft.fullstop.client.mixin;

import com.mojang.blaze3d.Blaze3D;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    /**
     * Effective per-frame correction fraction is delta × 0.005 (capability) ×
     * 0.15 (Entity.turn's hidden multiplier) = 0.00075 × delta. Cap delta so the
     * fraction stays below ~0.9 — uncapped, any frame slower than ~15 FPS (or a
     * single >133 ms stall) overshot the target and the camera flailed
     * divergently instead of easing in.
     */
    @Unique
    private static final double FULLSTOP$MAX_DELTA = 1200.0;

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void turningPlayer(CallbackInfo ci) {
        // No corrections while the mouse is ungrabbed (inventory/pause): mouse
        // motion can't cancel them there, so the camera would swing behind the screen.
        if (!((MouseHandler) (Object) this).isMouseGrabbed()) return;

        double time = Blaze3D.getTime();
        double delta = Math.min((time - lastMouseEventTime) * 1000 * 20, FULLSTOP$MAX_DELTA);

        if (minecraft.player == null || DamageImmunityRules.unphysable(minecraft.player)) return;

        FullStopCapability fullstop = FullStopCapability.grabCapability(minecraft.player);
        
        if (fullstop == null) return;

        if (fullstop.isMostlyDownward() || fullstop.isMostlyUpward()) {
            fullstop.setTargetAngle(Double.NaN);
        }

        if (accumulatedDX != 0 || accumulatedDY != 0) {
            fullstop.setTargetAngle(Double.NaN);
            fullstop.setTargetPitch(Double.NaN);
        }

        double rotationCorrection = fullstop.rotationCorrection(delta);
        double pitchCorrection = fullstop.pitchCorrection(delta);

        if (rotationCorrection != 0.0 || pitchCorrection != 0.0) {
            // Apply the physics-based rotation correction.
            // We do NOT cancel the event, allowing vanilla logic (and other mods)
            // to handle the actual mouse input (accumulatedDX/DY).
            // This ensures we don't eat mouse inputs or break compatibility.
            minecraft.player.turn(rotationCorrection, pitchCorrection);
        }
    }
}