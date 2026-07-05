package net.camacraft.fullstop.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Boat;
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
 */
public class PlayerDeltaPacket {
    /** Sanity bound: 100 blocks/tick (2000 m/s). Real anti-inflation happens in the capability. */
    private static final double MAX_CLAIMED_SPEED_SQR = 100 * 100;

    private final Vec3 delta;
    private final boolean forVehicle;

    public PlayerDeltaPacket(Vec3 delta, boolean forVehicle) {
        this.delta = delta;
        this.forVehicle = forVehicle;
    }

    public PlayerDeltaPacket(FriendlyByteBuf buffer) {
        this(new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readBoolean());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeDouble(this.delta.x);
        buffer.writeDouble(this.delta.y);
        buffer.writeDouble(this.delta.z);
        buffer.writeBoolean(this.forVehicle);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sendingPlayer = ctx.get().getSender();
            if (sendingPlayer == null) return;

            if (delta.lengthSqr() > MAX_CLAIMED_SPEED_SQR) {
                return;
            }

            if (forVehicle) {
                if (!sendingPlayer.isPassenger()) return;

                Entity vehicle = sendingPlayer.getVehicle();
                // Only the driver may report the vehicle's velocity, and only for
                // vehicle types the client actually controls (not rail-bound carts).
                if (vehicle != null && vehicle.getControllingPassenger() == sendingPlayer
                        && (vehicle instanceof LivingEntity || vehicle instanceof Boat)) {
                    vehicle.getCapability(DELTAV_CAP)
                            .ifPresent(cap -> cap.setCurrentNativeVelocity(this.delta));
                }
            } else {
                if (sendingPlayer.isPassenger()) return;

                sendingPlayer.getCapability(DELTAV_CAP)
                        .ifPresent(cap -> cap.setCurrentNativeVelocity(this.delta));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
