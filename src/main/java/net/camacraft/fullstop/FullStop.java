package net.camacraft.fullstop;

import net.camacraft.fullstop.capabilities.FullStopCapability;
import net.camacraft.fullstop.capabilities.PositionCapability;
import net.camacraft.fullstop.handler.PacketHandler;
import net.camacraft.fullstop.network.PlayerDeltaPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;

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
//    public void onLivingJump(LivingEvent.LivingJumpEvent event) {
//        Entity Entity = event.getEntity();
//        if (Entity.hasEffect(CommonModEvents.NO_JUMP.get())) {
//            Entity.setDeltaMovement(Entity.getDeltaMovement().x, 0, Entity.getDeltaMovement().z); //TODO finish adding custom status effect
//        }
//    }

    public static FullStopCapability grabCapability(Entity entity) {
        FullStopCapability fullstopcap = entity.getCapability(DELTAV_CAP).orElseThrow(IllegalStateException::new);
        return fullstopcap;
    }

    //@SubscribeEvent
    //public static void onEnterVehicle() { //TODO add some logic for when entity enters vehicle while falling
    //
    //}

//    @SubscribeEvent(priority = EventPriority.LOWEST)
//    public static void onLevelTick(TickEvent.LevelTickEvent event) {
//        AABB largestBb = new AABB(-3000, -64, -3000, 3000, 320, 3000);
//        List<Entity> entities = event.level.getEntitiesOfClass(Entity.class, largestBb); //TODO implement a different way to scan all entities (this one lags)
//
//        for (Entity entity : entities) {
//            onLivingTick(entity);
//        }
//    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (SERVER.velocityThreshold.get() == 0) return;

        LivingEntity entity = event.getEntity();

        if (entity.isDeadOrDying()) return;
        if (entity.isRemoved()) return;

        FullStopCapability fullstopcap = grabCapability(entity);
        fullstopcap.tick(entity);

        if (entity instanceof Player player) {
            if (player.isCreative() || player.isSpectator()) return;
            applyForceEffects(fullstopcap, entity);
        }

            if (entity.isFallFlying()) {
                return;
            }


        HorizontalImpactType horizontalImpactType = collidingKinetically(entity);
        double damage = applyDamage(entity, fullstopcap, horizontalImpactType);

        if (horizontalImpactType != HorizontalImpactType.NONE) {
            bounceEntity(entity, horizontalImpactType, damage > 0);
        }

        if (horizontalImpactType == HorizontalImpactType.SLIME || horizontalImpactType == HorizontalImpactType.HONEY) {
            stickyImpact(entity, horizontalImpactType);
        }

        if (damage > 0) {
            impactDamageSound(entity, damage, horizontalImpactType);
            applyDamageEffects(entity, damage);
        }
    }

