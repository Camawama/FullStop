package net.camacraft.fullstop.server;

import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class CancelEvents {

    // Cancel the fall damage event
    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        event.setCanceled(true);
    }


}
