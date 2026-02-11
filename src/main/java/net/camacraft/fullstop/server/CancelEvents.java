package net.camacraft.fullstop.server;

import net.camacraft.fullstop.common.effect.ModEffects;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
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

    @SubscribeEvent
    public static void onLivingJump(LivingEvent.LivingJumpEvent event) {
        if (event.getEntity().hasEffect(ModEffects.SPRAIN.get())) {
            
            var entity = event.getEntity();
            
            if (entity.getDeltaMovement().y > 0) {
                 entity.setDeltaMovement(entity.getDeltaMovement().x, 0, entity.getDeltaMovement().z);
            }
        }
    }
}