//    public static void onLivingTick(Entity entity) { //TODO Uncomment this when todo on line 97 is complete
//        if (SERVER.velocityThreshold.get() == 0) return;
//
//        if (entity instanceof LivingEntity living) {
//            if (living.isDeadOrDying()) return;
//        }
//
//        if (entity.isRemoved()) return;
//
//        FullStopCapability fullstopcap = grabCapability(entity);
//        fullstopcap.tick(entity);
//
//        if (entity instanceof Player player) {
//            if (player.isCreative() || player.isSpectator()) return;
//            applyForceEffects(fullstopcap, entity);
//        }
//
//        if (entity instanceof LivingEntity living) {
//            if (living.isFallFlying()) {
//                return;
//            }
//        }
//
//        HorizontalImpactType horizontalImpactType = collidingKinetically(entity);
//        double damage = applyDamage(entity, fullstopcap, horizontalImpactType);
//
//        if (horizontalImpactType != HorizontalImpactType.NONE) {
//            bounceEntity(entity, horizontalImpactType, damage > 0);
//        }
//
//        if (horizontalImpactType == HorizontalImpactType.SLIME || horizontalImpactType == HorizontalImpactType.HONEY) {
//            stickyImpact(entity, horizontalImpactType);
//        }
//
//        if (damage > 0) {
//            impactDamageSound(entity, damage, horizontalImpactType);
//            applyDamageEffects(entity, damage);
//        }
//
////        logToChat(entity, entity.isAutoSpinAttack());
////        logToChat(entity, fullstopcap.getCurrentVelocity());
////        logToChat(entity, entity.getYRot());
////        logToChat(entity, entity.getYRot());
//
////        if (horizontalImpactType != HorizontalImpactType.NONE) {
////            logToChat(entity, horizontalImpactType);
////        }
//
////        logToChat(entity, entity.position());
////        logToChat(entity, entityVelocity(entity));
//    }

    private static void spawnParticle(Vec3 pos, HorizontalImpactType impactType) {
        ParticleOptions particleType;

        if (impactType == HorizontalImpactType.SLIME) {
            particleType = ParticleTypes.ITEM_SLIME;
        } else if (impactType == HorizontalImpactType.HONEY) {
            particleType = ParticleTypes.FALLING_HONEY;
        } else {
            throw new IllegalStateException("not a sticky type");
        }

        Minecraft.getInstance().level.addParticle(
                particleType,
                pos.x, pos.y, pos.z,
                0.1, 0.1, 0.1
        );
    }

    private static void stickyImpact(Entity entity, HorizontalImpactType impactType) {
        Vec3 eyePos = entity.getEyePosition();
        Vec3 pos = entity.position();
        Vec3 midPos = eyePos.add(pos).scale(0.5);

        if (impactType == HorizontalImpactType.SLIME) {
            entity.level().playSound(null, eyePos.x, eyePos.y, eyePos.z,
                    SoundEvents.SLIME_BLOCK_FALL, SoundSource.BLOCKS, 0.5F, 1.0F);
        } else if (impactType == HorizontalImpactType.HONEY) {
            entity.level().playSound(null, eyePos.x, eyePos.y, eyePos.z,
                    SoundEvents.HONEY_BLOCK_BREAK, SoundSource.BLOCKS, 0.5F, 1.0F);
        }

        spawnParticle(eyePos, impactType);
        spawnParticle(midPos, impactType);
        spawnParticle(pos, impactType);
    }

    private static void applyForceEffects(FullStopCapability fullstop, Entity entity) {
        if (entity instanceof LivingEntity livingEntity) {
            if (fullstop.getRunningAverageDelta() > 5.0) {
                livingEntity.addEffect(new MobEffectInstance(
                        MobEffects.BLINDNESS, 30, 0, false, false));
            }

            if (fullstop.getRunningAverageDelta() > 3.0) {
                livingEntity.addEffect(new MobEffectInstance(
                        MobEffects.CONFUSION, 90, 0, false, false));
            }
        }
    }

    private static void impactDamageSound(Entity entity, double damage, HorizontalImpactType impactType) {
        float volume = Math.min(0.1F * (float) damage, 1.0F);

        if (impactType == HorizontalImpactType.SOLID) {
            entity.level().playSound(null, entity,
                    SoundEvents.PLAYER_BIG_FALL, SoundSource.HOSTILE, volume, 0.8F);
        }
    }

    private static void applyDamageEffects(Entity entity, double damage) {
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                    (int) damage * 5, (int) damage / 2, false, false));

