package net.camacraft.fullstop;

import net.camacraft.fullstop.capabilities.FullStopCapability;
import net.camacraft.fullstop.capabilities.PositionCapability;
import net.camacraft.fullstop.handler.PacketHandler;
import net.camacraft.fullstop.network.PlayerDeltaPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.camacraft.fullstop.capabilities.PositionCapability.Provider.POSITION_CAP;
import static net.camacraft.fullstop.capabilities.FullStopCapability.Provider.DELTAV_CAP;
import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.FullStopConfig.SERVER_SPEC;
import static net.minecraftforge.event.TickEvent.Phase.START;

@Mod(FullStop.MOD_ID)
public class FullStop
{
    public static final String MOD_ID = "fullstop";
    /**
     * For some reason entities on the ground still have a negative delta Y change of this value.
     */
    public static final double RESTING_Y_DELTA = 0.0784000015258789;

    public FullStop() {
        MinecraftForge.EVENT_BUS.register(FullStop.class);
        MinecraftForge.EVENT_BUS.register(PositionCapability.class);
        MinecraftForge.EVENT_BUS.register(FullStopConfig.class);
        MinecraftForge.EVENT_BUS.register(FullStopCapability.class);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
    }

    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        // Cancel the fall damage event
        event.setCanceled(true);
    }

//    @SubscribeEvent
//    public static void onJumpEvent(LivingEvent.LivingJumpEvent event) {
//        // Cancel the jump event
//        event.setCanceled(true);
//    }
// DETECT ONCE THE PLAYER HAS TAKEN DAMAGE AND PREVENT JUMPING FOR AS LONG AS THE SLOWNESS EFFECT GETS APPLIED

    public static FullStopCapability grabCapability(LivingEntity entity) {
        FullStopCapability fullstopcap = entity.getCapability(DELTAV_CAP).orElseThrow(IllegalStateException::new);
        return fullstopcap;
    }

    //@SubscribeEvent
    //public static void onEnterVehicle() {
    //
    //}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (SERVER.velocityThreshold.get() == 0) return;

        LivingEntity entity = event.getEntity();
        if (entity.isDeadOrDying() || entity.isRemoved()) return;
        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) return;
        if (entity.level().isClientSide()) return;

        FullStopCapability fullstopcap = grabCapability(entity);
        fullstopcap.tick(entity);

        applyDamage(entity, fullstopcap);
        applyGForceEffects(fullstopcap, entity);

        //logToChat(entity, String.valueOf(fullstopcap.recentlyRiptiding(entity)));
    }

    private static void applyGForceEffects(FullStopCapability fullstop, LivingEntity entity) {
        if (fullstop.getRunningAverageDelta() > 5.0) {
            entity.addEffect(new MobEffectInstance(
                    MobEffects.BLINDNESS,30, 0, false, false));
        }

        if (fullstop.getRunningAverageDelta() > 3.0) {
            entity.addEffect(new MobEffectInstance(
                    MobEffects.CONFUSION,90, 0, false, false));
        }
    }

    private static void playSound(LivingEntity entity, double damage) {
        entity.level().playSound(null, entity.blockPosition(),
                SoundEvents.PLAYER_BIG_FALL, SoundSource.PLAYERS, 0.6F, 0.8F);

        entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                (int) damage * 5, (int) damage / 2, false, false));

        entity.addEffect(new MobEffectInstance(MobEffects.JUMP,
                (int) damage * 5, 200, false, false));
    }

