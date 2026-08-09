package net.camacraft.fullstop.client.physics;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.network.PlayerDeltaPacket;
import net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import static net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability;

/**
 * Client physics: only the LOCAL player is simulated (for the g-force effects and
 * bounce camera). The server owns everything else — the old client-side simulation
 * of all rendered entities caused rubber-banding.
 */
public class PhysicsDispatchClient {

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // One packet per tick, END phase only (this event fires for both phases).
        if (event.phase != TickEvent.Phase.END) return;
        if (event.side.isServer()) return;
        if (event.player.isDeadOrDying() || event.player.isRemoved()) return;
        if (event.player != Minecraft.getInstance().player) return;

        FullStopCapability cap = grabCapability(event.player);
        if (cap == null) return;

        Vec3 playerDelta = event.player.getDeltaMovement();
        cap.setCurrentNativeVelocity(playerDelta);

        // Collision flags ride along: the server can't measure them for players
        // (move packets arrive pre-clipped), and without them the kinetic-damage
        // evidence gate can never pass horizontally.
        if (event.player.isPassenger()) {
            Entity vehicle = event.player.getVehicle();
            if (vehicle != null) {
                PacketHandler.sendToServer(new PlayerDeltaPacket(vehicle.getDeltaMovement(), true,
                        vehicle.horizontalCollision, vehicle.verticalCollision));
            }
        } else {
            PacketHandler.sendToServer(new PlayerDeltaPacket(playerDelta, false,
                    event.player.horizontalCollision, event.player.verticalCollision));
        }
    }

    /**
     * Captures mount/dismount momentum for the LOCAL player on the client —
     * the side that owns a driven vehicle's (and the player's own) motion.
     * The vehicle's velocity is read from its locally observed movement and
     * handed to ClientVehiclePhysics, which re-applies it for a few ticks:
     * one-shot applications kept losing to late vanilla sync (the dismount
     * position packet overwrites the player's motion, and the boarding window
     * has equivalent transient overwrites).
     */
    @SubscribeEvent
    public static void onMount(net.minecraftforge.event.entity.EntityMountEvent event) {
        if (!event.getEntityMounting().level().isClientSide) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || event.getEntityMounting() != player) return;

        Entity vehicle = event.getEntityBeingMounted();
        Vec3 observedMotion = new Vec3(vehicle.getX() - vehicle.xOld,
                vehicle.getY() - vehicle.yOld,
                vehicle.getZ() - vehicle.zOld);
        ClientVehiclePhysics.onLocalMountChange(event.isMounting(), vehicle, observedMotion);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) return;
        if (DamageImmunityRules.unphysable(player)) return;

        FullStopCapability fullstop = grabCapability(player);
        if (fullstop == null) return;

        if (player.tickCount != fullstop.getLastTick()) {
            fullstop.tick(player);
            fullstop.setLastTick(player.tickCount);
        }

        // Camera-only bounce reaction; the server owns the player's actual motion.
        // (detectBlocks reads SERVER config values, hence the isLoaded guard.)
        boolean cameraAllowed = FullStopConfig.CLIENT.rotateCamera.get()
                && (!FullStopConfig.CLIENT.rotateCameraOnlyWhenFlying.get() || player.isFallFlying())
                && !player.isPassenger();
        if (cameraAllowed && FullStopConfig.SERVER_SPEC.isLoaded()) {
            Collision collision = CommonCollisionDetector.detectBlocks(player, fullstop);
            CameraBounceHandler.apply(player, fullstop, collision);
        }

        // Physics for the vehicle this player DRIVES: vehicle motion is
        // client-authoritative, so bounces and water skips must run here — the
        // server's copy detects contact a tick late and its writes race the
        // client simulation.
        if (FullStopConfig.SERVER_SPEC.isLoaded()) {
            ClientVehiclePhysics.tickDrivenVehicle(player);
        }
    }
}
