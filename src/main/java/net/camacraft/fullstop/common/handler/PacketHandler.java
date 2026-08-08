package net.camacraft.fullstop.common.handler;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.network.PlayerDeltaPacket;
import net.camacraft.fullstop.common.network.VehicleMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class PacketHandler {
    private static final String PROTOCOL_VERSION = "3";
    private static int messageID = 0;

    private static final SimpleChannel INSTANCE = NetworkRegistry.ChannelBuilder.named(
                    new ResourceLocation(FullStop.MODID, "main"))
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .simpleChannel();

    public static void register() {
        INSTANCE.messageBuilder(PlayerDeltaPacket.class, messageID++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PlayerDeltaPacket::encode)
                .decoder(PlayerDeltaPacket::new)
                .consumerMainThread(PlayerDeltaPacket::handle)
                .add();

        INSTANCE.messageBuilder(VehicleMotionPacket.class, messageID++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VehicleMotionPacket::encode)
                .decoder(VehicleMotionPacket::new)
                .consumerMainThread(VehicleMotionPacket::handle)
                .add();
    }

    public static void sendToServer(PlayerDeltaPacket msg) {
        INSTANCE.sendToServer(msg);
    }

    public static void sendToPlayer(ServerPlayer player, Object msg) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /**
     * Syncs a motion change the server just made on a player-controlled vehicle
     * to its driver. Client-authoritative vehicles (boats above all: the server
     * Boat.tick zeroes its own deltaMovement every tick) never reliably receive
     * server-side setDeltaMovement through the vanilla hurtMarked path — this is
     * what made a ridden boat land dead on slime while an empty one bounced.
     * Call after setDeltaMovement whenever the entity may be a driven vehicle.
     */
    public static void syncMotionToControllingDriver(Entity entity) {
        if (entity.getControllingPassenger() instanceof ServerPlayer driver) {
            sendToPlayer(driver, new VehicleMotionPacket(entity.getId(), entity.getDeltaMovement()));
        }
    }
}
