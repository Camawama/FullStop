package net.camacraft.fullstop.client.sound;

import net.camacraft.camafy.client.sound.AudioEffects;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.client.render.OxygenBlackout;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.rules.GForceThresholds;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Muffles all game sound while drowning or pulling heavy g: a low-pass whose
 * cutoff eases toward the stronger of the two effects. The OpenAL side (the
 * EFX filter, attaching it to every playing and starting source on the sound
 * thread, surviving sound-engine reloads) is Camafy's {@link AudioEffects};
 * this class only decides how muffled things should be.
 */
@Mod.EventBusSubscriber(modid = FullStop.MODID, value = Dist.CLIENT)
public class AudioFilterManager {

    /** FullStop's request with Camafy's audio effects; the strongest muffle of all mods wins. */
    private static final ResourceLocation MUFFLE = new ResourceLocation(FullStop.MODID, "muffle");

    private static float currentCutoff = 1.0f; // 1.0 = full range (22kHz), 0.0 = min range

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.isCreative() || minecraft.player.isSpectator()
                || !FullStopConfig.SERVER_SPEC.isLoaded()) {
            currentCutoff = 1.0f;
            AudioEffects.clear(MUFFLE);
            return;
        }

        // Another mod owning this player's felt g (G_FORCE) or air (ENVIRONMENT)
        // turns that muffle off: an MKW racer hears the race.
        FullStopCapability cap = FullStopCapability.grabCapability(minecraft.player);
        int claims = cap != null ? cap.getClaims() : 0;

        float gForceCutoff = 1.0f;
        if (FullStopConfig.SERVER.enableGForceEffects.get() && !PhysicsAspect.G_FORCE.in(claims)) {
            if (cap != null) {
                double gForce = cap.getRunningAverageDelta();
                GForceThresholds.Range thresholds = GForceThresholds.effective(minecraft.player,
                        FullStopConfig.SERVER.minGForceThreshold.get(),
                        FullStopConfig.SERVER.maxGForceThreshold.get());

                if (gForce > thresholds.min()) {
                    // max(…, 0.001): min == max in the config would make this 0/0 = NaN.
                    float intensity = (float) ((gForce - thresholds.min()) / Math.max(thresholds.max() - thresholds.min(), 0.001));
                    intensity = Math.min(intensity, 1.0f);
                    float curve = intensity * intensity;
                    gForceCutoff = 1.0f - (curve * 0.98f);
                }
            }
        }

        float drowningCutoff = 1.0f;
        if (!PhysicsAspect.ENVIRONMENT.in(claims)) {
            float curve = OxygenBlackout.intensity(minecraft.player);
            drowningCutoff = 1.0f - (curve * 0.98f);
        }

        float targetCutoff = Math.min(gForceCutoff, drowningCutoff);

        currentCutoff = currentCutoff + (targetCutoff - currentCutoff) * 0.07f;

        // Camafy applies the curve FullStop always used: gain 0.05 + 0.95c,
        // high-frequency gain c³.
        if (currentCutoff >= 0.999f) {
            AudioEffects.clear(MUFFLE);
        } else {
            AudioEffects.setLowPassCutoff(MUFFLE, currentCutoff);
        }
    }
}
