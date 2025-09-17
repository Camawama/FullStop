package net.camacraft.fullstop.common.physics;

import net.camacraft.fullstop.client.message.LogToChat;
import net.camacraft.fullstop.client.render.ParticleRenderer;
import net.camacraft.fullstop.client.sound.SoundPlayer;
import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.effects.ModEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
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
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
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
import java.util.Collections;
import java.util.List;

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
            ParticleRenderer.spawnParticle(pos, collision, blockState);
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
        AABB boundingBox = entity.getBoundingBox();

        // Get the normalized direction from previous velocity
        Vec3 previousVelocity = fullstop.getPreviousVelocity();
        Vec3 direction = previousVelocity.normalize();

        // If not moving, return NONE
        if (direction.lengthSqr() == 0 || fullstop.getStoppingForce() == 0) {
            return Collision.NONE;
        }

        AABB expandedBox = expandAABB(direction, boundingBox);

        @SuppressWarnings("resource")
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

        List<Entity> collidingEntities = Collections.emptyList();

        if (SERVER.entityCollisionDamage.get()) { //SERVER.entityCollisionDamage will be split in the future
            collidingEntities = level.getEntities(
                    entity,
                    expandedBox,
                    e -> (e instanceof LivingEntity || e instanceof Boat || e instanceof AbstractMinecart) &&
                            e != entity &&
                            !(entity instanceof ItemEntity && ((ItemEntity) entity).getOwner() == e)
            );

            if (!collidingEntities.isEmpty()) {
                collisionTypeOrd[0] = Math.max(collisionTypeOrd[0], Collision.CollisionType.ENTITY.ordinal());
            }
        }

        Collision.CollisionType impactType = Collision.CollisionType.values()[collisionTypeOrd[0]];

        // Return collision with block types or entities
        return new Collision(impactType, highestY[0], lowestY[0], collidedBlockStates, collidingEntities);
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

