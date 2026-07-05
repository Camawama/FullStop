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

        if (event.player.isPassenger()) {
            Entity vehicle = event.player.getVehicle();
            if (vehicle != null) {
                PacketHandler.sendToServer(new PlayerDeltaPacket(vehicle.getDeltaMovement(), true));
            }
        } else {
            PacketHandler.sendToServer(new PlayerDeltaPacket(playerDelta, false));
        }
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

        // Camera-only bounce reaction; the server owns actual motion.
        if (FullStopConfig.CLIENT.rotateCamera.get()) {
            Collision collision = CommonCollisionDetector.detectBlocks(player, fullstop);
            CameraBounceHandler.apply(player, fullstop, collision);
        }
    }
}
