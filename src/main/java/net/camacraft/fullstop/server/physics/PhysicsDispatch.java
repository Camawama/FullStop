package net.camacraft.fullstop.server.physics;

import com.google.common.collect.Lists;
import net.camacraft.fullstop.client.message.LogToChat;
import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.physics.Physics;
import net.camacraft.fullstop.server.CancelEvents;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.client.message.LogToChat.logToChat;
import static net.camacraft.fullstop.common.capabilities.FullStopCapability.grabCapability;
import static net.camacraft.fullstop.common.physics.Physics.calcNewDamage;

public class PhysicsDispatch {

//    @SubscribeEvent
//    public static void onLevelTick(TickEvent.LevelTickEvent event) {
//
//        if (event.level instanceof ServerLevel level) {
//            level.getAllEntities().forEach(PhysicsDispatch::onEntityTick);
//        }
//    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.level instanceof ServerLevel level) {
            List<Entity> entities = Lists.newArrayList(level.getAllEntities());
            for (Entity entity : entities) {
                onEntityTick(entity);
            }
        }
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

    @SubscribeEvent
    public static void onEntityChangeDimension(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            FullStopCapability cap = grabCapability(living);
            if (cap != null) {
                cap.setHasTeleported(true);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityTeleport(EntityTeleportEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            FullStopCapability cap = grabCapability(living);
            if (cap != null) {
                cap.setHasTeleported(true);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            FullStopCapability cap = grabCapability(living);
            if (cap != null) {
                cap.setHasTeleported(true);

                if (living instanceof ServerPlayer) {
                    if (!cap.getJoinedForFirstTime()) {
                        cap.setJoinedForFirstTime(true); // Old check that could be useful for checking if a player has joined for the first time
                    }
                }
            }
        }
    }

    private static void onEntityTick(Entity entity) {
        if (Physics.unphysable(entity)) return;

//        if (!entity.isSwimming()) {
//            if (entity instanceof Player player) {
//                if (player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.DIAMOND_SWORD)) {
//                    player.setPose(Pose.SWIMMING);
//                }
//            }
//        }

        Physics physics = new Physics(entity);
        physics.handleEntityCollision();
        physics.applyForceEffects();
        physics.bounceEntity();
        physics.impactSound();
        physics.impactDamageSound();
        physics.applyKineticDamage();
        physics.applyDamageEffects();
    }
}