//        entity.addEffect(new MobEffectInstance(CommonModEvents.NO_JUMP.get(), (int) damage * 5, 1)); //TODO uncomment after custom status effect is implemented
        }
    }

    public enum HorizontalImpactType {
        NONE, SLIME, SOLID, HONEY
    }

    public static HorizontalImpactType collidingKinetically(Entity entity) {
        FullStopCapability fullstopcap = grabCapability(entity);
        AABB boundingBox = entity.getBoundingBox();

        // Get the normalized direction from previous velocity
        Vec3 direction = fullstopcap.getPreviousVelocity().multiply(1, 0, 1).normalize();

        // If the direction is zero (not moving), return false
        if (direction.lengthSqr() == 0) { return HorizontalImpactType.NONE; }

        AABB expandedBox = expandAABB(direction, boundingBox);

        // Iterate over the block states in the expanded bounding box
//        AtomicBoolean colliding = new AtomicBoolean(false);
//        AtomicBoolean slime = new AtomicBoolean(false);
//        AtomicBoolean honey = new AtomicBoolean(false);
        AtomicInteger collisionTypeOrd = new AtomicInteger(HorizontalImpactType.NONE.ordinal());
        Level level = entity.level();

        level.getBlockCollisions(entity, expandedBox).forEach(voxelShape -> {
            Vec3 center = voxelShape.bounds().getCenter();
            BlockState blockState = level.getBlockState(blockPosFromVec3(center));

//            colliding.set(true);  // Collision detected
            collisionTypeOrd.set(HorizontalImpactType.SOLID.ordinal());

            if (blockState.isSlimeBlock()) {
                collisionTypeOrd.set(HorizontalImpactType.SLIME.ordinal());
            }
        });

        level.getBlockStates(expandedBox).forEach(blockState -> {
            if (blockState.isStickyBlock() && !blockState.isSlimeBlock()) {
                collisionTypeOrd.set(HorizontalImpactType.HONEY.ordinal());
            }
        });

        HorizontalImpactType impactType = HorizontalImpactType.values()[collisionTypeOrd.get()];

        return fullstopcap.actualImpact(impactType);
    }

    private static BlockPos blockPosFromVec3(Vec3 pos) {
        Vec3i vec3i = new Vec3i((int) pos.x, (int) pos.y, (int) pos.z);
        return new BlockPos(vec3i);
    }

    @NotNull
    private static AABB expandAABB(Vec3 direction, AABB b) {
        double dirX = Math.signum(direction.x);
        double dirZ = Math.signum(direction.z);

        // Expand the bounding box infinitesimally in the direction we're checking for collisions

        //AABB expandedBox = b.expandTowards(dX, 0, dZ);
        AABB expandedBox = new AABB(
                dirX < 0 ? Math.nextDown(b.minX) : b.minX,
                b.minY,
                dirZ < 0 ? Math.nextDown(b.minZ) : b.minZ,
                dirX > 0 ? Math.nextUp(b.maxX) : b.maxX,
                b.maxY,
                dirZ > 0 ? Math.nextUp(b.maxZ) : b.maxZ
        );
        return expandedBox;
    }

    private static void bounceEntity(Entity entity, HorizontalImpactType horizontalImpactType, boolean hurt) {
        FullStopCapability fullstopcap = grabCapability(entity);

        Vec3 preV = fullstopcap.getPreviousVelocity();
        Vec3 curV = fullstopcap.getCurrentVelocity();
        double perpScaleFactor, paraScaleFactor;

        if (horizontalImpactType == HorizontalImpactType.SLIME) {
            perpScaleFactor = -1.0;
            paraScaleFactor = 1.0;
        } else if (horizontalImpactType == HorizontalImpactType.HONEY) {
            perpScaleFactor = -0.0;
            paraScaleFactor = 0.0;
        } else if (hurt) {
            perpScaleFactor = -0.5;
            paraScaleFactor = 0.2;
        } else {
            perpScaleFactor = -0.0;
            paraScaleFactor = 1.0;
        }

        Vec3 newV = new Vec3(
                preV.x * (curV.x == 0.0 ? perpScaleFactor : paraScaleFactor),
                curV.y,
                preV.z * (curV.z == 0.0 ? perpScaleFactor : paraScaleFactor)
        ).scale(0.05);

        entity.setDeltaMovement(newV);


    }

    public static void logToChat(Entity entity, Object message) {
        Component chatMessage = Component.literal(String.valueOf(message));
        entity.sendSystemMessage(chatMessage);
    }

    private static double applyDamage(Entity entity, FullStopCapability fullstopcap, HorizontalImpactType horizontalImpactType) {
        double delta = fullstopcap.getStoppingForce();
        double damage = Math.max(delta - 12.77, 0);

        if (delta > 0.5) {
            double x = 0;
        }

        if (entity instanceof LivingEntity) {
            if (damage <= 0) return 0;

            DamageSources sources = entity.damageSources();
            float damageAmount = (float) (damage * 1.07);

            if (fullstopcap.isMostlyDownward()) {
                entity.hurt(sources.fall(), damageAmount);
            } else if (horizontalImpactType == HorizontalImpactType.SOLID) {
                entity.hurt(sources.flyIntoWall(), damageAmount);
            } else {
                return 0;
            }
        }
        return damage;

    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.player.isDeadOrDying() || event.player.isRemoved()) return;
        if (!(event.player instanceof ServerPlayer player)) {
            FullStopCapability fullstopcap = grabCapability(event.player);
            fullstopcap.setCurrentVelocity(event.player.getDeltaMovement());
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

        Entity entity = event.getEntity();

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
    public static double calculateApproachVelocity(Entity attacker, Entity target) {
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