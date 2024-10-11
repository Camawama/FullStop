package net.camacraft.velocitydamage.handler;

import net.camacraft.velocitydamage.VelocityDamage;
import net.camacraft.velocitydamage.network.PlayerDeltaPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class PacketHandler {
    private static final String PROTOCOL_VERSION = "1";

    private static final SimpleChannel INSTANCE = NetworkRegistry.ChannelBuilder.named(
                    new ResourceLocation(VelocityDamage.MOD_ID, "main"))
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .simpleChannel();

    public static void register() {
        int messageID = 0;  // You need to assign a unique ID for each packet
        INSTANCE.messageBuilder(PlayerDeltaPacket.class, messageID, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PlayerDeltaPacket::encode)
                .decoder(PlayerDeltaPacket::new)
                .consumerMainThread(PlayerDeltaPacket::handle)
                .add();
    }

    public static void sendToServer(PlayerDeltaPacket msg) {
        INSTANCE.sendToServer(msg);
    }

    // currently I only have a Client to Server handler. Might add a Server to Client if needed
}