// NEW AABB EXPANSION THAT IS SUPPOSED TO EXPAND UPWARDS AND DOWNWARDS (CHATGPT USED)
//    @NotNull
//    private static AABB expandAABB(Vec3 direction, AABB b) {
//        double dirX = Math.signum(direction.x);
//        double dirY = Math.signum(direction.y);
//        double dirZ = Math.signum(direction.z);
//
//        return new AABB(
//                // X min
//                dirX < 0 ? Math.nextDown(b.minX) : b.minX,
//                // Y min
//                dirY < 0 ? Math.nextDown(b.minY) : b.minY,
//                // Z min
//                dirZ < 0 ? Math.nextDown(b.minZ) : b.minZ,
//
//                // X max
//                dirX > 0 ? Math.nextUp(b.maxX) : b.maxX,
//                // Y max
//                dirY > 0 ? Math.nextUp(b.maxY) : b.maxY,
//                // Z max
//                dirZ > 0 ? Math.nextUp(b.maxZ) : b.maxZ
//        );
//    }

    private void handleEntityCollision() {
        if (!SERVER.entityCollisionDamage.get()) return; //TODO split entity velocity transfer into it's own config option
        if (collision.collisionType != Collision.CollisionType.ENTITY) return;
        if (fullstop.getCurrentVelocity().length() < 5.0) return;

//        if (entity instanceof Player) {
//            LogToChat.logToChat(collision.collidingEntities);
//        }

        if (entity instanceof LivingEntity) {
            for (Entity collidedEntity : collision.collidingEntities) {
                if (collidedEntity instanceof LivingEntity) {

//                    if (entity instanceof Player) { // DEBUG CODE
//                        ((LivingEntity) collidedEntity).addEffect(new MobEffectInstance(
//                                MobEffects.GLOWING,
//                                10,
//                                1,
//                                false,
//                                false,
//                                false
//                        ));
//                    }

                    if (collidedEntity instanceof Player) {
                        if (!entity.level().isClientSide()) {
                            entity.setDeltaMovement(Vec3.ZERO);
                            entity.hasImpulse = true;
                        }
                    } else {
                        if (!entity.level().isClientSide() && fullstop.isMostlyDownward() && !fullstop.isMostlyUpward() && collidedEntity.getPassengers().isEmpty()) {
                            if (!entity.isCrouching() && !(collidedEntity instanceof AgeableMob ageable && ageable.isBaby())) {
                                entity.startRiding(collidedEntity, true);
                            }
                        }
                        if (!entity.isPassengerOfSameVehicle(collidedEntity)) {
                            entity.setDeltaMovement(Vec3.ZERO);
                            entity.hasImpulse = true;
                            if (!(collidedEntity instanceof IronGolem)) {
                                collidedEntity.setDeltaMovement(fullstop.getPreviousVelocity().scale(0.05));
                            }
                        }
                    }
                } else if (collidedEntity instanceof Minecart minecart) {
                    if (!entity.isCrouching() && !entity.level().isClientSide() && fullstop.isMostlyDownward() && !fullstop.isMostlyUpward() && collidedEntity.getPassengers().isEmpty()) {
                        entity.startRiding(minecart, true);
                        minecart.setDeltaMovement(fullstop.getPreviousVelocity().scale(0.05));
                    }

                } else if (collidedEntity instanceof Boat boat) {
                    if (!entity.isCrouching() && !entity.level().isClientSide() && fullstop.isMostlyDownward() && !fullstop.isMostlyUpward() && collidedEntity.getPassengers().isEmpty()) {
                        entity.startRiding(boat, true);
                        boat.setDeltaMovement(fullstop.getPreviousVelocity().scale(0.15)); // TODO COLLISIONS ARE NOT PROPERLY DETECTED FOR BOATS, THEREFORE, THIS DOESN'T WORK.
                    }
                }
            }
        }

//        if (entity instanceof Minecart) {
//
//        }
    }

    public void bounceEntity() {
        handleEntityCollision();
        if (entity.isCrouching() || collision.fake() || (!collision.sticky() && fullstop.getStoppingForce() < 9)) return; //added entity.IsCrouching()
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

        if (fullstop.isMostlyDownward()) {
            damage -= jumpBoostLevel;
        }

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
            DamageSource baseSource = fullstop.isMostlyDownward()
                    ? sources.fall()
                    : sources.flyIntoWall();

            DamageSource customSource = makeSelfSource(baseSource, velocityToDisplay, color, fullstop.isMostlyDownward());
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

            // Pick ANY collided entity (even Iron Golem) just for death message
            LivingEntity collidedExample = collision.collidingEntities.stream()
                    .filter(e -> e instanceof LivingEntity)
                    .map(e -> (LivingEntity) e)
                    .findFirst()
                    .orElse(null);

            int colliders = validTargets.size();
            float entityDamage = (colliders == 0) ? (float) damage : (float) damage / (colliders + 1);

            // --- Always apply damage to self ---
            DamageSource selfSource;
            if (collidedExample != null) {
                selfSource = makeEntityCollisionSelfSource(
                        sources.flyIntoWall(),
                        (LivingEntity) entity,
                        collidedExample,
                        velocityToDisplay,
                        color,
                        fullstop.isMostlyDownward()
                );
            } else {
                selfSource = makeSelfSource(
                        sources.flyIntoWall(),
                        velocityToDisplay,
                        color,
                        fullstop.isMostlyDownward()
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


    @NotNull
    private static DamageSource makeSelfSource(DamageSource baseSource,
                                               String velocityToDisplay,
                                               TextColor color,
                                               boolean isMostlyDownward) {
        return new DamageSource(baseSource.typeHolder()) {
            @Override
            public Component getLocalizedDeathMessage(LivingEntity victim) {
                if (isMostlyDownward) {
                    // Vanilla fall message + velocity
                    Component base = super.getLocalizedDeathMessage(victim);

                    boolean hasElytra = FullStopCapability.hasElytraEquipped(victim);
                    Component flyingComponent = hasElytra
                            ? Component.literal(" with Elytra")
                            : Component.empty();

                    Component velocityComponent = Component.literal(" " + velocityToDisplay)
                            .withStyle(Style.EMPTY.withColor(color));

                    return base.copy().append(flyingComponent).append(velocityComponent);
                } else {
                    // Horizontal kinetic phrasing
                    Component velocityComponent = Component.literal(" " + velocityToDisplay)
                            .withStyle(Style.EMPTY.withColor(color));

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

            // ✅ Keep attacker reference for aggro
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
                                                              boolean isMostlyDownward) {
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
                            .append(" was crushed by ")
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
//            LogToChat.logToChat(fullstop.getCurrentVelocity());
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