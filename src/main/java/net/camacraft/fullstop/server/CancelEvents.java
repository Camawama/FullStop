package net.camacraft.fullstop.server;

import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class CancelEvents {

    // Cancel the fall damage event
    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        if (DamageImmunityRules.unphysable(event.getEntity())) {
            return;
        }

        event.setCanceled(true);
    }
}
