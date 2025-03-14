package net.camacraft.fullstop.common.network;

import net.camacraft.fullstop.common.data.Collision;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

import static net.camacraft.fullstop.common.capabilities.FullStopCapability.Provider.DELTAV_CAP;

public class PlayerDeltaPacket {
    private final Vec3 playerDelta;
    private final Collision.CollisionType collision;

    public PlayerDeltaPacket(Vec3 playerDelta, Collision.CollisionType collision) {
        this.playerDelta = playerDelta;
        this.collision = collision;
    }

    public PlayerDeltaPacket(FriendlyByteBuf buffer) {
        this(new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readEnum(Collision.CollisionType.class));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeDouble(this.playerDelta.x);
        buffer.writeDouble(this.playerDelta.y);
        buffer.writeDouble(this.playerDelta.z);
        buffer.writeEnum(this.collision);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Work that needs to be thread-safe (most work)
            ServerPlayer sendingPlayer = ctx.get().getSender(); // the client that sent this packet

            if (sendingPlayer.isPassenger()) {
                sendingPlayer.getVehicle().getCapability(DELTAV_CAP)
                        .ifPresent(delta -> {
                            delta.setCurrentVelocity(this.playerDelta);
                            delta.putCollision(this.collision);
                        });
            } else {
                sendingPlayer.getCapability(DELTAV_CAP)
                        .ifPresent(delta -> {
                            delta.setCurrentVelocity(this.playerDelta);
                            delta.putCollision(this.collision);
                        });
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
