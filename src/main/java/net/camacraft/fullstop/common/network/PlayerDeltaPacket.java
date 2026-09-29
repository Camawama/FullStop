package net.camacraft.fullstop.common.network;

import net.camacraft.camafy.physics.PhysicsOwnership;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

import static net.camacraft.fullstop.common.capability.FullStopCapability.Provider.DELTAV_CAP;

/**
 * Client → server: the player's (or their vehicle's) deltaMovement for this tick.
 * The flag makes the target explicit, so a packet sent while riding can't be
 * applied to the player after a dismount race (or vice versa).
 *
 * The value only ever REFINES the server's own measurement — the capability
 * discards it whenever it exceeds the measured position delta.
 *
 * It is also the server's CLOCK for the client: one of these arrives per
 * client tick, moved or not, always ahead of that tick's move packet, so the
 * capability can measure per client tick instead of per server tick (see the
 * sampling notes in FullStopCapability).
 */
public class PlayerDeltaPacket {
    /** Sanity bound: 100 blocks/tick (2000 m/s). Real anti-inflation happens in the capability. */
    private static final double MAX_CLAIMED_SPEED_SQR = 100 * 100;

    private final Vec3 delta;
    private final boolean forVehicle;
    // The client's own collision flags. The server cannot measure these for
    // players — move packets arrive already clipped to the wall, so the server's
    // move() sees a tiny free move and never sets horizontalCollision. Without
    // this report the kinetic-damage evidence gate can never pass horizontally
    // (which silently cancelled ALL wall damage for players).
    private final boolean horizontalCollision;
    private final boolean verticalCollision;
    // Another mod moved the target on the CLIENT in a way that isn't real
    // velocity (Camafy's PhysicsOwnership.markDiscontinuity: a Gravity Unbound
    // surface-cling release step, say). The server never sees client-side
    // marks, and it measures players from exactly these client ticks, so the
    // mark is carried over and re-made on the server copy.
    private final boolean discontinuity;
    // What the client's Valkyrien Skies added to this tick's position (m/s, world): the carry of
    // the deck the mover stands on, and the shove of a hull that ran into it. The positions the
    // server measures the mover by come from this client, drawn with ITS ship poses (which run
    // behind the server's), so the server takes these out instead of its own readings.
    private final Vec3 carryMps;
    private final Vec3 shoveMps;

    public PlayerDeltaPacket(Vec3 delta, boolean forVehicle, boolean horizontalCollision, boolean verticalCollision,
                             boolean discontinuity) {
        this(delta, forVehicle, horizontalCollision, verticalCollision, discontinuity, Vec3.ZERO, Vec3.ZERO);
    }

    public PlayerDeltaPacket(Vec3 delta, boolean forVehicle, boolean horizontalCollision, boolean verticalCollision,
                             boolean discontinuity, Vec3 carryMps, Vec3 shoveMps) {
        this.delta = delta;
        this.forVehicle = forVehicle;
        this.horizontalCollision = horizontalCollision;
        this.verticalCollision = verticalCollision;
        this.discontinuity = discontinuity;
        this.carryMps = carryMps;
        this.shoveMps = shoveMps;
    }

