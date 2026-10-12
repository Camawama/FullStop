package net.camacraft.fullstop.client.sound;

import net.camacraft.camafy.client.sound.AudioEffects;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.network.SonicBoomPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundSourceEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * A sonic boom on this client (the owner, 2026-10-10: "the sound should be heard from even further away but low passed
 * the further away it is. It should still be loud from further away though"). The server says where the shock wave came
 * from and how far it travelled ({@link SonicBoomPacket}); the boom is played from there with no distance falloff of the
 * sound engine's own (vanilla's linear falloff made a boom 300 blocks off a whisper, and nothing past 384 blocks), at
 * the loudness of {@link #gain} - full to 48 blocks, still 45% at the edge of the hearing range - and low-passed by
 * {@link #gainHf} through Camafy's {@code AudioEffects} (the one place every mod's muffling goes through): a crack close
 * by, a deep thump from far off. Without EFX (Camafy logs it once) it is only quieter and deeper with distance.
 */
@Mod.EventBusSubscriber(modid = FullStop.MODID, value = Dist.CLIENT)
public final class SonicBoomSounds {

    private static final ResourceLocation OWNER = new ResourceLocation(FullStop.MODID, "sonic_boom");
    /** Closer than this (blocks) a boom is at full loudness and unfiltered. */
    private static final double NEAR = 48.0;

    private static int played;
    private static float lastGain = Float.NaN, lastGainHf = Float.NaN;

    private SonicBoomSounds() {
    }

    /** The boom's loudness heard {@code distance} blocks from where its wave came from. */
    public static float gain(double distance, double range) {
        if (distance <= NEAR) return 1.0f;
        double t = Mth.clamp((distance - NEAR) / Math.max(1.0, range - NEAR), 0.0, 1.0);
        return (float) (1.0 - 0.55 * Math.pow(t, 0.8));
    }

    /** The boom's high-frequency gain (1: a crack, toward 0: a thump) heard {@code distance} blocks off. */
    public static float gainHf(double distance, double range) {
        if (distance <= NEAR * 0.5) return 1.0f;
        double t = Mth.clamp((distance - NEAR * 0.5) / Math.max(1.0, range - NEAR * 0.5), 0.0, 1.0);
        return (float) Mth.clamp(Math.pow(1.0 - t, 2.2), 0.03, 1.0);
    }

    /** A boom reached this listener. */
    public static void on(SonicBoomPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        double far = Mth.clamp(packet.distance() / Math.max(1.0f, packet.range()), 0.0, 1.0);
        float gain = gain(packet.distance(), packet.range());
        float gainHf = gainHf(packet.distance(), packet.range());
        mc.getSoundManager().play(new Boom(SoundEvents.GENERIC_EXPLODE, packet, gain, packet.pitch(), gainHf, packet.seed()));
        mc.getSoundManager().play(new Boom(SoundEvents.WARDEN_SONIC_BOOM, packet, gain * 0.9f, (float) (2.0 - 0.6 * far), gainHf,
                packet.seed() ^ 0x5DEECE66DL));
        played++;
        lastGain = gain;
        lastGainHf = gainHf;
    }

    /** Booms played since the client started, and the latest one's gain and high-frequency gain (for the harness). */
    public static int played() {
        return played;
    }

    public static float lastGain() {
        return lastGain;
    }

    public static float lastGainHf() {
        return lastGainHf;
    }

    /** On the sound thread as a boom's source starts: its low-pass, in its first sample. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onSourceStarted(PlaySoundSourceEvent event) {
        if (event.getSound() instanceof Boom boom && boom.gainHf < 0.999f) {
            AudioEffects.setChannelLowPass(event.getChannel(), OWNER, 1.0f, boom.gainHf);
        }
    }

    /** One of a boom's two sounds: placed where its wave came from, loud by {@link #gain}, never faded by the engine. */
    private static final class Boom extends AbstractSoundInstance {
        final float gainHf;

        Boom(SoundEvent sound, SonicBoomPacket packet, float volume, float pitch, float gainHf, long seed) {
            super(sound, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
            this.random.setSeed(seed);
            this.volume = Mth.clamp(volume, 0.0f, 1.0f);
            this.pitch = pitch;
            this.x = packet.x();
            this.y = packet.y();
            this.z = packet.z();
            this.attenuation = Attenuation.NONE;
            this.relative = false;
            this.gainHf = gainHf;
        }
    }
}
