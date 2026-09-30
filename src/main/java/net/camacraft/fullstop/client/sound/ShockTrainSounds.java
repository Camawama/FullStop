package net.camacraft.fullstop.client.sound;

import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.fullstop.common.network.ShockTrainPacket;
import net.camacraft.fullstop.common.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * The continuous shock train of a supersonic rotor, on this client: one looping positional sound
 * per source the server says is supersonic ({@link ShockTrainPacket}), following the ship as this
 * client draws it, its pitch the rotor's pulse rate over the loop's own ({@link #BASE_RATE}: a
 * two-tip rotor at 256 RPM pulses 8.5 times a second and plays at a pitch of 1.07; a single 16-block
 * arm at 4.3 a second at 0.53, deeper), fading in and out over half a second. A train the server
 * stops telling us about ({@link #LOST_TICKS}: the ship unloaded, the server gone) fades out too.
 */
public final class ShockTrainSounds {

    /** The loop's own pulse rate (pulses per second), as {@code tools/gen_sounds.py} made it. */
    public static final double BASE_RATE = 8.0;
    /** Without word from the server for this long (ticks) a train fades out. */
    private static final int LOST_TICKS = 60;
    /** The volume's share of its target gained or lost per tick: half a second in or out. */
    private static final float FADE = 0.1f;

    private static final Map<Long, Train> TRAINS = new HashMap<>();

    private ShockTrainSounds() {
    }

    /** A start, an update or a stop from the server. */
    public static void on(ShockTrainPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        Train train = TRAINS.get(packet.sourceId());
        if (!packet.active()) {
            if (train != null) train.stopping = true;
            return;
        }
        if (train == null || train.isStopped()) {
            train = new Train(packet, now);
            TRAINS.put(packet.sourceId(), train);
            mc.getSoundManager().play(train);
        } else {
            train.update(packet, now);
        }
    }

    /** How many trains are playing (for the harness and tests). */
    public static int playing() {
        TRAINS.values().removeIf(AbstractTickableSoundInstance::isStopped);
        return TRAINS.size();
    }

    private static final class Train extends AbstractTickableSoundInstance {
        private final long id;
        private final boolean ship;
        private long lastPacket;
        private float targetVolume;
        private float targetPitch;
        private Vec3 at;
        private boolean stopping;

        Train(ShockTrainPacket packet, long now) {
            super(ModSounds.SHIP_SHOCK_TRAIN.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.id = packet.sourceId();
            this.ship = packet.ship();
            this.looping = true;
            this.delay = 0;
            this.attenuation = Attenuation.LINEAR;
            this.relative = false;
            this.volume = 0.0f;
            update(packet, now);
            this.pitch = targetPitch;
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
        }

        void update(ShockTrainPacket packet, long now) {
            lastPacket = now;
            at = new Vec3(packet.x(), packet.y(), packet.z());
            targetVolume = Math.max(0.0f, packet.loudness());
            targetPitch = (float) Mth.clamp(packet.pulsesPerSecond() / BASE_RATE, 0.5, 2.0);
            stopping = false;
        }

        @Override
        public void tick() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                stop();
                TRAINS.remove(id);
                return;
            }
            long now = mc.level.getGameTime();
            float step = FADE * Math.max(targetVolume, 1.0f);
            if (stopping || now - lastPacket > LOST_TICKS) {
                volume = Math.max(0.0f, volume - step);
                if (volume <= 0.0f) {
                    stop();
                    TRAINS.remove(id);
                    return;
                }
            } else {
                volume = Math.min(targetVolume, volume + step);
            }
            pitch += (targetPitch - pitch) * 0.2f;
            if (ship) {
                // where this client has the ship: the sound rides it between the server's words
                ShipRef ref = ShipRef.of(mc.level, id);
                Vec3 centre = ref == null ? null : ref.position();
                if (centre != null) at = centre;
            }
            x = at.x;
            y = at.y;
            z = at.z;
        }
    }
}
