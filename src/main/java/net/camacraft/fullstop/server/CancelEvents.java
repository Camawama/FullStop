package net.camacraft.fullstop.server;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.registry.ModEffects;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import static net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability;

public class CancelEvents {

    /**
     * FullStop replaces vanilla fall damage with its own kinetic impact damage
     * (KineticDamageCalculator: vertical stopping force + collision flags), so
     * vanilla fall damage is cancelled for every entity FullStop simulates.
     */
    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        // Must agree with the tick loop's unphysable verdict, which uses the
        // capability's cached NoAI flag — a fresh isNoAi() read here could
        // disagree during the cache window and double- or zero-damage a landing.
        FullStopCapability fullstop = grabCapability(event.getEntity());
        if (DamageImmunityRules.unphysable(event.getEntity(), fullstop)) {
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
        // Prevent fireworks from exploding on block impact (FullStop lets them skim)
        if (event.getProjectile() instanceof FireworkRocketEntity && event.getRayTraceResult() instanceof BlockHitResult) {
            event.setCanceled(true);
        }
    }
}