    public PlayerDeltaPacket(FriendlyByteBuf buffer) {
        this(new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeDouble(this.delta.x);
        buffer.writeDouble(this.delta.y);
        buffer.writeDouble(this.delta.z);
        buffer.writeBoolean(this.forVehicle);
        buffer.writeBoolean(this.horizontalCollision);
        buffer.writeBoolean(this.verticalCollision);
        buffer.writeBoolean(this.discontinuity);
        buffer.writeDouble(this.carryMps.x);
        buffer.writeDouble(this.carryMps.y);
        buffer.writeDouble(this.carryMps.z);
        buffer.writeDouble(this.shoveMps.x);
        buffer.writeDouble(this.shoveMps.y);
        buffer.writeDouble(this.shoveMps.z);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        // Registered with consumerMainThread, so this already runs on the server
        // thread — no enqueueWork needed.
        ServerPlayer sendingPlayer = ctx.get().getSender();
        if (sendingPlayer == null) {
            ctx.get().setPacketHandled(true);
            return;
        }

        // NaN/Infinity would sail through every magnitude comparison (NaN > x is
        // always false) and poison the server-side velocity state permanently.
        if (!Double.isFinite(delta.x) || !Double.isFinite(delta.y) || !Double.isFinite(delta.z)
                || delta.lengthSqr() > MAX_CLAIMED_SPEED_SQR || !finite(carryMps) || !finite(shoveMps)
                || carryMps.lengthSqr() > MAX_CLAIMED_SPEED_SQR * 400 || shoveMps.lengthSqr() > MAX_CLAIMED_SPEED_SQR * 400) {
            ctx.get().setPacketHandled(true);
            return;
        }

        if (forVehicle) {
            if (sendingPlayer.isPassenger()) {
                Entity vehicle = sendingPlayer.getVehicle();
                // Only the driver may report the vehicle's velocity, and only for a
                // vehicle its client actually moves: whatever it controls (horses,
                // boats, modded vehicles such as MKW's karts) but a rail-bound cart.
                if (vehicle != null && vehicle.getControllingPassenger() == sendingPlayer
                        && !(vehicle instanceof AbstractMinecart)) {
                    if (this.discontinuity) {
                        PhysicsOwnership.markDiscontinuity(vehicle);
                    }
                    // one client tick of the vehicle's motion: opens the sample the
                    // driver's move-vehicle packet completes (see FullStopCapability)
                    vehicle.getCapability(DELTAV_CAP)
                            .ifPresent(cap -> cap.recordClientDelta(this.delta, this.horizontalCollision, this.verticalCollision,
                                    this.carryMps, this.shoveMps));
                }
            }
        } else if (!sendingPlayer.isPassenger()) {
            if (this.discontinuity) {
                PhysicsOwnership.markDiscontinuity(sendingPlayer);
            }
            logShipMotion(sendingPlayer);
            sendingPlayer.getCapability(DELTAV_CAP)
                    .ifPresent(cap -> cap.recordClientDelta(this.delta, this.horizontalCollision, this.verticalCollision,
                            this.carryMps, this.shoveMps));
        }
        ctx.get().setPacketHandled(true);
    }

    /** Last game time a player's ship motion was logged (debugShipContactLog: once a second). */
    private static final java.util.Map<ServerPlayer, Long> LOGGED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * debugShipContactLog: while a ship carries or shoves the player, once a second, what its
     * client measured against what the server's own ship poses say - the gap is how far apart
     * the two sides' views of the ship are.
     */
    private void logShipMotion(ServerPlayer player) {
        if (!net.camacraft.fullstop.FullStopConfig.SERVER_SPEC.isLoaded()
                || !net.camacraft.fullstop.FullStopConfig.SERVER.debugShipContactLog.get()) return;
        if (carryMps.lengthSqr() < 1.0e-6 && shoveMps.lengthSqr() < 1.0e-6) return;
        long now = player.level().getGameTime();
        Long last = LOGGED.get(player);
        if (last != null && now - last < 20 && shoveMps.lengthSqr() < 1.0e-6) return;
        LOGGED.put(player, now);
        Vec3 server = net.camacraft.fullstop.common.compat.ShipCompat.shipDragVelocityMps(player);
        org.slf4j.LoggerFactory.getLogger(PlayerDeltaPacket.class).info(
                "[FullStop] {}'s client: ship carry ({}) m/s{} - the server's own ship pose says ({}) m/s; its motion is "
                        + "measured with the client's", player.getName().getString(), fmt(carryMps),
                shoveMps.lengthSqr() < 1.0e-6 ? "" : ", hull shove (" + fmt(shoveMps) + ") m/s", fmt(server));
    }

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
