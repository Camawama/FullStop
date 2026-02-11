package net.camacraft.fullstop.common.network;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

import static net.camacraft.fullstop.common.capability.FullStopCapability.Provider.DELTAV_CAP;

public class PlayerDeltaPacket {
    private final Vec3 playerDelta;

    public PlayerDeltaPacket(Vec3 playerDelta) {
        this.playerDelta = playerDelta;
    }

    public PlayerDeltaPacket(FriendlyByteBuf buffer) {
        this(new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeDouble(this.playerDelta.x);
        buffer.writeDouble(this.playerDelta.y);
        buffer.writeDouble(this.playerDelta.z);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sendingPlayer = ctx.get().getSender();

            if (sendingPlayer != null) {
                // Security check: Clamp velocity to prevent massive exploits
                // 100 blocks/tick (2000 m/s) is a generous upper bound for legitimate modded movement
                if (playerDelta.lengthSqr() > 100 * 100) {
                    return;
                }

                if (sendingPlayer.isPassenger()) {
                    Entity vehicle = sendingPlayer.getVehicle();
                    // Only apply velocity to the vehicle if the player is actually driving it
                    if (vehicle != null && vehicle.getControllingPassenger() == sendingPlayer) {
                        // Additional check: Only allow if vehicle is a LivingEntity (like a horse/pig) or Boat
                        // This prevents controlling things like Minecarts which are usually on rails
                        if (vehicle instanceof LivingEntity || vehicle instanceof net.minecraft.world.entity.vehicle.Boat) {
                            vehicle.getCapability(DELTAV_CAP)
                                    .ifPresent(delta -> delta.setCurrentNativeVelocity(this.playerDelta));
                        }
                    }
                } else {
                    sendingPlayer.getCapability(DELTAV_CAP)
                            .ifPresent(delta -> delta.setCurrentNativeVelocity(this.playerDelta));
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
