package net.camacraft.fullstop.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

import static net.camacraft.fullstop.common.capabilities.FullStopCapability.Provider.DELTAV_CAP;

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
            // Work that needs to be thread-safe (most work)
            ServerPlayer sendingPlayer = ctx.get().getSender(); // the client that sent this packet

            if (sendingPlayer.isPassenger()) {
                sendingPlayer.getVehicle().getCapability(DELTAV_CAP)
                        .ifPresent(delta -> delta.setCurrentNativeVelocity(this.playerDelta));
            } else {
                sendingPlayer.getCapability(DELTAV_CAP)
                        .ifPresent(delta -> delta.setCurrentNativeVelocity(this.playerDelta));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