//    public static boolean collidingKinetically(LivingEntity entity) {
//        FullStopCapability fullstopcap = grabCapability(entity);
//        AABB boundingBox = entity.getBoundingBox();
//        Vec3 direction = fullstopcap.getPreviousVelocity().normalize();
//        Vec3 castForward = entity.position().add(direction.scale(0.875));
//        AtomicBoolean colliding = new AtomicBoolean(false);
//
//        boundingBox = boundingBox.move(boundingBox.getCenter().add(castForward));
//        entity.level().getBlockStates(boundingBox).forEach(blockState -> {
//            if (!blockState.isAir() && !blockState.liquid()) { //TODO consider adding liquid blockstate here too
//                colliding.set(true);
//            }
//        });
//
//        // No collisions found
//        return colliding.get();
//    }

    public static boolean collidingKinetically(LivingEntity entity) {
        FullStopCapability fullstopcap = grabCapability(entity);
        AABB boundingBox = entity.getBoundingBox();

        // Get the normalized direction from previous velocity
        Vec3 direction = fullstopcap.getPreviousVelocity().normalize();

        // If the direction is zero (not moving), return false
        if (direction.lengthSqr() == 0) {
            return false;
        }

        // Cast forward by a small distance in the direction of movement
        Vec3 castForward = entity.position().add(direction.scale(0.875));

        // Expand the bounding box slightly in the direction we're checking for collisions
        AABB expandedBox = boundingBox.expandTowards(direction.scale(0.5));

        // Iterate over the block states in the expanded bounding box
        AtomicBoolean colliding = new AtomicBoolean(false);
        entity.level().getBlockStates(expandedBox).forEach(blockState -> {
            if (!blockState.isAir() && !blockState.liquid()) {
                colliding.set(true);  // Collision detected
            }
        });

        // Return if we found any collision
        return colliding.get();
    }

    public static void logToChat(Entity entity, String message) {
        Component chatMessage = Component.literal(message);
        entity.sendSystemMessage(chatMessage);
    }


    private static void applyDamage(LivingEntity entity, FullStopCapability fullstopcap) {
        double delta = fullstopcap.getDeltaSpeed();
        double damage = Math.max(delta - 12.77, 0);

        if (delta > 0.5) {
            double x = 0;
        }

        if (entity.isFallFlying() || entity.isSpectator() || fullstopcap.recentlyRiptiding(entity)) return;
        if (damage <= 0) return;

        DamageSources sources = entity.damageSources();
        float damageAmount = (float) (damage * 1.07);

        if (fullstopcap.isMostlyDownward()) {
            entity.hurt(sources.fall(), damageAmount);
        } else if(collidingKinetically(entity)) {
            entity.hurt(sources.flyIntoWall(), damageAmount);
        }

        playSound(entity, damage);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!(event.player instanceof ServerPlayer player)){
            PacketHandler.sendToServer(new PlayerDeltaPacket(event.player.getDeltaMovement()));
            return;
        }
        if (!event.phase.equals(START)) return;
        player.getCapability(POSITION_CAP)
                .ifPresent(position -> position.tickPosition(player));
    }

    // Lowest priority so other mods have a chance to change the damage prior to this
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.isCanceled()) return;
        if (event.getSource().getDirectEntity() == null) return;
        if (event.getSource().getDirectEntity().level().isClientSide || event.getEntity().level().isClientSide) return;
        if (event.getSource().getDirectEntity() instanceof Projectile && SERVER.projectileMultiplier.get() == 0) return;
        if (event.getSource().getDirectEntity() instanceof AbstractArrow && !(SERVER.wildMode.get())) return;

        LivingEntity entity = event.getEntity();

        Entity attacker = event.getSource().getDirectEntity();

        float originalDamage = event.getAmount();

        double approachVelocity = calculateApproachVelocity(attacker, event.getEntity());
        float newDamage = calculateNewDamage((float) approachVelocity, originalDamage);
        int damageRatio = Math.round(newDamage / originalDamage);

        if (attacker instanceof Player) {
            Player player = (Player) attacker;
            ItemStack item = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (item.isDamageableItem()) {
                int currentValue = item.getDamageValue();
                item.setDamageValue(currentValue + damageRatio - 1);
            }
        }

        if (newDamage > originalDamage) {
            entity.level().playSound(null, entity.blockPosition(),
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.6F, 0.9F);
        }

        event.setAmount(newDamage);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(SERVER.projectilesHaveMomentum.get())) return;
        if (!(event.getEntity() instanceof Projectile projectile) || projectile.level().isClientSide) return;
        if (projectile.getOwner() == null) return;

        Vec3 ownerVelocity = entityVelocity(projectile.getOwner()).scale((double) 1 / 20);
        if (ownerVelocity.equals(Vec3.ZERO)) return;

        projectile.setDeltaMovement(projectile.getDeltaMovement().add(ownerVelocity));
    }

    public static Vec3 entityVelocity(Entity entity) {
        if (entity instanceof ServerPlayer) {
            return entity.getCapability(DELTAV_CAP).orElseThrow(IllegalStateException::new).getCurrentVelocity();
        }

        return entity.getDeltaMovement().add(0, RESTING_Y_DELTA, 0).scale(20);
    }

    /**
     * Positive values indicate that the attacker is approaching the target. Negative indicates that the attacker is
     * retreating from the target.
     * <br><br>
     * Faithful to true calculations, however; it should be noted that since position is measured at the feet, if the
     * attacker hits the target as it moves upwards relative to the attacker, a debuff is incurred. To fairly rectify
     * this, the eye positions of the entities are also considered.
     */
    public static double calculateApproachVelocity(Entity attacker, LivingEntity target) {
        Vec3 attackerVelocity =
                attacker instanceof Projectile projectile
                ? entityVelocity(projectile).subtract(entityVelocity(projectile).scale(SERVER.projectileMultiplier.get()))
                : entityVelocity(attacker);
        Vec3 targetVelocity = entityVelocity(target);

        if (attackerVelocity.length() == 0 && targetVelocity.length() == 0) {
            return 0;
        }

        Vec3 attackerPosition = attacker.position();
        Vec3 targetPosition = target.position();

        if (targetVelocity.y() >= attackerVelocity.y() && target.position().y() > attacker.position().y()) {
            attackerPosition = attacker.getEyePosition();
        }
        if (targetVelocity.y() <=  attackerVelocity.y() && target.position().y() < attacker.position().y()) {
            targetPosition = target.getEyePosition();
        }

        Vec3 velocityDifference = attackerVelocity.subtract(targetVelocity);
        Vec3 directionToTarget = targetPosition.subtract(attackerPosition).normalize();

        return directionToTarget.dot(velocityDifference);
    }
    public static float calculateNewDamage(float approachVelocity, float originalDamage) {
        if (approachVelocity == 0) {
            return originalDamage;
        }

        float arbitraryVelocity = Math.abs(approachVelocity) / SERVER.velocityIncrement.get().floatValue();
        float multiplier = (float) (Math.pow(arbitraryVelocity, SERVER.exponentiationConstant.get().floatValue()) / 2F);
        float percentageBonus = originalDamage * multiplier;

        if (approachVelocity < 0) {
            float minDamage = originalDamage * SERVER.minDamagePercent.get().floatValue();
            return Math.max(minDamage, originalDamage - percentageBonus);
        }

        float maxDamage = originalDamage * SERVER.maxDamagePercent.get().floatValue();
        return Math.min(maxDamage, originalDamage + percentageBonus);
    }
}