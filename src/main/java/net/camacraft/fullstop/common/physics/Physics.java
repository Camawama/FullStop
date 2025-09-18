package net.camacraft.fullstop.common.physics;

import net.camacraft.fullstop.client.message.LogToChat;
import net.camacraft.fullstop.client.render.RaycastParticleRenderer;
import net.camacraft.fullstop.client.render.CollisionParticleRenderer;
import net.camacraft.fullstop.client.sound.SoundPlayer;
import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.effects.ModEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.capabilities.FullStopCapability.Provider.DELTAV_CAP;
import static net.camacraft.fullstop.common.capabilities.FullStopCapability.grabCapability;
import static net.camacraft.fullstop.common.physics.Physics.EntityUtils.velocitiesAreSimilar;

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

        if (attacker instanceof LivingEntity living) {
            ItemStack item = living.getItemInHand(InteractionHand.MAIN_HAND);
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
        if (fullstop.getStoppingForce() < 4.0) return;

        Vec3 pos = entity.position();
        Vec3 highestPos = new Vec3(pos.x, highestY, pos.z);
        Vec3 lowestPos = new Vec3(pos.x, lowestY, pos.z);
        Vec3 midPos = highestPos.add(lowestPos).scale(0.5);

        for (BlockState blockState : collision.blockStates) {
            CollisionParticleRenderer.spawnParticle(pos, collision, blockState);
        }

//        spawnParticle(highestPos, collision);
//        spawnParticle(midPos, collision);
//        spawnParticle(pos, collision);
    }

    public void impactSound() {
        float volume = ((float) (fullstop.getStoppingForce() * 0.05f));

        if (collision.blockStates != null && !collision.blockStates.isEmpty()) {
            volume /= (collision.blockStates.size() + 1); // +1 avoids over-shrinking
        }

        if (collision.collidingEntities != null && !collision.collidingEntities.isEmpty()) {
            volume /= (collision.collidingEntities.size() + 1);
        }

        volume = Mth.clamp(volume, 0.0f, 2.0f);

        float minPitch = 0.9f;
        float maxPitch = 1.7f;
        float pitch = (float) Mth.clamp(minPitch + (fullstop.getStoppingForce() / 100f) * (maxPitch - minPitch), minPitch, maxPitch);

        if (collision.fake()) return;

        if (fullstop.getStoppingForce() <= 6.0) return;

        if (!(entity instanceof LivingEntity) && collision.collisionType == Collision.CollisionType.ENTITY) return;

        for (Entity collidedEntity : collision.collidingEntities) {
            SoundPlayer.playSound(collidedEntity, SoundEvents.BOOK_PUT, volume, pitch); // This sound works surprisingly well for entity collision!
        }

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
            if (fullstop.getRunningAverageDelta() > 5.0) {
                livingEntity.addEffect(new MobEffectInstance(
                        MobEffects.BLINDNESS, 30, 0, false, false));
            }

            if (fullstop.getRunningAverageDelta() > 2.0) {
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
        if (fullstop.getIsDamageImmune()) return;
        if (entity instanceof LivingEntity livingEntity) {
            int fallProtLevel = livingEntity.getItemBySlot(EquipmentSlot.FEET).getEnchantmentLevel(Enchantments.FALL_PROTECTION);

            if (fullstop.isMostlyDownward()) {
                livingEntity.addEffect(new MobEffectInstance(ModEffects.SPRAIN.get(),
                        (int) (damage * 5 * (1.0 - fallProtLevel * 0.2)), 0, false, false));
            } else {
                livingEntity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,
                        (int) (damage * 5 * (1.0 - fallProtLevel * 0.2)), (int) ((damage / 2) * (1.0 - fallProtLevel * 0.2)), false, false));
            }
            livingEntity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                    (int) (damage * 5 * (1.0 - fallProtLevel * 0.2)), (int) ((damage / 2) * (1.0 - fallProtLevel * 0.2)), false, false));
        }
    }

    public Collision collidingKinetically() {
        Vec3 previousVelocity = fullstop.getPreviousVelocity().scale(0.05);
        if (previousVelocity.lengthSqr() == 0 || fullstop.getStoppingForce() == 0) {
            return Collision.NONE;
        }

        Level level = entity.level();
        AABB box = entity.getBoundingBox();
        Vec3 direction = fullstop.getAcceleration().normalize().reverse();

        // Limit ray length to just ahead of the bounding box face
        double rayLength = Math.min(previousVelocity.length(), 0.01); // clamp ~half block ahead

        // 8 corners + center
        List<Vec3> rayStarts = List.of(
                new Vec3(box.minX, box.minY + 0.15, box.minZ),
                new Vec3(box.minX, box.minY + 0.15, box.maxZ),
                new Vec3(box.minX, box.maxY - 0.15, box.minZ),
                new Vec3(box.minX, box.maxY - 0.15, box.maxZ),
                new Vec3(box.maxX, box.minY + 0.15, box.minZ),
                new Vec3(box.maxX, box.minY + 0.15, box.maxZ),
                new Vec3(box.maxX, box.maxY - 0.15, box.minZ),
                new Vec3(box.maxX, box.maxY - 0.15, box.maxZ),
                box.getCenter()
        );

        ArrayList<BlockState> collidedBlockStates = new ArrayList<>();
        double highestY = -64;
        double lowestY = 320;
        Collision.CollisionType impactType = Collision.CollisionType.NONE;

        // --- Block ray sweeps ---
        for (Vec3 start : rayStarts) {
            Vec3 end = start.add(direction.scale(rayLength));
            ClipContext ctx = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity);
            BlockHitResult blockHit = level.clip(ctx);

            if (blockHit.getType() == HitResult.Type.BLOCK) {
                BlockPos hitPos = blockHit.getBlockPos();

                // only run client-side visual debug
                if (level.isClientSide) {
                    boolean hitBlock = blockHit.getType() == HitResult.Type.BLOCK;
                    RaycastParticleRenderer.spawnRay(level, start, end, hitBlock, 12); // steps = 12 for denser line
                }


                BlockState hitState = level.getBlockState(hitPos);

                if (!collidedBlockStates.contains(hitState)) {
                    collidedBlockStates.add(hitState);
                }

                highestY = Math.max(highestY, hitPos.getY() + 1);
                lowestY = Math.min(lowestY, hitPos.getY());

                Collision.CollisionType typeHere;
                if (hitState.isStickyBlock()) {
                    if (hitState.is(Blocks.SLIME_BLOCK)) {
                        typeHere = Collision.CollisionType.SLIME;
                    } else {
                        typeHere = Collision.CollisionType.HONEY;
                    }
                } else {
                    typeHere = Collision.CollisionType.SOLID;
                }

                if (impactType.ordinal() < typeHere.ordinal()) {
                    impactType = typeHere;
                }
            }
        }

        // --- Entity collisions (local AABB only) ---
        List<Entity> collidingEntities = Collections.emptyList();
        if (SERVER.entityCollisionDamage.get()) {
            AABB entityCheckBox = box.inflate(0.1); // only slightly larger than entity’s box
            collidingEntities = level.getEntities(
                    entity,
                    entityCheckBox,
                    e -> (e instanceof LivingEntity || e instanceof Boat || e instanceof AbstractMinecart)
                            && e != entity
                            && !(entity instanceof ItemEntity && ((ItemEntity) entity).getOwner() == e)
            );

            if (!collidingEntities.isEmpty()) {
                impactType = Collision.CollisionType.ENTITY;
            }
        }


        return new Collision(impactType, highestY, lowestY, collidedBlockStates, collidingEntities);
    }

