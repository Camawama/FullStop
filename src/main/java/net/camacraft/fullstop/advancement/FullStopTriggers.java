package net.camacraft.fullstop.advancement;

import net.camacraft.fullstop.FullStop;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * FullStop's advancement criteria, one per advancement in {@code data/fullstop/advancements}, and
 * where each fires:
 * <ul>
 *   <li>{@link #OUCH}: the first kinetic damage FullStop deals a player ({@link AdvancementEvents}).</li>
 *   <li>{@link #BOOM}: a sonic boom reached the player ({@code SonicBooms.boom}).</li>
 *   <li>{@link #THUNDERSCREECH}: ten seconds within {@link #THUNDERSCREECH_RANGE} blocks of a supersonic
 *       rotor's shock train ({@link #trainNear}, called by {@code SonicBooms.serveTrain} every tick).</li>
 *   <li>{@link #HIT_BY_A_SHIP}: a ship's hit that the player survived ({@link AdvancementEvents}).</li>
 *   <li>{@link #FLATTENED}: killed by a ship ({@link AdvancementEvents}).</li>
 *   <li>{@link #CRATER_MAKER}: the player's own impact opened a crater ({@code KineticBlockInteractions}).</li>
 *   <li>{@link #MID_AIR_COLLISION}: two projectiles the player fired met in the air ({@code ProjectileCollisions}).</li>
 *   <li>{@link #BOUNCY}: bounced off slime at {@link #BOUNCY_MPS} or more ({@code BounceHandler}).</li>
 *   <li>{@link #TERMINAL_VELOCITY}: landed from a fall at {@link #TERMINAL_VELOCITY_MPS} or more and lived
 *       ({@code KineticDamageApplier}).</li>
 *   <li>{@link #BELLY_FLOP}: hurt by a belly flop into water ({@code KineticDamageApplier}).</li>
 * </ul>
 * Registered once at common setup, on the main thread (the criteria map isn't thread-safe).
 */
public final class FullStopTriggers {
    public static final PlainTrigger OUCH = new PlainTrigger(id("ouch"));
    public static final PlainTrigger BOOM = new PlainTrigger(id("boom"));
    public static final PlainTrigger THUNDERSCREECH = new PlainTrigger(id("thunderscreech"));
    public static final PlainTrigger HIT_BY_A_SHIP = new PlainTrigger(id("hit_by_a_ship"));
    public static final PlainTrigger FLATTENED = new PlainTrigger(id("flattened"));
    public static final PlainTrigger CRATER_MAKER = new PlainTrigger(id("crater_maker"));
    public static final PlainTrigger MID_AIR_COLLISION = new PlainTrigger(id("mid_air_collision"));
    public static final PlainTrigger BOUNCY = new PlainTrigger(id("bouncy"));
    public static final PlainTrigger TERMINAL_VELOCITY = new PlainTrigger(id("terminal_velocity"));
    public static final PlainTrigger BELLY_FLOP = new PlainTrigger(id("belly_flop"));

    /** A landing at this speed (m/s, the body's own) or faster, survived, is "Terminal velocity". */
    public static final double TERMINAL_VELOCITY_MPS = 50.0;
    /** A slime bounce from this speed (m/s) or faster is "Bouncy". */
    public static final double BOUNCY_MPS = 20.0;
    /** How close (blocks) to a shock train "Thunderscreech" counts a player as standing beside it. */
    public static final double THUNDERSCREECH_RANGE = 32.0;
    public static final double THUNDERSCREECH_RANGE_SQ = THUNDERSCREECH_RANGE * THUNDERSCREECH_RANGE;
    /** How long (ticks) beside a train earns "Thunderscreech". */
    public static final int THUNDERSCREECH_TICKS = 200;
    /** A break in being beside a train longer than this (ticks) starts the count over. */
    public static final int THUNDERSCREECH_GAP_TICKS = 40;

    /** Per player: {the tick the current spell beside a train began, the last tick beside one}. */
    private static final Map<UUID, long[]> NEAR_TRAIN = new HashMap<>();
    private static boolean registered;

    private FullStopTriggers() {
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(FullStop.MODID, path);
    }

    public static void register() {
        if (registered) return;
        registered = true;
        CriteriaTriggers.register(OUCH);
        CriteriaTriggers.register(BOOM);
        CriteriaTriggers.register(THUNDERSCREECH);
        CriteriaTriggers.register(HIT_BY_A_SHIP);
        CriteriaTriggers.register(FLATTENED);
        CriteriaTriggers.register(CRATER_MAKER);
        CriteriaTriggers.register(MID_AIR_COLLISION);
        CriteriaTriggers.register(BOUNCY);
        CriteriaTriggers.register(TERMINAL_VELOCITY);
        CriteriaTriggers.register(BELLY_FLOP);
    }

    /**
     * The player is within {@link #THUNDERSCREECH_RANGE} of a playing shock train this tick ({@code now}, the
     * level's game time). Ten seconds of it without a break longer than {@link #THUNDERSCREECH_GAP_TICKS}
     * earns the advancement; the count then starts over for the next time.
     */
    public static void trainNear(ServerPlayer player, long now) {
        long[] spell;
        synchronized (NEAR_TRAIN) {
            spell = NEAR_TRAIN.computeIfAbsent(player.getUUID(), id -> new long[]{now, now});
            if (now - spell[1] > THUNDERSCREECH_GAP_TICKS || now < spell[0]) spell[0] = now;
            spell[1] = now;
            if (now - spell[0] < THUNDERSCREECH_TICKS) return;
            NEAR_TRAIN.remove(player.getUUID());
        }
        THUNDERSCREECH.trigger(player);
    }

    /** Drops a player's count beside a train (tests, and a player leaving). */
    public static void forgetTrain(ServerPlayer player) {
        synchronized (NEAR_TRAIN) {
            NEAR_TRAIN.remove(player.getUUID());
        }
    }
}
