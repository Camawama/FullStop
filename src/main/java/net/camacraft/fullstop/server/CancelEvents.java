package net.camacraft.fullstop.server;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class CancelEvents {

//    public static boolean debugBreak = false;

    // Cancel the fall damage event
    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {

//        if (event.getEntity() instanceof Player) {
//            debugBreak = true;
//        }

        event.setCanceled(true);
    }
}
