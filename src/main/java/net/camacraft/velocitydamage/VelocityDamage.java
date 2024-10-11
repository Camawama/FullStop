package net.camacraft.velocitydamage;

import net.camacraft.velocitydamage.capabilities.FullStopCapability;
import net.camacraft.velocitydamage.capabilities.PositionCapability;
import net.camacraft.velocitydamage.handler.PacketHandler;
import net.camacraft.velocitydamage.network.PlayerDeltaPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
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
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
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

import static net.camacraft.velocitydamage.capabilities.PositionCapability.Provider.POSITION_CAP;
import static net.camacraft.velocitydamage.capabilities.FullStopCapability.Provider.DELTAV_CAP;
import static net.camacraft.velocitydamage.VelocityDamageConfig.SERVER;
import static net.camacraft.velocitydamage.VelocityDamageConfig.SERVER_SPEC;
import static net.minecraftforge.event.TickEvent.Phase.START;

@Mod(VelocityDamage.MOD_ID)
public class VelocityDamage
{
    public static final String MOD_ID = "velocitydamage";
    /**
     * For some reason entities on the ground still have a negative delta Y change of this value.
     */
    public static final double RESTING_Y_DELTA = 0.0784000015258789;

    public VelocityDamage() {
        MinecraftForge.EVENT_BUS.register(VelocityDamage.class);
        MinecraftForge.EVENT_BUS.register(PositionCapability.class);
        MinecraftForge.EVENT_BUS.register(VelocityDamageConfig.class);
        MinecraftForge.EVENT_BUS.register(FullStopCapability.class);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
    }

    public static boolean isRiptiding(LivingEntity entity) {
        if (entity instanceof Player player) {
            if (player.isUsingItem() && player.getUseItem().getItem() instanceof TridentItem) {
                int enchantLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.RIPTIDE, player.getUseItem());
                return enchantLevel > 0;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        // Cancel the fall damage event
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (SERVER.velocityThreshold.get() == 0) return;

        LivingEntity entity = event.getEntity();
        if (entity.isDeadOrDying() || entity.isRemoved()) return;
        if (entity instanceof Player player) if (player.isCreative()) return;
        if (entity.level().isClientSide()) return;

        FullStopCapability deltav = entity.getCapability(DELTAV_CAP).orElseThrow(IllegalStateException::new);
        deltav.tick(entity);

        double delta = deltav.getDeltaSpeed();
        double damage = Math.max(delta - 12.77, 0);

        if (isRiptiding(entity)) {
            deltav.seenRiptiding();
        }

        if (!entity.isFallFlying() && !deltav.recentlyRiptiding() && damage > 0) {
            DamageSources sources = entity.damageSources();
            entity.hurt(
                    deltav.isMostlyDownward() ? sources.fall() : sources.flyIntoWall(),
                    (float) (damage * 1.07)
            );

            entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, (int) damage * 5, (int) damage / 2, false, false));
            entity.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 3.2F, 0.7F);
        }
        if (deltav.getRunningAverageDelta() > 2) {
            entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,30, 0, false, false));
        }
        if (deltav.getRunningAverageDelta() > 1.75) {
            entity.addEffect(new MobEffectInstance(MobEffects.CONFUSION,90, 0, false, false));
        }
       // if (entity instanceof Player && delta > 1.0)
       //     for (ServerPlayer player : entity.getServer().getPlayerList().getPlayers())
       //        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("delta: " + delta));

        //if (entity instanceof Bat || entity instanceof Player) return;

        //Vec3 velocity = entityVelocity(entity).scale((double) 1 / 20);
        //if (velocity.scale(20).horizontalDistance() <= SERVER.velocityThreshold.get()) return;

        //noinspection SuspiciousNameCombination
        // if (Mth.equal(velocity.x, entity.collide(velocity).x) && Mth.equal(velocity.z, entity.collide(velocity).z)) return;
        //try {
        //    Method collideMethod = Entity.class.getDeclaredMethod("collide", Vec3.class);
        //    collideMethod.setAccessible(true);
        //    Vec3 collidedVelocity = (Vec3) collideMethod.invoke(entity, velocity);
        //    if (Mth.equal(velocity.x, collidedVelocity.x) && Mth.equal(velocity.z, collidedVelocity.z)) return;
        //} catch (Exception e) {
        //    e.printStackTrace();
        //}
        //entity.playSound(PLAYER_ATTACK_CRIT, 3.2F, 0.7F);
        //entity.hurt(entity.damageSources().flyIntoWall(), (float) ((float) velocity.scale(20).horizontalDistance() - SERVER.velocityThreshold.get()));
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

    /**
     * Used to get the velocity (in blocks/second) of an entity. A special handling case is made for
     * <code>ServerPlayer</code>s as they do not return the delta movement caused due to input from the corresponding
     * player.
     * @param entity the <code>Entity</code> to return a velocity from.
     * @return  the velocity of the entity relative to the world as an R3 vector.
     */
    public static Vec3 entityVelocity(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            PositionCapability position = player.getCapability(POSITION_CAP).orElseThrow(IllegalStateException::new);
            return position.currentPosition.subtract(position.oldPosition).scale(20);
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

        if (attackerVelocity.length() == 0 && targetVelocity.length() == 0) return 0;

        Vec3 attackerPosition = attacker.position();
        Vec3 targetPosition = target.position();

        if (targetVelocity.y() - attackerVelocity.y() >= 0 && target.position().y() > attacker.position().y()) attackerPosition = attacker.getEyePosition();
        if (targetVelocity.y() - attackerVelocity.y() <= 0 && target.position().y() < attacker.position().y()) targetPosition = target.getEyePosition();

        Vec3 velocityDifference = attackerVelocity.subtract(targetVelocity);
        Vec3 directionToTarget = targetPosition.subtract(attackerPosition).normalize();

        return directionToTarget.dot(velocityDifference);
    }
    public static float calculateNewDamage(float approachVelocity, float originalDamage) {
        if (approachVelocity == 0) return originalDamage;

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