//    public Collision collidingKinetically() {
//        AABB boundingBox = entity.getBoundingBox();
//
//        // Get the normalized direction from previous velocity
//        Vec3 previousVelocity = fullstop.getPreviousVelocity();
//        Vec3 direction = previousVelocity.normalize();
//
//        // If not moving, return NONE
//        if (direction.lengthSqr() == 0 || fullstop.getStoppingForce() == 0) {
//            return Collision.NONE;
//        }
//
//        AABB expandedBox = expandAABB(direction, boundingBox);
//
//        @SuppressWarnings("resource")
//        Level level = entity.level();
//
//        double[] highestY = {-64};
//        double[] lowestY = {320};
//        boolean[] blocks_here = {false};
//        int[] collisionTypeOrd = {Collision.CollisionType.NONE.ordinal()};
//
//        // Variable to store the block type we collided with
//        ArrayList<BlockState> collidedBlockStates = new ArrayList<>(6);
//
//        // --- Pass 1: mark honey/slime and force honey into collided list ---
//        level.getBlockStates(expandedBox).forEach(blockState -> {
//            if (blockState.isStickyBlock() && !blockState.isSlimeBlock()) {
//                // Found honey -> mark type
//                collisionTypeOrd[0] = Math.max(collisionTypeOrd[0], Collision.CollisionType.HONEY.ordinal());
//                collidedBlockStates.add(blockState); // ensure honey is logged
//            }
//            blocks_here[0] = true;
//        });
//
//        if (blocks_here[0]) {
//            // --- Pass 2: normal solid/slime collision collection ---
//            level.getBlockCollisions(entity, expandedBox).forEach(voxelShape -> {
//                AABB bounds = voxelShape.bounds();
//
//                if (bounds.maxY > highestY[0]) {
//                    highestY[0] = bounds.maxY;
//                }
//                if (bounds.minY < lowestY[0]) {
//                    lowestY[0] = bounds.minY;
//                }
//
//                Vec3 center = voxelShape.bounds().getCenter();
//                BlockPos hitBlockPos = blockPosFromVec3(center);
//                BlockState blockState = level.getBlockState(hitBlockPos);
//
//                // Store collided block types (avoid duplicates)
//                if (!collidedBlockStates.contains(blockState)) {
//                    collidedBlockStates.add(blockState);
//                }
//
//                Collision.CollisionType collisionHere;
//                if (blockState.isStickyBlock()) {
//                    collisionHere = Collision.CollisionType.SLIME;
//                } else {
//                    collisionHere = Collision.CollisionType.SOLID;
//                }
//
//                if (collisionTypeOrd[0] < collisionHere.ordinal()) {
//                    collisionTypeOrd[0] = collisionHere.ordinal();
//                }
//            });
//        }
//
//        List<Entity> collidingEntities = Collections.emptyList();
//
//        if (SERVER.entityCollisionDamage.get()) {
//            collidingEntities = level.getEntities(
//                    entity,
//                    expandedBox,
//                    e -> (e instanceof LivingEntity || e instanceof Boat || e instanceof AbstractMinecart) &&
//                            e != entity &&
//                            !(entity instanceof ItemEntity && ((ItemEntity) entity).getOwner() == e)
//            );
//
//            if (!collidingEntities.isEmpty()) {
//                collisionTypeOrd[0] = Math.max(collisionTypeOrd[0], Collision.CollisionType.ENTITY.ordinal());
//            }
//        }
//
//        Collision.CollisionType impactType = Collision.CollisionType.values()[collisionTypeOrd[0]];
//
////        // Debug log (optional)
////        if (entity instanceof ServerPlayer) {
////            LogToChat.logToChat(collidedBlockStates);
////        }
//
//        // Return collision with block types or entities
//        return new Collision(impactType, highestY[0], lowestY[0], collidedBlockStates, collidingEntities);
//    }


