package net.camacraft.fullstop.client.compat.create;

import net.camacraft.fullstop.common.compat.create.kinetic.GearPushables;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Gears on the vehicle the local player drives: a driven vehicle is moved by its driver's client, so the push, the
 * fling and the pull of a nip are applied here ({@link GearPushables#tickVehicle}); the server only jams the gears.
 * Registered with Create installed, on a client.
 */
public final class GearVehiclesClient {

    private GearVehiclesClient() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.isPaused()) return;
        Entity vehicle = mc.player.getRootVehicle();
        if (vehicle == mc.player || vehicle.getControllingPassenger() != mc.player) return;
        GearPushables.tickVehicle(vehicle);
    }
}
