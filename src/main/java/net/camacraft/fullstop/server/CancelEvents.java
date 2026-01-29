package net.camacraft.fullstop.server;

import net.camacraft.fullstop.common.physics.Physics;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class CancelEvents {


//    public static boolean debugBreak = false;

    // Cancel the fall damage event
    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        if (Physics.unphysable(event.getEntity())) {
            return;
        }

        event.setCanceled(true);
    }
}