//    public Collision collidingKinetically() {
//        AABB boundingBox = entity.getBoundingBox();
//
//        // Get the normalized direction from previous velocity
//        Vec3 previousVelocity = fullstop.getPreviousVelocity();
//        Vec3 direction = previousVelocity.normalize();
//
//        // If not moving, return NONE
//        if (direction.lengthSqr() == 0 || fullstop.getStoppingForce() == 0) {
//            return Collision.NONE;
//        }
//
//        AABB expandedBox = expandAABB(direction, boundingBox);
//
//        @SuppressWarnings("resource")
//        Level level = entity.level();
//
//        double[] highestY = {-64};
//        double[] lowestY = {320};
//        boolean[] blocks_here = {false};
//        int[] collisionTypeOrd = {Collision.CollisionType.NONE.ordinal()};
//
//        // Variable to store the block type we collided with
//        ArrayList<BlockState> collidedBlockStates = new ArrayList<>(6);
//
//        level.getBlockStates(expandedBox).forEach(blockState -> {
//            if (blockState.isStickyBlock() && !blockState.isSlimeBlock()) {
//                collisionTypeOrd[0] = Collision.CollisionType.HONEY.ordinal();
//            }
//            blocks_here[0] = true;
//        });
//
//        if (blocks_here[0]) {
//            level.getBlockCollisions(entity, expandedBox).forEach(voxelShape -> {
//                AABB bounds = voxelShape.bounds();
//
//                if (bounds.maxY > highestY[0]) {
//                    highestY[0] = bounds.maxY;
//                }
//                if (bounds.minY < lowestY[0]) {
//                    lowestY[0] = bounds.minY;
//                }
//
//                Vec3 center = voxelShape.bounds().getCenter();
//                BlockPos hitBlockPos = blockPosFromVec3(center);
//                BlockState blockState = level.getBlockState(hitBlockPos); // Get block state
//
//                // Store the collided block types
//                collidedBlockStates.add(blockState);
//
//                Collision.CollisionType collisionHere;
//                if (blockState.isStickyBlock()) {
//                    collisionHere = Collision.CollisionType.SLIME;
//                } else {
//                    collisionHere = Collision.CollisionType.SOLID;
//                }
//
//                if (collisionTypeOrd[0] < collisionHere.ordinal()) {
//                    collisionTypeOrd[0] = collisionHere.ordinal();
//                }
//            });
//        }
//
//        List<Entity> collidingEntities = Collections.emptyList();
//
//        if (SERVER.entityCollisionDamage.get()) { //SERVER.entityCollisionDamage will be split in the future
//            collidingEntities = level.getEntities(
//                    entity,
//                    expandedBox,
//                    e -> (e instanceof LivingEntity || e instanceof Boat || e instanceof AbstractMinecart) &&
//                            e != entity &&
//                            !(entity instanceof ItemEntity && ((ItemEntity) entity).getOwner() == e)
//            );
//
//            if (!collidingEntities.isEmpty()) {
//                collisionTypeOrd[0] = Math.max(collisionTypeOrd[0], Collision.CollisionType.ENTITY.ordinal());
//            }
//        }
//
//        Collision.CollisionType impactType = Collision.CollisionType.values()[collisionTypeOrd[0]];
//
////        if (entity instanceof ServerPlayer) {
////            LogToChat.logToChat(collidedBlockStates);
////        }
//
//        // Return collision with block types or entities
//        return new Collision(impactType, highestY[0], lowestY[0], collidedBlockStates, collidingEntities);
//    }

//    private static BlockPos blockPosFromVec3(Vec3 pos) {
//        Vec3i vec3i = new Vec3i(floor(pos.x), floor(pos.y), floor(pos.z));
//        return new BlockPos(vec3i);
//    }

    private static int floor(double r) {
        return (int) Math.floor(r);
    }

