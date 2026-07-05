package net.camacraft.fullstop.client.sound;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.client.mixin.ChannelAccessor;
import net.camacraft.fullstop.client.mixin.SoundEngineAccessor;
import net.camacraft.fullstop.client.mixin.SoundManagerAccessor;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.rules.GForceThresholds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundSourceEvent;
import net.minecraftforge.client.event.sound.PlayStreamingSourceEvent;
import net.minecraftforge.client.event.sound.SoundEngineLoadEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTEfx;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class AudioFilterManager {

    private static int filterObject = -1;
    private static boolean supported = false;
    private static boolean initialized = false;
    private static float currentCutoff = 1.0f; // 1.0 = full range (22kHz), 0.0 = min range

    private static void init() {
        if (initialized) return;
        initialized = true;

        try {
            // Check if OpenAL EFX is supported
            long device = org.lwjgl.openal.ALC10.alcGetContextsDevice(org.lwjgl.openal.ALC10.alcGetCurrentContext());
            if (org.lwjgl.openal.ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
                supported = true;
                
                // Generate a Filter Object (not an Effect Object)
                if (filterObject != -1) {
                    EXTEfx.alDeleteFilters(filterObject);
                }
                filterObject = EXTEfx.alGenFilters();

                // Set filter type to Low Pass
                EXTEfx.alFilteri(filterObject, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
                
                // Check for errors
                if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                    supported = false;
                    System.err.println("FullStop: Failed to initialize OpenAL EFX for audio filtering.");
                } else {
                    // Initial filter settings (no filtering)
                    EXTEfx.alFilterf(filterObject, EXTEfx.AL_LOWPASS_GAIN, 1.0f);
                    EXTEfx.alFilterf(filterObject, EXTEfx.AL_LOWPASS_GAINHF, 1.0f);
                }
            }
        } catch (Exception e) {
            supported = false;
            e.printStackTrace();
        }
    }

    public static void applyFilterToAllChannels() {
        if (!supported || filterObject == -1) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() == null) return;

        try {
            var soundManager = mc.getSoundManager();
            var engine = ((SoundManagerAccessor) soundManager).fullstop$getSoundEngine();
            if (engine == null) return;

            ChannelAccess access = ((SoundEngineAccessor) engine).fullstop$getChannelAccess();
            if (access == null) return;

            // ✅ This already runs on the sound thread
            access.executeOnChannels(stream -> {
                stream.forEach(channel -> {
                    if (channel instanceof ChannelAccessor accessor) {
                        int sourceId = accessor.fullstop$getSource();
                        if (sourceId != 0) {
                            AL10.alSourcei(sourceId, EXTEfx.AL_DIRECT_FILTER, filterObject);
                        }
                    }
                });
            });

        } catch (Exception ignored) {}
    }

    @SubscribeEvent
    public static void onSoundEngineLoad(SoundEngineLoadEvent event) {
        // Re-initialize when sound engine reloads
        initialized = false;
        filterObject = -1;
        init();
    }

    @SubscribeEvent
    public static void onPlaySoundSource(PlaySoundSourceEvent event) {
        if (!supported || filterObject == -1) return;
        
        // Apply the filter to the new source
        if (event.getChannel() instanceof ChannelAccessor accessor) {
            int sourceId = accessor.fullstop$getSource();
            if (sourceId != 0) {
                AL10.alSourcei(sourceId, EXTEfx.AL_DIRECT_FILTER, filterObject);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayStreamingSource(PlayStreamingSourceEvent event) {
        if (!supported || filterObject == -1) return;
        
        // Apply the filter to the new streaming source
        if (event.getChannel() instanceof ChannelAccessor accessor) {
            int sourceId = accessor.fullstop$getSource();
            if (sourceId != 0) {
                AL10.alSourcei(sourceId, EXTEfx.AL_DIRECT_FILTER, filterObject);
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.isCreative() || minecraft.player.isSpectator()
                || !FullStopConfig.SERVER_SPEC.isLoaded()) {
            if (currentCutoff < 0.99f) {
                currentCutoff = 1.0f;
                updateFilter(1.0f);
            }
            return;
        }

        if (!initialized) {
            init();
        }

        if (!supported) return;

        float gForceCutoff = 1.0f;
        if (FullStopConfig.SERVER.enableGForceEffects.get()) {
            FullStopCapability cap = FullStopCapability.grabCapability(minecraft.player);
            if (cap != null) {
                double gForce = cap.getRunningAverageDelta();
                GForceThresholds.Range thresholds = GForceThresholds.effective(minecraft.player,
                        FullStopConfig.CLIENT.minGForceThreshold.get(),
                        FullStopConfig.CLIENT.maxGForceThreshold.get());

                if (gForce > thresholds.min()) {
                    float intensity = (float) ((gForce - thresholds.min()) / (thresholds.max() - thresholds.min()));
                    intensity = Math.min(intensity, 1.0f);
                    float curve = intensity * intensity;
                    gForceCutoff = 1.0f - (curve * 0.98f);
                }
            }
        }

        float drowningCutoff = 1.0f;
        int airSupply = minecraft.player.getAirSupply();
        int maxAir = minecraft.player.getMaxAirSupply();
        if (airSupply < maxAir) {
            float intensity = 1.0f - ((float)airSupply / (float)maxAir);
            float curve = intensity * intensity * intensity;
            drowningCutoff = 1.0f - (curve * 0.98f);
        }

        float targetCutoff = Math.min(gForceCutoff, drowningCutoff);

        currentCutoff = currentCutoff + (targetCutoff - currentCutoff) * 0.07f;
        
        if (Math.abs(currentCutoff - targetCutoff) > 0.001f || currentCutoff < 0.99f) {
            updateFilter(currentCutoff);
        }
    }

    private static void updateFilter(float cutoff) {
        if (!supported || filterObject == -1) return;
        
        float gain = 0.05f + (cutoff * 0.95f);
        
        EXTEfx.alFilterf(filterObject, EXTEfx.AL_LOWPASS_GAIN, gain);
        EXTEfx.alFilterf(filterObject, EXTEfx.AL_LOWPASS_GAINHF, cutoff * cutoff * cutoff);
    }
}
