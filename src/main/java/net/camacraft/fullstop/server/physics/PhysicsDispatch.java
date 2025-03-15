package net.camacraft.fullstop.server.physics;

import net.camacraft.fullstop.common.physics.Physics;
import net.camacraft.fullstop.common.physics.PhysicsRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ConcurrentModificationException;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.physics.Physics.calcNewDamage;

public class PhysicsDispatch {
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (!(event.level instanceof ServerLevel)) return;
        try {
            PhysicsRegistry.server.forEach(PhysicsDispatch::onEntityTick);
        } catch (ConcurrentModificationException e ){
            System.out.println("concurrent modification");
        }
//        if (horizontalImpactType != HorizontalImpactType.NONE) {
//            logToChat(horizontalImpactType);
//        }
    }
    // Lowest priority so other mods have a chance to change the damage prior to this
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.isCanceled()) return;
        if (event.getSource().getDirectEntity() == null) return;
        if (event.getEntity().level().isClientSide) return;

        if (event.getSource().getDirectEntity() instanceof Projectile && SERVER.projectileMultiplier.get() == 0) return;
        if (event.getSource().getDirectEntity() instanceof AbstractArrow && !(SERVER.wildMode.get())) return;

        float newDamage = calcNewDamage(event);

        event.setAmount(newDamage);
    }

    private static void onEntityTick(Entity entity) {
        if (Physics.unphysable(entity)) return;

        Physics physics = new Physics(entity);
        physics.applyForceEffects();
        physics.bounceEntity();
        physics.applyDamage();
        physics.applyDamageEffects();
    }
}
