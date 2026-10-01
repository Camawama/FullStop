package net.camacraft.fullstop.client.sound;

import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.network.ShockTrainPacket;
import net.camacraft.fullstop.common.registry.ModSounds;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The shock train of a supersonic rotor, on this client: one {@code ship.shock_crack} per shock, played at
 * the rotor's own cadence - the server says how many shocks a second sweep this listener ({@link ShockTrainPacket}:
 * turns a second times supersonic tips) and how fast the tips go (Mach), and this plays the one-shot that often,
 * from where the ship is drawn right now. No loop: speeding up or slowing down is heard as the cracks closing up
 * or spreading out, exactly in step with the spin (a two-tip rotor at 256 RPM: a crack every 118 ms).
 *
 * <p>The cadence is kept by a fractional accumulator stepped every render frame by real time, so it is exact over
 * any number of frames and ticks, whatever the frame rate. Past {@link #BLUR_RATE} shocks a second (where separate
 * cracks blur into a tone) no cracks are skipped: one plays every tick ({@link #MAX_RATE}) at a pitch that keeps
 * rising with the true rate instead. Pitch and loudness also follow the tips' Mach number: just over the barrier
 * the crack is a deep thud, well past it a sharper, louder snap ({@link #pitch}, {@link #volume}).</p>
 *
 * <p>A train the server stops telling us about ({@link #LOST_TICKS}: the ship unloaded, the server gone) ends by
 * itself. Nothing here needs the sound engine to keep a channel: each crack is its own short sound, so a crack the
 * engine refuses (master volume off, too far) costs nothing and the next is tried at its own time.</p>
 */
@Mod.EventBusSubscriber(modid = FullStop.MODID, value = Dist.CLIENT)
public final class ShockTrainSounds {

    /** Shocks a second beyond which the cracks blur into a tone: one crack a tick from here on, pitched up by the true rate. */
    public static final double BLUR_RATE = 15.0;
    /** The most cracks a second ever played (one a tick). */
    public static final double MAX_RATE = 20.0;
    /** Without word from the server for this long (ticks) a train ends. */
    private static final int LOST_TICKS = 60;
    /** How many of the latest crack intervals the harness's measure averages. */
    private static final int MEASURED = 16;

    private static final Map<Long, Train> TRAINS = new HashMap<>();
    private static long lastFrameMillis = -1L;
    private static int cracksPlayed;

    private ShockTrainSounds() {
    }

    /** A start, an update or a stop from the server. */
    public static void on(ShockTrainPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        Train train = TRAINS.get(packet.sourceId());
        if (!packet.active()) {
            if (train != null) TRAINS.remove(packet.sourceId());
            return;
        }
        if (train == null) {
            train = new Train(packet.sourceId(), packet.ship());
            TRAINS.put(packet.sourceId(), train);
        }
        train.update(packet, now);
    }

    /** How many trains are cracking right now (for the harness and tests). */
    public static int playing() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            TRAINS.clear();
            return 0;
        }
        long now = mc.level.getGameTime();
        TRAINS.values().removeIf(train -> train.lost(now));
        return TRAINS.size();
    }

    /** Cracks played since the client started (for the harness). */
    public static int cracksPlayed() {
        return cracksPlayed;
    }

    /** The mean of the latest crack intervals (ms) of any train, or 0 with fewer than two cracks (for the harness). */
    public static double measuredIntervalMillis() {
        double sum = 0.0;
        int n = 0;
        for (Train train : TRAINS.values()) {
            for (long gap : train.gaps) {
                sum += gap;
                n++;
            }
        }
        return n == 0 ? 0.0 : sum / n;
    }

    /** The interval (ms) the server's rate asks for, of the fastest train, or 0 with none (for the harness). */
    public static double expectedIntervalMillis() {
        double best = 0.0;
        for (Train train : TRAINS.values()) {
            double rate = Math.min(train.rate, MAX_RATE);
            if (rate > best) best = rate;
        }
        return best <= 0.0 ? 0.0 : 1000.0 / best;
    }

    /** Every render frame: step each train's accumulator by real time and play the cracks that fall due. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        long millis = Util.getMillis();
        long dt = lastFrameMillis < 0 ? 0 : Math.min(250L, millis - lastFrameMillis);
        lastFrameMillis = millis;
        if (TRAINS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            TRAINS.clear();
            return;
        }
        if (mc.isPaused()) return;
        long now = mc.level.getGameTime();
        Iterator<Train> it = TRAINS.values().iterator();
        while (it.hasNext()) {
            Train train = it.next();
            if (train.lost(now)) {
                it.remove();
                continue;
            }
            train.step(mc, dt, millis);
        }
    }

    /**
     * The crack's pitch for the tips' Mach number and the train's true rate: a deep thud just over the barrier
     * (0.8), rising to a snap at Mach 2 (1.2); past {@link #BLUR_RATE} shocks a second it keeps rising with the rate
     * the one-a-tick cracks can no longer keep (the tone of the roar).
     */
    static float pitch(double mach, double rate) {
        double pitch = 0.8 + 0.4 * Mth.clamp(mach - 1.0, 0.0, 1.5);
        if (rate > BLUR_RATE) pitch *= rate / BLUR_RATE;
        return (float) Mth.clamp(pitch, 0.5, 2.0);
    }

    /** The crack's gain: the server's loudness, a little less right at the barrier, full from Mach 1.5 on. */
    static float volume(double loudness, double mach) {
        return (float) Mth.clamp(loudness * (0.75 + 0.5 * Mth.clamp(mach - 1.0, 0.0, 0.5)), 0.0, 1.0);
    }

    private static final class Train {
        private final long id;
        private final boolean ship;
        private long lastPacket;
        private double rate;
        private double mach = 1.0;
        private float loudness;
        private Vec3 at = Vec3.ZERO;
        /** Shocks due so far: a crack plays each time it passes one. Starts at one so the first crack is immediate. */
        private double due = 1.0;
        private long lastCrackMillis = -1L;
        /** The latest intervals between cracks (ms), for the harness. */
        private final ArrayDeque<Long> gaps = new ArrayDeque<>(MEASURED);

        Train(long id, boolean ship) {
            this.id = id;
            this.ship = ship;
        }

        void update(ShockTrainPacket packet, long now) {
            lastPacket = now;
            rate = Math.max(0.0, packet.pulsesPerSecond());
            mach = Math.max(0.0, packet.mach());
            loudness = Mth.clamp(packet.loudness(), 0.0f, 1.0f);
            at = new Vec3(packet.x(), packet.y(), packet.z());
        }

        boolean lost(long now) {
            return now - lastPacket > LOST_TICKS || rate <= 0.0;
        }

        void step(Minecraft mc, long dtMillis, long millis) {
            if (ship) {
                // where this client has the ship: the cracks come from it between the server's words
                ShipRef ref = ShipRef.of(mc.level, id);
                Vec3 centre = ref == null ? null : ref.position();
                if (centre != null) at = centre;
            }
            // the cadence played: the rotor's own, or one a tick past the blur (the pitch carries the rest)
            double played = rate > BLUR_RATE ? MAX_RATE : rate;
            due += played * dtMillis / 1000.0;
            if (due < 1.0) return;
            // one crack a frame at most: a frame late on a slow client is caught up, never doubled into a click
            due = Math.min(due - 1.0, 1.0);
            SoundInstance crack = new SimpleSoundInstance(ModSounds.SHIP_SHOCK_CRACK.get(), SoundSource.BLOCKS,
                    volume(loudness, mach), pitch(mach, rate), SoundInstance.createUnseededRandom(), at.x, at.y, at.z);
            mc.getSoundManager().play(crack);
            cracksPlayed++;
            if (lastCrackMillis >= 0) {
                if (gaps.size() >= MEASURED) gaps.pollFirst();
                gaps.addLast(millis - lastCrackMillis);
            }
            lastCrackMillis = millis;
        }
    }
}
