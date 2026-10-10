package net.camacraft.fullstop.client.physics;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.compat.ShipHolds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * The local player while Valkyrien Skies holds it in place ({@link ShipHolds}: it touches a ship that isn't loaded for
 * it yet, most often one made a moment ago: Shipify, a toolgun, an assembler). Valkyrien Skies holds it two ways:
 * <ul>
 *   <li><b>Its server</b> refuses the player's moves and sends the player back where the server has it ("moved while
 *   colliding with unloaded ships!"; the owner's log had 8 to 12 of them at every ship made). Vanilla takes a position
 *   from the server as a stop: the player's motion went to nothing. A player bouncing on slime that was made a ship a
 *   moment before was put back where it touched, bounce gone (the owner: "my player gets stuck at the point of contact
 *   with the slime"; harness {@code fsb_slime_fall} with {@code fsbFreshLead}: every such bounce cut, apex 0.23 blocks of
 *   6.33). Full Stop's server says when a position it sends is that hold ({@code ShipHoldPacket}); the player is then
 *   given back the motion it had when it was last where the server put it (its last few ticks are remembered), and
 *   held still until the server stops refusing, so the landing (and the bounce) happens again from there.</li>
 *   <li><b>Its client</b> cancels the player's moves while gravity goes on adding to its velocity: the player hung where
 *   it touched with its speed growing (WallJump Unbound's falling wind playing), then went into the deck at that speed
 *   or, set back by the server meanwhile, at none. Here the velocity and fall distance it came with are put aside while
 *   the hold lasts (the player hangs still and silent) and given back the tick it ends.</li>
 * </ul>
 * {@code -Dfullstop.shipHoldKeepsMotion=false} turns both off.
 */
@Mod.EventBusSubscriber(modid = FullStop.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientShipHold {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Read each tick (the harness switches it for its before/after). */
    public static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty("fullstop.shipHoldKeepsMotion"));

    /** Ticks a hold by the server lasts after its last refused move (it refuses each move it is sent). */
    private static final int SERVER_HOLD_TICKS = 3;
    /** Ticks of the player's own past remembered (where it was, and how it moved from there). */
    private static final int HISTORY = 20;
    /** How close (blocks) a remembered place must be to where the server put the player to be that place. */
    private static final double SAME_PLACE = 0.5;

    private record Moment(Vec3 pos, Vec3 velocity, float fallDistance) {}

    private static final Moment[] PAST = new Moment[HISTORY];
    private static int pastHead;

    private static Vec3 kept;
    private static float keptFall;
    private static int heldTicks;
    /** Client ticks left of a hold by the server; whether its position has yet to be matched to the player's past. */
    private static int serverHoldLeft;
    private static boolean serverHoldFresh;
    /** Ticks the last hold lasted, how many holds there were and how many of them were the server's (for the harness). */
    private static int lastHoldTicks;
    private static int holds;
    private static int serverHolds;

    private ClientShipHold() {
    }

    public static int lastHoldTicks() {
        return lastHoldTicks;
    }

    public static int holds() {
        return holds;
    }

    public static int serverHolds() {
        return serverHolds;
    }

    /** Valkyrien Skies' server refused a move: the position it sends next puts the player back ({@code ShipHoldPacket}). */
    public static void heldByServer() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (serverHoldLeft <= 0) serverHolds++;
        serverHoldLeft = SERVER_HOLD_TICKS;
        serverHoldFresh = true;
        if (kept == null) {
            // until the position comes and is matched to the past: the motion as it is now
            kept = mc.player.getDeltaMovement();
            keptFall = mc.player.fallDistance;
            heldTicks = 0;
            holds++;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !enabled || player.isPassenger() || player.isSpectator()) {
            kept = null;
            serverHoldLeft = 0;
            java.util.Arrays.fill(PAST, null);
            return;
        }
        if (mc.isPaused()) return;
        if (serverHoldFresh) {
            serverHoldFresh = false;
            // the server put the player back where it was some ticks ago: the motion it had there
            Moment then = nearest(player.position());
            if (then != null) {
                kept = then.velocity();
                keptFall = then.fallDistance();
            }
        }
        boolean heldHere = ShipHolds.heldForUnloadedShip(player);
        if (heldHere || serverHoldLeft > 0) {
            if (serverHoldLeft > 0) serverHoldLeft--;
            if (kept == null) {
                kept = player.getDeltaMovement();
                keptFall = player.fallDistance;
                heldTicks = 0;
                holds++;
            }
            heldTicks++;
            player.setDeltaMovement(Vec3.ZERO);
            return;
        }
        if (kept != null) {
            player.setDeltaMovement(kept);
            player.fallDistance = keptFall;
            lastHoldTicks = heldTicks;
            if (FullStopConfig.SERVER_SPEC.isLoaded() && FullStopConfig.SERVER.debugImpactLog.get()) {
                LOGGER.info("[FullStop] client: Valkyrien Skies held you {} ticks at a ship still loading; your motion ({}, {}, {}) m/s is given back",
                        heldTicks, fmt(kept.x * 20.0), fmt(kept.y * 20.0), fmt(kept.z * 20.0));
            }
            kept = null;
        }
        PAST[pastHead] = new Moment(player.position(), player.getDeltaMovement(), player.fallDistance);
        pastHead = (pastHead + 1) % HISTORY;
    }

    /**
     * The remembered moment the player was nearest {@code pos} (the latest of equals), when within {@link #SAME_PLACE};
     * null for none. The server puts the player back exactly where one of its moves began.
     */
    private static Moment nearest(Vec3 pos) {
        Moment best = null;
        double bestSqr = SAME_PLACE * SAME_PLACE;
        for (int i = 1; i <= HISTORY; i++) {
            Moment m = PAST[Math.floorMod(pastHead - i, HISTORY)];
            if (m == null) continue;
            double d = m.pos().distanceToSqr(pos);
            if (d < bestSqr - 1.0E-12) {
                best = m;
                bestSqr = d;
            }
        }
        return best;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
