package net.camacraft.fullstop.client.sound;

import com.mojang.logging.LogUtils;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.fullstop.common.network.ShockTrainPacket;
import net.camacraft.fullstop.common.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * The continuous shock train of a supersonic rotor, on this client: one looping positional sound
 * per source the server says is supersonic ({@link ShockTrainPacket}), following the ship as this
 * client draws it, its pitch the rotor's pulse rate over the loop's own ({@link #BASE_RATE}: a
 * two-tip rotor at 256 RPM pulses 8.5 times a second and plays at a pitch of 1.07; a single 16-block
 * arm at 4.3 a second at 0.53, deeper), fading in and out over half a second. A train the server
 * stops telling us about ({@link #LOST_TICKS}: the ship unloaded, the server gone) fades out too.
 *
 * <p>The loop starts silent and fades in, so it must say so ({@link Train#canStartSilent}): the
 * sound engine drops any sound whose volume is zero when it starts unless it does, and it takes a
 * sound's audible range from that starting volume times the sound's attenuation distance. The
 * train's range is therefore the sound's own (192 blocks in {@code sounds.json}, the server's
 * {@code SonicBooms.TRAIN_RANGE}), not the volume's: the packet's loudness is a gain of 0..1. A
 * loop the engine refused after all (the master volume at zero, the listener out of range as it
 * started) is forgotten so the server's next word starts it again.</p>
 */
public final class ShockTrainSounds {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The loop's own pulse rate (pulses per second), as {@code tools/gen_sounds.py} made it. */
    public static final double BASE_RATE = 8.0;
    /** Without word from the server for this long (ticks) a train fades out. */
    private static final int LOST_TICKS = 60;
    /** The gain gained or lost per tick: half a second in or out. */
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
        if (train != null && !train.isStopped() && !mc.getSoundManager().isActive(train)) {
            // the engine finished or never took it: start over
            train.end();
            TRAINS.remove(packet.sourceId());
            train = null;
        }
        if (train == null || train.isStopped()) {
            train = new Train(packet, now);
            TRAINS.put(packet.sourceId(), train);
            mc.getSoundManager().play(train);
            if (!mc.getSoundManager().isActive(train)) {
                // refused (master volume off, too far as it started): the server says again in half a second
                LOGGER.debug("[FullStop] the sound engine didn't take the shock train of {} {} at {},{},{}", packet.ship() ? "ship" : "entity",
                        packet.sourceId(), packet.x(), packet.y(), packet.z());
                TRAINS.remove(packet.sourceId());
            }
        } else {
            train.update(packet, now);
        }
    }

    /** How many trains the sound engine is playing right now (for the harness and tests). */
    public static int playing() {
        Minecraft mc = Minecraft.getInstance();
        TRAINS.values().removeIf(train -> train.isStopped() || !mc.getSoundManager().isActive(train));
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

        void end() {
            stop();
        }

        /** It fades in from nothing: the engine must not drop it for starting silent. */
        @Override
        public boolean canStartSilent() {
            return true;
        }

        void update(ShockTrainPacket packet, long now) {
            lastPacket = now;
            at = new Vec3(packet.x(), packet.y(), packet.z());
            targetVolume = Mth.clamp(packet.loudness(), 0.0f, 1.0f);
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
            if (stopping || now - lastPacket > LOST_TICKS) {
                volume = Math.max(0.0f, volume - FADE);
                if (volume <= 0.0f) {
                    stop();
                    TRAINS.remove(id);
                    return;
                }
            } else {
                volume = Math.min(targetVolume, volume + FADE);
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
