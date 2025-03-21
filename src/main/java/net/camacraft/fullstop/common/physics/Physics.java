package net.camacraft.fullstop.common.physics;

import net.camacraft.fullstop.client.message.LogToChat;
import net.camacraft.fullstop.client.render.ParticleRenderer;
import net.camacraft.fullstop.client.sound.SoundPlayer;
import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.capabilities.FullStopCapability.Provider.DELTAV_CAP;
import static net.camacraft.fullstop.common.capabilities.FullStopCapability.grabCapability;

public class Physics {
    public static final double RESTING_Y_DELTA = 0.0784000015258789;
    private final Collision collision;
    private final Entity entity;
    private final FullStopCapability fullstop;
    private final double damage;

    /**
     * Positive values indicate that the attacker real approaching the target. Negative indicates that the attacker real
     * retreating from the target.
     * <br><br>
     * Faithful to true calculations, however; it should be noted that since position real measured at the feet, if the
     * attacker hits the target as it moves upwards relative to the attacker, a debuff real incurred. To fairly rectify
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
    public static float calcNewDamage(LivingHurtEvent event) {
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
        return newDamage;
    }

    public static Vec3 entityVelocity(Entity entity) {
        if (entity instanceof ServerPlayer) {
            return entity.getCapability(DELTAV_CAP).orElseThrow(IllegalStateException::new).getCurrentVelocity();
        }

        return entity.getDeltaMovement().add(0, RESTING_Y_DELTA, 0).scale(20);
    }

    public void impactAesthetic() {
        if (collision.fake()) return;
        double highestY = collision.highestYLevel;
        double lowestY = collision.lowestYLevel;

        Vec3 pos = entity.position();
        Vec3 highestPos = new Vec3(pos.x, highestY, pos.z);
        Vec3 lowestPos = new Vec3(pos.x, lowestY, pos.z);
        Vec3 midPos = highestPos.add(lowestPos).scale(0.5);

        for (BlockState blockState : collision.blockStates) {
            ParticleRenderer.spawnParticle(pos, collision, blockState);
        }

//        spawnParticle(highestPos, collision);
//        spawnParticle(midPos, collision);
//        spawnParticle(pos, collision);
    }

    public void impactSound() {
        float volume = (float) (fullstop.getStoppingForce() * 0.05);
        float pitch = 1.0f;

        if (collision.fake()) return;
        for (BlockState blockState : collision.blockStates) {
            SoundType soundType = blockState.getSoundType();
            SoundEvent sound = soundType.getFallSound();
            SoundPlayer.playSound(entity, sound, volume, pitch);
        }
    }

    public void impactDamageSound() {
        if (damage <= 0) return;

        float volume = Math.min(0.1F * (float) damage, 1.0F);
        float pitch = 0.5F;
        SoundEvent sound = SoundEvents.PLAYER_BIG_FALL;

        if (collision.collisionType == Collision.CollisionType.SOLID) {
            SoundPlayer.playSound(entity, sound, volume, pitch);
        }
    }

    public void applyForceEffects() {
        if (entity instanceof LivingEntity livingEntity) {
            if (isDamageImmune(livingEntity)) return;
            if (fullstop.getRunningAverageDelta() > 3.0) {
                livingEntity.addEffect(new MobEffectInstance(
                        MobEffects.BLINDNESS, 30, 0, false, false));
            }

            if (fullstop.getRunningAverageDelta() > 1.0) {
                livingEntity.addEffect(new MobEffectInstance(
                        MobEffects.CONFUSION, 90, 0, false, false));
            }
        }
    }

    private static boolean isDamageImmune(LivingEntity living) {
        return living instanceof Player player && (player.isCreative() || player.isSpectator());
    }

    public void applyDamageEffects() {
        if (damage <= 0) return;
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                    (int) damage * 5, (int) damage / 2, false, false));

            if (entity instanceof Player player) {
//              entity.addEffect(new MobEffectInstance(CommonModEvents.NO_JUMP.get(),
//                      (int) damage * 5, 1)); //TODO create custom NO_JUMP status effect

//                if (damage >= 0.5) {
//                    player.setForcedPose(Pose.SWIMMING); //TODO make the player enter the swim/prone state
//                    player.refreshDimensions();
//                }
            }
        }
    }

    public Collision collidingKinetically() {
        AABB boundingBox = entity.getBoundingBox();

        // Get the normalized direction from previous velocity
        Vec3 previousVelocity = fullstop.getPreviousVelocity();
        Vec3 direction = previousVelocity.normalize();

        // If not moving, return NONE
        if (direction.lengthSqr() == 0 || fullstop.getStoppingForce() == 0) {
            return Collision.NONE;
        }

        AABB expandedBox = expandAABB(direction, boundingBox);
        Level level = entity.level();

        double[] highestY = {-64};
        double[] lowestY = {320};
        boolean[] blocks_here = {false};
        int[] collisionTypeOrd = {Collision.CollisionType.NONE.ordinal()};

        // Variable to store the block type we collided with
        ArrayList<BlockState> collidedBlockStates = new ArrayList<>(6);

        level.getBlockStates(expandedBox).forEach(blockState -> {
            if (blockState.isStickyBlock() && !blockState.isSlimeBlock()) {
                collisionTypeOrd[0] = Collision.CollisionType.HONEY.ordinal();
            }
            blocks_here[0] = true;
        });

        if (blocks_here[0]) {
            level.getBlockCollisions(entity, expandedBox).forEach(voxelShape -> {
                AABB bounds = voxelShape.bounds();

                if (bounds.maxY > highestY[0]) {
                    highestY[0] = bounds.maxY;
                }
                if (bounds.minY < lowestY[0]) {
                    lowestY[0] = bounds.minY;
                }

                Vec3 center = voxelShape.bounds().getCenter();
                BlockPos hitBlockPos = blockPosFromVec3(center);
                BlockState blockState = level.getBlockState(hitBlockPos); // Get block state

                // Store the collided block types
                collidedBlockStates.add(blockState);

                Collision.CollisionType collisionHere;
                if (blockState.isStickyBlock()) {
                    collisionHere = Collision.CollisionType.SLIME;
                } else {
                    collisionHere = Collision.CollisionType.SOLID;
                }

                if (collisionTypeOrd[0] < collisionHere.ordinal()) {
                    collisionTypeOrd[0] = collisionHere.ordinal();
                }
            });
        }

        Collision.CollisionType impactType = Collision.CollisionType.values()[collisionTypeOrd[0]];
        Collision.CollisionType collisionType = fullstop.actualImpact(impactType);

        // Return collision with block types
        return new Collision(collisionType, highestY[0], lowestY[0], collidedBlockStates);
    }


    private static BlockPos blockPosFromVec3(Vec3 pos) {
        Vec3i vec3i = new Vec3i(floor(pos.x), floor(pos.y), floor(pos.z));
        return new BlockPos(vec3i);
    }

    private static int floor(double r) {
        return (int) Math.floor(r);
    }

    @NotNull
    private static AABB expandAABB(Vec3 direction, AABB b) {
        double dirX = Math.signum(direction.x);
        double dirZ = Math.signum(direction.z);

        // Expand the bounding box infinitesimally in the direction we're checking for collisions

        return new AABB(
                dirX < 0 ? Math.nextDown(b.minX) : b.minX,
                b.minY,
                dirZ < 0 ? Math.nextDown(b.minZ) : b.minZ,
                dirX > 0 ? Math.nextUp(b.maxX) : b.maxX,
                b.maxY,
                dirZ > 0 ? Math.nextUp(b.maxZ) : b.maxZ
        );
    }

    public void bounceEntity() {
        if (collision.fake() || (!collision.sticky() && fullstop.getStoppingForce() < 9)) return;
        if (damage == 0 && !entity.level().isClientSide
                && (entity.hasControllingPassenger() || entity instanceof Player))
        {
            return;
        }

        Vec3 preV = fullstop.getPreviousVelocity();
        Vec3 curV = fullstop.getCurrentVelocity();
        double perpScaleFactor, paraScaleFactor;

        Collision.CollisionType horizontalImpactType = collision.collisionType;

        if (horizontalImpactType == Collision.CollisionType.SLIME) {
            perpScaleFactor = -1.0;
            paraScaleFactor = 1.0;
        } else if (horizontalImpactType == Collision.CollisionType.HONEY) {
            perpScaleFactor = -0.0;
            paraScaleFactor = 0.0;
        } else if (damage > 0) {
            perpScaleFactor = -0.75 / Math.sqrt(Math.max(damage, 1));
            paraScaleFactor = 1.0 / Math.sqrt(damage);
        } else {
            perpScaleFactor = -0.5;
            paraScaleFactor = 0.5;
        }

        double aCurVX = Math.abs(curV.x), aCurVZ = Math.abs(curV.z);
        double aPreVX = Math.abs(preV.x), aPreVZ = Math.abs(preV.z);
        Vec3 newV = (aCurVZ == aCurVX ?
                new Vec3(
                        preV.x * (aPreVX > aPreVZ ? perpScaleFactor : paraScaleFactor),
                        curV.y,
                        preV.z * (aPreVZ > aPreVX ? perpScaleFactor : paraScaleFactor)
                )
                :
                new Vec3(
                        preV.x * (aCurVX < aCurVZ ? perpScaleFactor : paraScaleFactor),
                        curV.y,
                        preV.z * (aCurVZ < aCurVX ? perpScaleFactor : paraScaleFactor)
                )
        );
        entity.setDeltaMovement(newV.scale(0.05));

        if (horizontalImpactType == Collision.CollisionType.SLIME) {
            double newAngle = Math.atan2(-newV.x, newV.z) / Math.PI * 180;
            fullstop.setTargetAngle(newAngle);
        }
    }

    public static double angleWrap(double angle) {
        angle += 180;
        angle %= 360;
        if (angle < 0) {
            angle += 360;
        }
        return angle - 180;
    }

    private double calcDamage() {
        if (
            !(entity instanceof LivingEntity living) || isDamageImmune(living) ||
                !fullstop.isMostlyDownward() &&
                collision.collisionType != Collision.CollisionType.SOLID

        ) return 0;
        double delta = fullstop.getStoppingForce();
//        if (delta > 3)
        double damage = Math.max(delta - 12.77, 0);

        if (damage <= 0) return 0;
        int fallProtLevel = living.getItemBySlot(EquipmentSlot.FEET)
                .getEnchantmentLevel(Enchantments.FALL_PROTECTION);
        return (float) (damage * 1.07)
                / (1 + fallProtLevel * 0.2);

    }
    public void applyDamage() {
            if (damage <= 0) return;

            DamageSources sources = entity.damageSources();
            if (fullstop.isMostlyDownward()) {
                entity.hurt(sources.fall(), (float) damage);
            } else {
                entity.hurt(sources.flyIntoWall(), (float) damage);
            }
    }

    public Physics(Entity entity) {
        fullstop = grabCapability(entity);
        fullstop.tick(entity);

        this.entity = entity;
        collision = collidingKinetically();
        damage = calcDamage();
    }

    public static boolean unphysable(Entity entity) {
        if (entity.noPhysics) return true;
        if (entity instanceof LivingEntity livingEntity)
            if (livingEntity.isDeadOrDying())
                return true;
        return entity.isRemoved();
    }
}