package net.camacraft.fullstop.common.handler;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.network.GForcePacket;
import net.camacraft.fullstop.common.network.PlayerDeltaPacket;
import net.camacraft.fullstop.common.network.ShipContactPacket;
import net.camacraft.fullstop.common.network.ShipReleasePacket;
import net.camacraft.fullstop.common.network.ShockTrainPacket;
import net.camacraft.fullstop.common.network.VehicleMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class PacketHandler {
    // 4: PlayerDeltaPacket carries the client-side discontinuity flag.
    // 5: VehicleMotionPacket carries a delta flag (a velocity change for a driven vehicle).
    // 6: PlayerDeltaPacket carries the client's ship carry and hull shove; ShipContactPacket
    //    (a hull struck the local player, as its client saw it); VehicleMotionPacket's delta
    //    mode also reaches the local player itself; GForcePacket (a seated rider's g on a ship).
    // 7: ShipReleasePacket (the client let go of a ship's deck until it lands).
    // 8: ShockTrainPacket (the continuous shock train of a supersonic rotor, to its listeners).
    // 9: ShockTrainPacket carries the tips' Mach number too (the cracks' pitch and loudness).
    private static final String PROTOCOL_VERSION = "9";
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

        INSTANCE.messageBuilder(ShipContactPacket.class, messageID++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ShipContactPacket::encode)
                .decoder(ShipContactPacket::new)
                .consumerMainThread(ShipContactPacket::handle)
                .add();

        INSTANCE.messageBuilder(GForcePacket.class, messageID++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(GForcePacket::encode)
                .decoder(GForcePacket::new)
                .consumerMainThread(GForcePacket::handle)
                .add();

        INSTANCE.messageBuilder(ShipReleasePacket.class, messageID++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ShipReleasePacket::encode)
                .decoder(ShipReleasePacket::new)
                .consumerMainThread(ShipReleasePacket::handle)
                .add();

        INSTANCE.messageBuilder(ShockTrainPacket.class, messageID++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ShockTrainPacket::encode)
                .decoder(ShockTrainPacket::new)
                .consumerMainThread(ShockTrainPacket::handle)
                .add();
    }

    public static void sendToServer(ShipReleasePacket msg) {
        INSTANCE.sendToServer(msg);
    }

    public static void sendToServer(ShipContactPacket msg) {
        INSTANCE.sendToServer(msg);
    }

    /**
     * Sends a velocity CHANGE (world axes, blocks per tick) to a player's own client, which adds
     * it to the player's motion there. For a player FullStop can't build an absolute velocity on
     * the server: the server's reading of a player's motion comes from its client's move reports
     * and is a tick behind, and near moving ships its own ship poses don't match what the client
     * drew. A change added where the motion is simulated is exact.
     */
    public static void sendPlayerImpulse(ServerPlayer player, Vec3 deltaBpt) {
        sendToPlayer(player, new VehicleMotionPacket(player.getId(), deltaBpt, true));
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

    /**
     * Sends a velocity CHANGE (world axes, blocks per tick) for a vehicle to the player driving
     * it: that client simulates the vehicle and adds the change through Camafy's Impulses (see
     * {@code BodyPush}).
     */
    public static void sendVehicleImpulse(ServerPlayer driver, Entity vehicle, Vec3 deltaBpt) {
        sendToPlayer(driver, new VehicleMotionPacket(vehicle.getId(), deltaBpt, true));
    }

    /** Vanilla's motion packet clamps each axis to this many blocks a tick (78 m/s). */
    private static final double VANILLA_MOTION_PACKET_LIMIT = 3.9;

    /**
     * Delivers a launch the server just gave a player (setDeltaMovement + hurtMarked) at its real
     * speed. Vanilla sends it as a ClientboundSetEntityMotionPacket, which clamps every axis to
     * 3.9 blocks a tick, so a player hit by an 80 m/s ship would leave at 78 m/s at most. Past
     * that limit the exact motion goes in FullStop's own packet instead, and hurtMarked is
     * cleared so the clamped copy (sent when the entity tracker next runs) can't overwrite it.
     * Other clients follow the player through its position updates either way.
     */
    public static void syncLaunchToPlayer(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return;
        Vec3 motion = player.getDeltaMovement();
        if (Math.abs(motion.x) <= VANILLA_MOTION_PACKET_LIMIT && Math.abs(motion.y) <= VANILLA_MOTION_PACKET_LIMIT
                && Math.abs(motion.z) <= VANILLA_MOTION_PACKET_LIMIT) {
            return;
        }
        sendToPlayer(player, new VehicleMotionPacket(player.getId(), motion));
        player.hurtMarked = false;
    }
}
