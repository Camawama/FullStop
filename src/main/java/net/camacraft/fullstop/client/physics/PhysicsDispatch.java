package net.camacraft.fullstop.client.physics;

import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.network.PlayerDeltaPacket;
import net.camacraft.fullstop.common.physics.Physics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import static net.camacraft.fullstop.common.capabilities.FullStopCapability.grabCapability;

public class PhysicsDispatch {
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.player.isDeadOrDying() || event.player.isRemoved()) return;
        if (event.player instanceof ServerPlayer || event.player != Minecraft.getInstance().player) return;

        FullStopCapability fullstopcap = grabCapability(event.player);
        Vec3 playerDelta = event.player.getDeltaMovement();
        fullstopcap.setCurrentVelocity(playerDelta);

        if (event.player.isPassenger()) {
            Entity vehicle = event.player.getVehicle();
            Vec3 vehicleDelta = vehicle.getDeltaMovement();
            PlayerDeltaPacket deltaPacket = new PlayerDeltaPacket(vehicleDelta);
            PacketHandler.sendToServer(deltaPacket);
        } else {
            PlayerDeltaPacket deltaPacket = new PlayerDeltaPacket(playerDelta);
            PacketHandler.sendToServer(deltaPacket);
        }
    }
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {

        if (event.level instanceof ClientLevel level) {
            level.tickingEntities.forEach(PhysicsDispatch::onEntityTick);
        }
    }

    private static void onEntityTick(Entity entity) {
        if (Physics.unphysable(entity)) return;

        Physics physics = new Physics(entity);
        physics.bounceEntity();
        physics.impactAesthetic();

//        if (!(entity instanceof Player player)) return;
//        if (!player.isSwimming()) {
//            if (player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.DIAMOND_SWORD)) {
//                player.setPose(Pose.SWIMMING); // This was used to test negating damage when prone (swimming)
//            }
//        }

    }

//    @SubscribeEvent
//    public static void onClientTick(TickEvent.ClientTickEvent event) {
//
//    }
}
