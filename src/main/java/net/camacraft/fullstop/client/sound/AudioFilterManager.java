package net.camacraft.fullstop.client.sound;

import net.camacraft.fullstop.FullStop;
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

@Mod.EventBusSubscriber(modid = FullStop.MODID, value = Dist.CLIENT)
public class AudioFilterManager {

    // Written on the sound-engine thread, read from the game thread.
    private static volatile int filterObject = -1;
    private static volatile boolean supported = false;
    private static volatile boolean initialized = false;
    private static float currentCutoff = 1.0f; // 1.0 = full range (22kHz), 0.0 = min range
    // Ticks the filter has been fully transparent; lets the per-tick reattach
    // stop once the transparent parameters have propagated to every source.
    private static int transparentTicks = 0;

    /**
     * Runs AL work on the sound engine's own executor thread. ALL of this class's
     * AL calls go through here: Minecraft makes its AL calls (and its alGetError
     * checks) on that thread, and AL error state is shared per context — calling
     * alFilterf/alSourcei from the game thread raced those checks and spammed
     * "Invalid parameter" errors from Mojang's own source management whenever the
     * filter was being updated every tick (i.e. while drowning).
     */
    private static void runOnSoundThread(Runnable task) {
        try {
            var soundManager = Minecraft.getInstance().getSoundManager();
            if (soundManager == null) return;
            var engine = ((SoundManagerAccessor) soundManager).fullstop$getSoundEngine();
            if (engine == null) return;
            ((SoundEngineAccessor) engine).fullstop$getExecutor().execute(() -> {
                try {
                    task.run();
                } finally {
                    // Drain any error our calls raised so Mojang's next check
                    // doesn't report it against an unrelated operation.
                    AL10.alGetError();
                }
            });
        } catch (Exception ignored) {}
    }

    private static void init() {
        if (initialized) return;
        initialized = true;

        runOnSoundThread(() -> {
        try {
            // Check if OpenAL EFX is supported
            long device = org.lwjgl.openal.ALC10.alcGetContextsDevice(org.lwjgl.openal.ALC10.alcGetCurrentContext());
            if (org.lwjgl.openal.ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
                supported = true;
                
                // Generate a Filter Object (not an Effect Object). No cleanup of a
                // previous id needed: init only re-runs after an engine reload, and
                // the old AL context (and its filter) died with it.
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
        });
    }

    public static void applyFilterToAllChannels() {
        if (!supported || filterObject == -1) return;

        // Once the filter has been transparent long enough for the final
        // parameters to propagate, stop enqueueing per-tick reattach work
        // (this used to run every tick forever, title screen included).
        if (transparentTicks > 2) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() == null) return;

        try {
            var soundManager = mc.getSoundManager();
            var engine = ((SoundManagerAccessor) soundManager).fullstop$getSoundEngine();
            if (engine == null) return;

            ChannelAccess access = ((SoundEngineAccessor) engine).fullstop$getChannelAccess();
            if (access == null) return;

            // Called on the game thread; executeOnChannels dispatches the body
            // to the sound executor, which is what makes the AL calls safe.
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

    /**
     * SoundEngineLoadEvent is a MOD-bus event; subscribed on the Forge bus this
     * never fired, so any sound-engine reload (F3+T, resource packs, output
     * device change) destroyed the AL context and left a stale filter id —
     * killing the muffle until game restart.
     */
    @Mod.EventBusSubscriber(modid = FullStop.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBusHandlers {
        @SubscribeEvent
        public static void onSoundEngineLoad(SoundEngineLoadEvent event) {
            initialized = false;
            filterObject = -1;
            init();
        }
    }

    @SubscribeEvent
    public static void onPlaySoundSource(PlaySoundSourceEvent event) {
        if (!supported || filterObject == -1) return;

        // Apply the filter to the new source
        if (event.getChannel() instanceof ChannelAccessor accessor) {
            attachFilter(accessor.fullstop$getSource());
        }
    }

    @SubscribeEvent
    public static void onPlayStreamingSource(PlayStreamingSourceEvent event) {
        if (!supported || filterObject == -1) return;

        // Apply the filter to the new streaming source
        if (event.getChannel() instanceof ChannelAccessor accessor) {
            attachFilter(accessor.fullstop$getSource());
        }
    }

    /** Attach on the sound thread; the source may already be gone by then (short sounds), hence the alIsSource guard. */
    private static void attachFilter(int sourceId) {
        if (sourceId == 0) return;
        runOnSoundThread(() -> {
            if (supported && filterObject != -1 && AL10.alIsSource(sourceId)) {
                AL10.alSourcei(sourceId, EXTEfx.AL_DIRECT_FILTER, filterObject);
            }
        });
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
                    // max(…, 0.001): min == max in the config would make this 0/0 = NaN.
                    float intensity = (float) ((gForce - thresholds.min()) / Math.max(thresholds.max() - thresholds.min(), 0.001));
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

        if (currentCutoff >= 0.999f) {
            transparentTicks++;
        } else {
            transparentTicks = 0;
        }
    }

    private static void updateFilter(float cutoff) {
        if (!supported || filterObject == -1) return;

        runOnSoundThread(() -> {
            if (!supported || filterObject == -1) return;
            float gain = 0.05f + (cutoff * 0.95f);

            EXTEfx.alFilterf(filterObject, EXTEfx.AL_LOWPASS_GAIN, gain);
            EXTEfx.alFilterf(filterObject, EXTEfx.AL_LOWPASS_GAINHF, cutoff * cutoff * cutoff);
        });
    }
}