//    @NotNull
//    private static AABB expandAABB(Vec3 direction, AABB b) {
//        double dirX = Math.signum(direction.x);
//        double dirZ = Math.signum(direction.z);
//
//        // Expand the bounding box infinitesimally in the direction we're checking for collisions
//        return new AABB(
//                dirX < 0 ? Math.nextDown(b.minX) : b.minX,
//                b.minY,
//                dirZ < 0 ? Math.nextDown(b.minZ) : b.minZ,
//                dirX > 0 ? Math.nextUp(b.maxX) : b.maxX,
//                b.maxY,
//                dirZ > 0 ? Math.nextUp(b.maxZ) : b.maxZ
//        );
//    }

    private static boolean isInPassengerChain(Entity possibleAncestor, Entity possibleDescendant) {
        // Returns true if possibleDescendant is a passenger (directly or indirectly) of possibleAncestor.
        Entity v = possibleDescendant;
        while (v != null) {
            Entity vehicle = v.getVehicle();
            if (vehicle == null) return false;
            if (vehicle == possibleAncestor) return true;
            v = vehicle;
        }
        return false;
    }

    private double getEntityWeight(Entity entity) {
        AABB box = entity.getBoundingBox();
        double entityVolume = (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);

        if (entity instanceof LivingEntity living && living.isFallFlying()) {
            return entityVolume * 3;
        }

        if (entity instanceof IronGolem) {
            return entityVolume * 8;
        } else if (entity instanceof Skeleton || entity instanceof SkeletonHorse) {
            return entityVolume / 3;
        }

        return entityVolume;
    }

    private boolean tryStartRidingSafely(Entity rider, Entity vehicle, double velocitySimilarityThreshold) {
        if (rider == null || vehicle == null) return false;
        if (rider.level().isClientSide()) return false; // server-only behavior
        if (!rider.isAlive() || !vehicle.isAlive()) return false;
        if (rider == vehicle) return false;

        // Weight check
        if (getEntityWeight(rider) >= getEntityWeight(vehicle)) return false;

        // Basic already-riding checks
        if (rider.getVehicle() == vehicle) return false;          // already riding this vehicle
        if (vehicle.getVehicle() == rider) return false;          // vehicle is riding rider (immediate cycle)
        if (!vehicle.getPassengers().isEmpty()) return false;
        if (rider.isPassengerOfSameVehicle(vehicle)) return false; // same vehicle chain already

        // Ride cooldown check
        if (grabCapability(rider).getDismountCooldown() > 0) return false;

        // Prevent deeper cycles: ensure rider isn't somewhere in vehicle's chain and vice versa
        if (isInPassengerChain(rider, vehicle) || isInPassengerChain(vehicle, rider)) return false;

        // Optional velocity similarity check (pass <= 0 to disable)
        if (velocitySimilarityThreshold > 0) {
            Vec3 v1 = grabCapability(rider).getCurrentVelocity();
            Vec3 v2 = grabCapability(vehicle).getCurrentVelocity();
            if (velocitiesAreSimilar(v1, v2, velocitySimilarityThreshold)) {
                return false;
            }
        }

        // Finally, attempt to mount
        return rider.startRiding(vehicle, true);
    }

    // Now the refactored handleEntityCollision method:

    private void handleEntityCollision() {
        if (!SERVER.entityCollisionDamage.get()) return;
        if (collision.collisionType != Collision.CollisionType.ENTITY) return;

        // Small early exit to avoid extra work
        Vec3 currentVelocity = fullstop.getCurrentVelocity();
        double currentSpeed = currentVelocity.length();
        if (currentSpeed < 5.0) return;

        Level level = entity.level();
        boolean isServer = !level.isClientSide();

        // Cache previous velocity and direction (used for transfers)
        Vec3 prevVelocity = fullstop.getPreviousVelocity(); // used for transfer
        double velocitySimilarityThreshold = 0.1;

        // Make sure entity is living (you said add for non-living later)
        if (!(entity instanceof LivingEntity)) return;

        // Iterate the collided entities safely
        for (Entity collidedEntityRaw : collision.collidingEntities) {
            if (collidedEntityRaw == null) continue;
            if (collidedEntityRaw == entity) continue;
            if (collidedEntityRaw.isRemoved() || !collidedEntityRaw.isAlive()) continue;

            // Short-circuit: if we already ended up mounting earlier in this method, stop.
            // (we will return after a successful mount below)

            // Distinguish by type
            if (collidedEntityRaw instanceof LivingEntity collidedLiving) {
                // caches
                Vec3 entityVel = grabCapability(entity).getCurrentVelocity();
                Vec3 collidedVel = grabCapability(collidedLiving).getCurrentVelocity();

                // If collided is player: stop the hitter immediately (special-case)
                if (collidedLiving instanceof Player) {
                    if (isServer) {
                        entity.setDeltaMovement(Vec3.ZERO);
                        entity.hasImpulse = true;
                    }
                    // do not attempt riding players further
                    continue;
                }

                // If the main entity is mostly downward (falling into collided entity)
                if (isServer && fullstop.isMostlyDownward() && !fullstop.isMostlyUpward()) {
                    // prefer to mount the collided entity if it has no passengers
                    if (collidedLiving.getPassengers().isEmpty()) {
                        if (!entity.isCrouching()) {
                            if (!velocitiesAreSimilar(entityVel, collidedVel, velocitySimilarityThreshold)) {
                                boolean mounted = tryStartRidingSafely(entity, collidedLiving, velocitySimilarityThreshold);
                                if (mounted) {
                                    // transfer some small portion of previous velocity to target
                                    if (!(collidedLiving instanceof IronGolem)) {
                                        collidedLiving.setDeltaMovement(prevVelocity.scale(0.05));
                                    }
                                    // stop the hitter
                                    entity.setDeltaMovement(Vec3.ZERO);
                                    grabCapability(entity).setCurrentVelocity(Vec3.ZERO);
                                    entity.hasImpulse = true;
                                    return; // mounted one, stop processing
                                }
                            }
                        }
                    } else { // collided has passengers: try to mount first passenger instead
                        Entity firstPassenger = collidedLiving.getPassengers().stream().findFirst().orElse(null);
                        if (firstPassenger != null) {
                            boolean mounted = tryStartRidingSafely(entity, firstPassenger, velocitySimilarityThreshold);
                            if (mounted) {
                                if (!(firstPassenger instanceof IronGolem)) {
                                    firstPassenger.setDeltaMovement(prevVelocity.scale(0.05));
                                }
                                entity.setDeltaMovement(Vec3.ZERO);
                                grabCapability(entity).setCurrentVelocity(Vec3.ZERO);
                                entity.hasImpulse = true;
                                return;
                            }
                        }
                    }
                }

                // Upward collisions: someone hitting the main entity from below (swap roles)
                if (isServer && !fullstop.isMostlyDownward() && fullstop.isMostlyUpward()) {
                    // avoid immediate cycles: use tryStartRidingSafely
                    if (entity.getPassengers().isEmpty()) {
                        boolean mounted = tryStartRidingSafely(collidedLiving, entity, velocitySimilarityThreshold);
                        if (mounted) {
                            // transfer velocity to collided
                            if (!(collidedLiving instanceof IronGolem)) {
                                collidedLiving.setDeltaMovement(prevVelocity.scale(0.05));
                            }
                            entity.setDeltaMovement(Vec3.ZERO);
                            grabCapability(entity).setCurrentVelocity(Vec3.ZERO);
                            entity.hasImpulse = true;
                            return;
                        }
                    } else {
                        Entity firstPassenger = entity.getPassengers().stream().findFirst().orElse(null);
                        if (firstPassenger != null) {
                            boolean mounted = tryStartRidingSafely(collidedLiving, firstPassenger, velocitySimilarityThreshold);
                            if (mounted) {
                                if (!(collidedLiving instanceof IronGolem)) {
                                    collidedLiving.setDeltaMovement(prevVelocity.scale(0.05));
                                }
                                entity.setDeltaMovement(Vec3.ZERO);
                                grabCapability(entity).setCurrentVelocity(Vec3.ZERO);
                                entity.hasImpulse = true;
                                return;
                            }
                        }
                    }
                }

                // If they are not the same passenger-vehicle chain, apply velocity transfer and stop the hitter.
                if (!entity.isPassengerOfSameVehicle(collidedLiving)) {
                    if (!(collidedLiving instanceof IronGolem)) {
                        collidedLiving.setDeltaMovement(prevVelocity.scale(0.05));
                    }
                    entity.setDeltaMovement(Vec3.ZERO);
                    grabCapability(entity).setCurrentVelocity(Vec3.ZERO);
                    entity.hasImpulse = true;
                }

            } else if (collidedEntityRaw instanceof AbstractMinecart minecart) {
                if (!entity.isCrouching() && isServer && fullstop.isMostlyDownward() && !fullstop.isMostlyUpward() && minecart.getPassengers().isEmpty()) {
                    boolean mounted = tryStartRidingSafely(entity, minecart, velocitySimilarityThreshold);
                    if (mounted) {
                        minecart.setDeltaMovement(prevVelocity.scale(0.05));
                        entity.setDeltaMovement(Vec3.ZERO);
                        grabCapability(entity).setCurrentVelocity(Vec3.ZERO);
                        entity.hasImpulse = true;
                        return;
                    }
                }
            } else if (collidedEntityRaw instanceof Boat boat) {
                if (!entity.isCrouching() && isServer && fullstop.isMostlyDownward() && !fullstop.isMostlyUpward() && boat.getPassengers().isEmpty()) {
                    boolean mounted = tryStartRidingSafely(entity, boat, velocitySimilarityThreshold);
                    if (mounted) {
                        boat.setDeltaMovement(prevVelocity.scale(0.15));
                        entity.setDeltaMovement(Vec3.ZERO);
                        grabCapability(entity).setCurrentVelocity(Vec3.ZERO);
                        entity.hasImpulse = true;
                        return;
                    }
                }
            } // other types: ignore
        } // end for
    }


    public class EntityUtils {

        /**
         * Returns true if two velocity vectors are "too similar"
         * i.e., the entity shouldn't mount the other if they're moving together.
         *
         * @param v1 velocity of main entity
         * @param v2 velocity of collided entity
         * @param threshold how close the velocities can be (length difference or angle)
         */
        public static boolean velocitiesAreSimilar(Vec3 v1, Vec3 v2, double threshold) {
            // Option 1: simple distance between velocity vectors
            return v1.distanceTo(v2) < threshold;

            // Option 2 (more precise, checks direction similarity):
            // double dot = v1.normalize().dot(v2.normalize());
            // return dot > 0.95; // nearly same direction
        }
    }

    public void bounceEntity() {
        handleEntityCollision();
        if (entity.isCrouching() || collision.fake() || (!collision.sticky() && fullstop.getStoppingForce() < 9)) return; //added entity.IsCrouching()

        if (damage == 0 && !entity.level().isClientSide
                && (entity.hasControllingPassenger() || entity instanceof Player)) {
            return;
        }

        if (fullstop.isMostlyUpward() || fullstop.isMostlyDownward()) return; // BAND-AID FIX TO PREVENT BOUNCING WHEN FALLING OR HITTING THE UNDERSIDE OF BLOCKS. THIS WILL HAVE TO BE REPLACED WHEN ADDING SLIME BLOCKS BOUNCING ENTITIES DOWNWARD TOO

        Vec3 preV = fullstop.getPreviousVelocity();
        Vec3 curV = fullstop.getCurrentVelocity();
        double perpScaleFactor, paraScaleFactor;

        Collision.CollisionType horizontalImpactType = collision.collisionType;

        if (horizontalImpactType == Collision.CollisionType.SLIME) {
            perpScaleFactor = -1.0;
            paraScaleFactor = 1.0;
        } else if (horizontalImpactType == Collision.CollisionType.HONEY) {
            perpScaleFactor = -0.0; // HONEY MUST BE ZERO
            paraScaleFactor = 0.0; // HONEY MUST BE ZERO
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
//        grabCapability(entity).setCurrentVelocity(newV.scale(0.05));

        if (!SERVER.rotateCamera.get()) { //returns if config is false so entity doesn't rotate when bouncing but should probably make this a client config
            return;
        }

        if (fullstop.getStoppingForce() < 3.0) return;

        if (entity instanceof Player) {
            if (((Player) entity).getAbilities().flying) return;
        }

        double newAngle = Math.atan2(-newV.x, newV.z);
        double currentAngle = Math.atan2(-curV.x, curV.z);

        double targetAngle = switch (horizontalImpactType) {
            case NONE -> 0.0;
            case SLIME -> newAngle;
            case HONEY -> Double.NaN;
            case SOLID -> Double.NaN;
            case ENTITY -> Double.NaN;
        } / Math.PI * 180;

        fullstop.setTargetAngle(targetAngle);
    }

    public static double angleWrap(double angle) {
        angle += 180;
        angle %= 360;
        if (angle < 0) {
            angle += 360;
        }
        return angle - 180;
    }

    private double calcKineticDamage() {

        if (entity instanceof LivingEntity && entity instanceof ServerPlayer) {

//            if (fullstop.isMostlyUpward()) {
//                LogToChat.logToChat("is mostly upward");
//            } else if (fullstop.isMostlyDownward()) {
//                LogToChat.logToChat("is mostly downward");
//            } else {
//                LogToChat.logToChat("is mostly horizontal");
//            }

//            LogToChat.logToChat(collision.collisionType);

        }

//        if (!entity.isAttackable()) {
//            return 0;
//        }

        if (!(entity instanceof LivingEntity living) || isDamageImmune(living)) {
            return 0;
        }

        if (entity instanceof Mob mob) {
            if (mob.isLeashed() && fullstop.isMostlyDownward() && collision.fake()) return 0;
        }

        if (!fullstop.isMostlyDownward() && collision.collisionType != Collision.CollisionType.SOLID && collision.collisionType != Collision.CollisionType.ENTITY) return 0;

        if (living.isSwimming() && living.isInWater()) return 0; //attempt to prevent damage when the player is in the swimming state and in water

        if (fullstop.getIsDamageImmune()) return 0;

        if (collision.collisionType == Collision.CollisionType.ENTITY) {
            if (!SERVER.entityCollisionDamage.get()) {
                return 0;
            }
        }

        if (entity instanceof Horse && damage < 1.0) { // BAND-AID FIX FOR HORSE DAMAGE
            return 0;
        }

        double delta = fullstop.getStoppingForce();

        double damage;
        if (!fullstop.isMostlyDownward()) {
            damage = Math.max(delta - SERVER.velocityDamageThresholdHorizontal.get(), 0);
        } else {
            damage = Math.max(delta - SERVER.velocityDamageThresholdVertical.get(), 0);
        }


        if (damage <= 0) return 0;

        int fallProtLevel = living.getItemBySlot(EquipmentSlot.FEET).getEnchantmentLevel(Enchantments.FALL_PROTECTION);

        MobEffectInstance jumpBoostEffect = living.getEffect(MobEffects.JUMP);
        int jumpBoostLevel;

        if (jumpBoostEffect == null) {
            jumpBoostLevel = 0;
        } else {
            jumpBoostLevel = jumpBoostEffect.getAmplifier() + 1;
        }

        if (fullstop.isMostlyDownward() && !fullstop.isMostlyUpward()) {
            damage -= jumpBoostLevel;
            if (damage <= 0) return 0;
        }

        damage = damage / 0.648 * getEntityWeight(entity);

        return (float) (damage * 1.07) / (1 + fallProtLevel * 0.2);
    }

    private static int lerpColor(int color1, int color2, double t) {
        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;

        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8) & 0xFF;
        int b2 = color2 & 0xFF;

        int r = (int)(r1 + (r2 - r1) * t);
        int g = (int)(g1 + (g2 - g1) * t);
        int b = (int)(b1 + (b2 - b1) * t);

        return (r << 16) | (g << 8) | b;
    }

    public void applyKineticDamage() {
        double previousVelocity = fullstop.getPreviousVelocity().length();
        if (damage < 1) return;

        // Decide on the color dynamically based on velocity
        TextColor color;
        double maxVelocity = 78.40;
        double t = Math.min(previousVelocity / maxVelocity, 1.0);

        int green   = 0x00FF00;
        int yellow  = 0xFFFF00;
        int red     = 0xFF0000;
        int darkRed = 0x800000;

        int rgb;
        if (t < 0.33) {
            double nt = t / 0.33;
            rgb = lerpColor(green, yellow, nt);
        } else if (t < 0.66) {
            double nt = (t - 0.33) / 0.33;
            rgb = lerpColor(yellow, red, nt);
        } else {
            double nt = (t - 0.66) / 0.34;
            rgb = lerpColor(red, darkRed, nt);
        }
        color = TextColor.fromRgb(rgb);

        String velocityToDisplay = String.format("(going %.2f m/s)", previousVelocity);
        DamageSources sources = entity.damageSources();

        // --- Case 1: Vertical / Wall damage applied to self ---
        if (collision.collisionType != Collision.CollisionType.ENTITY) {
            DamageSource baseSource;
            if (fullstop.isMostlyDownward()) {
                baseSource = sources.fall();
            } else if (fullstop.isMostlyUpward()) {
                baseSource = sources.flyIntoWall(); // or a custom "head-hit" damage source if you have one
            } else {
                baseSource = sources.flyIntoWall();
            }


            DamageSource customSource = makeSelfSource(baseSource, velocityToDisplay, color, fullstop.isMostlyDownward(), fullstop.isMostlyUpward());
            entity.hurt(customSource, (float) damage); // full damage since no entity split
        }

        // --- Case 2: Entity collision damage ---
        if (collision.collisionType == Collision.CollisionType.ENTITY && collision.collidingEntities != null) {
            // Filter out Iron Golems for damage purposes
            List<LivingEntity> validTargets = collision.collidingEntities.stream()
                    .filter(e -> e instanceof LivingEntity)
                    .map(e -> (LivingEntity) e)
                    .filter(living -> !(living instanceof IronGolem))
                    .toList();

            // Collect golems for sound only
            List<IronGolem> collidedGolems = collision.collidingEntities.stream()
                    .filter(e -> e instanceof IronGolem)
                    .map(e -> (IronGolem) e)
                    .toList();

            for (IronGolem golem : collidedGolems) {
                // Example scaling factors
                float baseVolume = 0.6f;
                float basePitch = 1.0f;

                // Scale volume linearly with damage, but clamp it
                float volume = baseVolume + (float)damage * 0.05f; // each damage adds 0.05
                volume = clamp(volume, 0.6f, 1.8f); // don’t let it get too quiet or too loud

                // Pitch goes *down* as damage increases
                float pitch = basePitch - (float)damage * 0.02f;
                pitch = clamp(pitch, 0.7f, 1.2f); // reasonable range

                SoundPlayer.playSound(entity, SoundEvents.IRON_GOLEM_HURT, volume, pitch);
            }

            // Pick ANY collided entity (even Iron Golem) just for death message
            LivingEntity collidedExample = collision.collidingEntities.stream()
                    .filter(e -> e instanceof LivingEntity)
                    .map(e -> (LivingEntity) e)
                    .findFirst()
                    .orElse(null);

            int colliders = validTargets.size();
            float entityDamage = (colliders == 0) ? (float) damage : (float) damage / (colliders + 1);

            if (!entity.onGround() && !collision.collidingEntities.stream().findFirst().get().onGround()) {
                if (velocitiesAreSimilar(entity.getDeltaMovement(), collision.collidingEntities.stream().findFirst().get().getDeltaMovement(), 0.1)) {
                    entityDamage = 0;
                }
            }

            // --- Always apply damage to self ---
            DamageSource selfSource;
            if (collidedExample != null) {
                selfSource = makeEntityCollisionSelfSource(
                        sources.flyIntoWall(),
                        (LivingEntity) entity,
                        collidedExample,
                        velocityToDisplay,
                        color,
                        fullstop.isMostlyDownward(),
                        fullstop.isMostlyUpward()
                );
            } else {
                selfSource = makeSelfSource(
                        sources.flyIntoWall(),
                        velocityToDisplay,
                        color,
                        fullstop.isMostlyDownward(),
                        fullstop.isMostlyUpward()
                );
            }
            entity.hurt(selfSource, entityDamage);

            // --- Apply damage to valid targets only (Iron Golems excluded) ---
            for (LivingEntity target : validTargets) {
                DamageSource attackerSource = makeEntityAttackerSource(
                        sources,
                        (LivingEntity) entity,
                        velocityToDisplay,
                        color,
                        fullstop.isMostlyDownward()
                );
                target.hurt(attackerSource, entityDamage);
            }
        }

    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @NotNull
    private static DamageSource makeSelfSource(DamageSource baseSource,
                                               String velocityToDisplay,
                                               TextColor color,
                                               boolean isMostlyDownward,
                                               boolean isMostlyUpward) {
        return new DamageSource(baseSource.typeHolder()) {
            @Override
            public Component getLocalizedDeathMessage(LivingEntity victim) {

                Component velocityComponent = Component.literal(" " + velocityToDisplay)
                        .withStyle(Style.EMPTY.withColor(color));

                if (isMostlyDownward) {
                    // Vanilla fall message + velocity
                    Component base = super.getLocalizedDeathMessage(victim);

                    boolean hasElytra = FullStopCapability.hasElytraEquipped(victim);
                    Component flyingComponent = hasElytra
                            ? Component.literal(" with Elytra")
                            : Component.empty();

                    return base.copy().append(flyingComponent).append(velocityComponent);

                } else if (isMostlyUpward) {
                    // Upward death message
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" hit their head")
                            .append(velocityComponent);

                } else {
                    // Horizontal kinetic phrasing
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" experienced kinetic energy")
                            .append(velocityComponent);
                }
            }
        };
    }


    @NotNull
    private static DamageSource makeEntityAttackerSource(DamageSources sources,
                                                         LivingEntity attacker,
                                                         String velocityToDisplay,
                                                         TextColor color,
                                                         boolean isMostlyDownward) {
        // Create a vanilla attacker source (keeps the attacker reference for aggro)
        DamageSource base = (attacker instanceof Player p)
                ? sources.playerAttack(p)
                : sources.mobAttack(attacker);

        return new DamageSource(base.typeHolder()) {
            @Override
            public Component getLocalizedDeathMessage(LivingEntity victim) {
                Component attackerName = attacker.getDisplayName()
                        .copy()
                        .withStyle(s -> s.withColor(color));

                Component velocityComponent = Component.literal(" " + velocityToDisplay)
                        .withStyle(Style.EMPTY.withColor(color));

                if (isMostlyDownward) {
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" was crushed by ")
                            .append(attackerName)
                            .append(velocityComponent);
                } else {
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" was hit by ")
                            .append(attackerName)
                            .append(velocityComponent);
                }
            }

            @Override
            public Entity getEntity() {
                return base.getEntity();
            }
        };
    }

    @NotNull
    private static DamageSource makeEntityCollisionSelfSource(DamageSource baseSource,
                                                              LivingEntity victim,
                                                              LivingEntity collided,
                                                              String velocityToDisplay,
                                                              TextColor color,
                                                              boolean isMostlyDownward,
                                                              boolean isMostlyUpward) {
        return new DamageSource(baseSource.typeHolder()) {
            @Override
            public Component getLocalizedDeathMessage(LivingEntity v) {
                // Leave the collided entity's name uncolored
                Component collidedName = collided.getDisplayName();

                // Only the velocity text is colored
                Component velocityComponent = Component.literal(" " + velocityToDisplay)
                        .withStyle(Style.EMPTY.withColor(color));

                if (isMostlyDownward) {
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" fell onto ")
                            .append(collidedName)
                            .append(" too hard")
                            .append(velocityComponent);
                } else if (isMostlyUpward) {
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" hit their head on ")
                            .append(collidedName)
                            .append(velocityComponent);
                } else {
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" slammed into ")
                            .append(collidedName)
                            .append(velocityComponent);
                }
            }
        };
    }

    public Physics(Entity entity) {
        fullstop = grabCapability(entity);

//        if (entity instanceof ServerPlayer) {
//            LogToChat.logToChat(getEntityWeight(entity));
//        }

        fullstop.tick(entity);
        this.entity = entity;
        collision = collidingKinetically();

//        if (collision.collisionType != null) {
//            if (entity instanceof ServerPlayer) {
//                LogToChat.logToChat(collision.collisionType);
//            }
//        }

        damage = calcKineticDamage();
    }

    public static boolean unphysable(Entity entity) {
        if (entity == null) return true;
        if (entity.noPhysics) return true;
        if (entity instanceof LivingEntity livingEntity)
            if (livingEntity.isDeadOrDying())
                return true;
        return entity.isRemoved();
    }
}