package net.camacraft.fullstop.server;

import net.camacraft.fullstop.common.effect.ModEffects;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
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

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        // Check if the projectile is a firework and it hit a block
        if (event.getProjectile() instanceof FireworkRocketEntity && event.getRayTraceResult() instanceof BlockHitResult) {
            // Cancel the event to prevent onHitBlock() from being called
            event.setCanceled(true);
        }
    }
}
