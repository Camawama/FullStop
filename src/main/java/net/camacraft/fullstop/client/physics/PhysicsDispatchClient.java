package net.camacraft.fullstop.client.physics;

import net.camacraft.fullstop.client.physics.collision.ClientCollisionDetector;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.network.PlayerDeltaPacket;
import net.camacraft.fullstop.common.physics.interaction.BounceHandler;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import static net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability;

public class PhysicsDispatchClient {
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.player.isDeadOrDying() || event.player.isRemoved()) return;
        if (event.player instanceof ServerPlayer || event.player != Minecraft.getInstance().player) return;

        FullStopCapability fullstopcap = grabCapability(event.player);
        Vec3 playerDelta = event.player.getDeltaMovement();
        fullstopcap.setCurrentNativeVelocity(playerDelta);

        if (event.player.isPassenger()) {
            Entity vehicle = event.player.getVehicle();
            if (vehicle != null) {
                Vec3 vehicleDelta = vehicle.getDeltaMovement();
                PlayerDeltaPacket deltaPacket = new PlayerDeltaPacket(vehicleDelta);
                PacketHandler.sendToServer(deltaPacket);
            }
        } else {
            PlayerDeltaPacket deltaPacket = new PlayerDeltaPacket(playerDelta);
            PacketHandler.sendToServer(deltaPacket);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ClientLevel level) {
            for (Entity entity : level.entitiesForRendering()) {
                onEntityTick(entity);
            }
        }
    }

    @SubscribeEvent
    public static void onDismount(EntityMountEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            FullStopCapability cap = grabCapability(living);

            if (cap != null) {
                cap.justDismounted();
            }
        }
    }

    private static void onEntityTick(Entity entity) {
        if (DamageImmunityRules.unphysable(entity)) return;

        FullStopCapability fullstop = grabCapability(entity);
        if (fullstop == null) return;

        if (entity.tickCount != fullstop.getLastTick()) {
            fullstop.tick(entity);
            fullstop.setLastTick(entity.tickCount);
        }

        Collision collision = ClientCollisionDetector.detect(entity, fullstop);

        // We only care about bounce logic on the client for the camera rotation
        // The server handles the actual velocity change and damage
        BounceHandler.apply(entity, fullstop, collision, false);
    }
}
