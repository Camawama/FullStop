package net.camacraft.fullstop.server.physics.ship;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What the server knows for itself about a player whose client reports its contacts with hulls
 * ({@link ShipEntityImpacts#clientContact}): how fast the player has really been moving, and how many reports it has
 * sent this tick. A report is a client's word. Its own speed widened how far from a ship a report was believed
 * (a quarter of a second of travel) and how hard the ship was pushed back, and both took the client's figure, up to
 * the packet's 2000 m/s: a client standing still 500 blocks from a ship could push it, as often as it sent. Here the
 * figure is held to the speed the server saw the player move at in the last second, with room for the client being
 * ahead, and the reports to a few a tick.
 */
public final class ContactReports {
    /** Reports taken from one player in one tick: a contact per ship touched, and a late packet or two. */
    static final int PER_TICK = 6;
    /** A tick's travel beyond this (blocks) is no speed but a teleport, and counts for nothing. */
    private static final double MAX_SAMPLE = 20.0;
    /** How much of the fastest recent tick is kept each tick: a second on, a sprint's worth is gone. */
    private static final double DECAY = 0.85;
    /** The client's own speed may be this much above the server's measure (it is a tick or more ahead), plus {@link #ROOM}. */
    private static final double AHEAD = 1.5;
    private static final double ROOM = 10.0;

    private static final class Seen {
        Vec3 last;
        double peakBlocksPerTick;
        long tick = Long.MIN_VALUE;
        int reports;
    }

    private static final Map<ServerPlayer, Seen> SEEN = Collections.synchronizedMap(new WeakHashMap<>());

    private ContactReports() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        Seen seen = SEEN.computeIfAbsent(player, p -> new Seen());
        Vec3 now = player.position();
        double moved = seen.last == null ? 0.0 : now.distanceTo(seen.last);
        if (moved > MAX_SAMPLE) moved = 0.0;
        seen.peakBlocksPerTick = Math.max(seen.peakBlocksPerTick * DECAY, moved);
        seen.last = now;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) SEEN.remove(player);
    }

    /** Whether another report from this player is taken this tick. */
    static boolean admit(ServerPlayer player, long gameTime) {
        Seen seen = SEEN.computeIfAbsent(player, p -> new Seen());
        if (seen.tick != gameTime) {
            seen.tick = gameTime;
            seen.reports = 0;
        }
        return seen.reports++ < PER_TICK;
    }

    /** The player's own speed (m/s) as a report may claim it: the client's figure, no more than the server saw with room. */
    static double ownMps(ServerPlayer player, double claimedMps) {
        Seen seen = SEEN.get(player);
        double measured = seen == null ? 0.0 : seen.peakBlocksPerTick * 20.0;
        return Math.min(Math.abs(claimedMps), measured * AHEAD + ROOM);
    }
}
