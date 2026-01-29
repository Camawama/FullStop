package net.camacraft.fullstop.common.physics;

import net.camacraft.fullstop.client.render.CollisionParticleRenderer;
import net.camacraft.fullstop.client.sound.SoundPlayer;
import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.effects.ModEffects;
import net.camacraft.fullstop.common.physics.damage.KineticDamageSources;
import net.camacraft.fullstop.common.physics.interaction.BounceHandler;
import net.camacraft.fullstop.common.physics.interaction.EntityCollisionHandler;
import net.camacraft.fullstop.common.physics.interaction.KineticInteractions;
import net.camacraft.fullstop.common.util.MathUtils;
import net.camacraft.fullstop.common.physics.util.EntityStackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.capabilities.FullStopCapability.Provider.DELTAV_CAP;
import static net.camacraft.fullstop.common.capabilities.FullStopCapability.grabCapability;
import static net.camacraft.fullstop.common.physics.util.VelocityUtils.velocitiesAreSimilar;

public class Physics {
    public static final double RESTING_Y_DELTA = 0.0784000015258789;
    private final Collision collision;
    private final Entity entity;
    private final FullStopCapability fullstop;
    private final double damage;
    private boolean brokeBlock = false;

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
            return entity.getCapability(DELTAV_CAP).orElseThrow(IllegalStateException::new).getCurrentScaledVelocity();
        }

        return entity.getDeltaMovement().add(0, RESTING_Y_DELTA, 0).scale(20);
    }

    public void impactAesthetic() {
        if (collision.fake()) return;
        double highestY = collision.highestYLevel;
        double lowestY = collision.lowestYLevel;
        if (fullstop.getStoppingForce() < 4.0) return;

        Vec3 pos = entity.position();

        for (BlockState blockState : collision.blockStates) {
            CollisionParticleRenderer.spawnParticle(pos, collision, blockState);
        }
    }

    public void impactSound() {
        // 1. RUN ON SERVER ONLY (Fixes the "Echo" / Double Audio)
        if (entity.level().isClientSide) return;
        if (!fullstop.canPlaySound()) return;

        // 2. CHECK FORCE THRESHOLD
        if (fullstop.getStoppingForce() <= 6.0) return;
        if (collision.fake()) return;

        // Calculate Volume/Pitch
        float volume = ((float) (fullstop.getStoppingForce() * 0.05f));

        // Scale volume down slightly if hitting many things so it doesn't blow out speakers
        int hitCount = (collision.blockStates != null ? collision.blockStates.size() : 0)
                + (collision.collidingEntities != null ? collision.collidingEntities.size() : 0);

        if (hitCount > 1) {
            volume /= (float) (hitCount * 0.6); // Gentle reduction to prevent ear-blasting
        }

        volume = Mth.clamp(volume, 0.0f, 1.0f);

        float minPitch = 0.9f;
        float maxPitch = 1.7f;
        float pitch = (float) Mth.clamp(minPitch + (fullstop.getStoppingForce() / 100f) * (maxPitch - minPitch), minPitch, maxPitch);

        // Track played sounds to avoid duplicates (Fixes "Multiple Ground Hit Sounds")
        List<SoundEvent> playedSounds = new ArrayList<>();

        // 3. PLAY ENTITY SOUNDS
        if (collision.collidingEntities != null) {
            for (Entity collidedEntity : collision.collidingEntities) {
                // Use vanilla SoundEvents directly
                SoundEvent hitSound = SoundEvents.BOOK_PUT;

                if (!playedSounds.contains(hitSound)) {
                    entity.level().playSound(null, collidedEntity.blockPosition(), hitSound, SoundSource.PLAYERS, volume, pitch);
                    playedSounds.add(hitSound);
                }
            }
        }

        // 4. PLAY BLOCK SOUNDS
        if (collision.blockStates != null) {
            for (BlockState blockState : collision.blockStates) {
                SoundType soundType = blockState.getSoundType();
                SoundEvent sound = soundType.getFallSound();

                // Only play if we haven't heard this specific sound yet this tick
                if (!playedSounds.contains(sound)) {
                    entity.level().playSound(null, entity.blockPosition(), sound, SoundSource.PLAYERS, volume, pitch);
                    playedSounds.add(sound);
                }

                if (entity instanceof Minecart) {
                    entity.level().playSound(null, entity.blockPosition(), SoundEvents.IRON_GOLEM_HURT, SoundSource.PLAYERS, volume / 2, pitch * (float) Math.random());
                    entity.level().playSound(null, entity.blockPosition(), SoundEvents.ANVIL_FALL, SoundSource.PLAYERS, volume / 2, pitch * (float) Math.random());
                }
            }
        }

        fullstop.setSoundCooldown(4);
    }

    public void impactDamageSound() {
        if (entity instanceof Player player) {
            if (player.isCreative() || player.isSpectator()) return;
        }
        if (!(entity instanceof LivingEntity)) return;
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

        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return;
        }

        if (fullstop.getIsDamageImmune()) return;
        if (entity.isInvulnerable()) return;

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

    public static Vec3 getRayDirection(FullStopCapability fullstop) {
        Vec3 velocity = fullstop.getPreviousScaledVelocity();
        if (velocity != null && velocity.lengthSqr() > 0.0001) {
            return velocity.normalize();
        }

        Vec3 acc = fullstop.getAcceleration();
        if (acc == null) return Vec3.ZERO;
        return acc.normalize();
    }

    public static double getRayLength(Entity entity, FullStopCapability fullstop) {
        Vec3 previousVelocity = fullstop.getPreviousScaledVelocity().scale(0.05);
        if (entity instanceof Arrow) {
            return Math.min(previousVelocity.length(), 0.00);
        } else {
            return previousVelocity.length() + 0.01; // ADJUST THIS VALUE TO FIX COLLIDING THROUGH BLOCKS
        }
    }

    public static List<Vec3> getRayStarts(Entity entity) {
        AABB box = entity.getBoundingBox();
        double minX = box.minX;
        double minY = box.minY + 0.1;
        double minZ = box.minZ;
        double maxX = box.maxX;
        double maxY = box.maxY;
        double maxZ = box.maxZ;
        double cenX = box.getCenter().x;
        double cenY = box.getCenter().y;
        double cenZ = box.getCenter().z;

        return List.of(
                // 8 Corners
                new Vec3(minX, minY, minZ),
                new Vec3(minX, minY, maxZ),
                new Vec3(minX, maxY, minZ),
                new Vec3(minX, maxY, maxZ),
                new Vec3(maxX, minY, minZ),
                new Vec3(maxX, minY, maxZ),
                new Vec3(maxX, maxY, minZ),
                new Vec3(maxX, maxY, maxZ),

                // 6 Face Centers
                new Vec3(minX, cenY, cenZ), // West
                new Vec3(maxX, cenY, cenZ), // East
                new Vec3(cenX, minY, cenZ), // Bottom
                new Vec3(cenX, maxY, cenZ), // Top
                new Vec3(cenX, cenY, minZ), // North
                new Vec3(cenX, cenY, maxZ), // South

                // 1 Center
                box.getCenter()
        );
    }

    public Collision collidingKinetically() {
        Vec3 previousVelocity = fullstop.getPreviousScaledVelocity().scale(0.05);
        if (previousVelocity.lengthSqr() == 0) {
            return Collision.NONE;
        }

        Vec3 direction = getRayDirection(fullstop);
        if (direction.lengthSqr() == 0) {
            return Collision.NONE;
        }

        Level level = entity.level();
        double rayLength = getRayLength(entity, fullstop);
        List<Vec3> rayStarts = getRayStarts(entity);

        ArrayList<BlockState> collidedBlockStates = new ArrayList<>();
        ArrayList<BlockPos> collidedBlockPositions = new ArrayList<>();
        ArrayList<BlockHitResult> collidedBlockHits = new ArrayList<>();
        double highestY = -64;
        double lowestY = 320;
        Collision.CollisionType impactType = Collision.CollisionType.NONE;

        ClipContext.Fluid fluidContext = entity.isInWater() ? ClipContext.Fluid.NONE : ClipContext.Fluid.SOURCE_ONLY;

        for (Vec3 start : rayStarts) {
            Vec3 end = start.add(direction.scale(rayLength));
            
            ClipContext ctx = new ClipContext(start, end, ClipContext.Block.COLLIDER, fluidContext, entity);
            BlockHitResult blockHit = level.clip(ctx);

            if (blockHit.getType() == HitResult.Type.BLOCK) {
                BlockPos hitPos = blockHit.getBlockPos();
                BlockState hitState = level.getBlockState(hitPos);
                Direction hitFace = blockHit.getDirection();
                Vec3 hitNormal = Vec3.atLowerCornerOf(hitFace.getNormal());
                
                boolean isOpposing = direction.dot(hitNormal) < -0.1;

                // JUMPING FIX: If moving mostly upwards, ignore collisions with vertical faces.
                // This prevents "scraping" the side of a block while jumping from causing a collision.
                if (fullstop.isMostlyUpward() && hitFace.getAxis().isHorizontal()) {
                    isOpposing = false;
                }

                // Fix for running on flat ground: Ignore floor collisions if not falling significantly
                if (hitFace == Direction.UP && previousVelocity.y > -0.5) {
                    isOpposing = false;
                }

                if (isOpposing && !collidedBlockPositions.contains(hitPos)) {
                    collidedBlockStates.add(hitState);
                    collidedBlockPositions.add(hitPos);
                    collidedBlockHits.add(blockHit);
                    
                    highestY = Math.max(highestY, hitPos.getY() + 1);
                    lowestY = Math.min(lowestY, hitPos.getY());

                    Collision.CollisionType typeHere;
                    if (hitState.isStickyBlock()) {
                        if (hitState.is(Blocks.SLIME_BLOCK)) {
                            typeHere = Collision.CollisionType.SLIME;
                        } else {
                            typeHere = Collision.CollisionType.HONEY;
                        }
                    } else if (hitState.getBlock() instanceof BedBlock) {
                        typeHere = Collision.CollisionType.BED;
                    } else {
                        typeHere = Collision.CollisionType.SOLID;
                    }

                    if (impactType.ordinal() < typeHere.ordinal()) {
                        impactType = typeHere;
                    }
                }
            }
        }

        List<Entity> collidingEntities = Collections.emptyList();
        if (SERVER.entityCollisionDamage.get()) {
            AABB box = entity.getBoundingBox();
            AABB entityCheckBox = box.inflate(0.1);
            collidingEntities = level.getEntities(
                    entity,
                    entityCheckBox,
                    e -> (e instanceof LivingEntity || e instanceof Boat || e instanceof AbstractMinecart)
                            && e != entity
                            && !(entity instanceof ItemEntity && ((ItemEntity) entity).getOwner() == e)
                            && !(e.isPassengerOfSameVehicle(entity))
            );

            if (collidingEntities.size() > 1) {
                boolean overlapping = collidingEntities.stream()
                        .allMatch(e -> e.getBoundingBox().intersects(entity.getBoundingBox()));
                if (overlapping) {
                    collidingEntities = Collections.emptyList();
                }
            }

            if (!collidingEntities.isEmpty()) {
                impactType = Collision.CollisionType.ENTITY;
            }
        }


        return new Collision(impactType, highestY, lowestY, collidedBlockStates, collidingEntities, collidedBlockPositions, collidedBlockHits);
    }

    public void handleEntityCollision() {
        EntityCollisionHandler.handle(this);
    }


    public void bounceEntity() {
        BounceHandler.apply(this);
    }

    private double calcKineticDamageTotal() {
        if (entity instanceof Mob mob) {
            if (mob.isLeashed() && fullstop.isMostlyDownward() && collision.fake()) return 0;
        }

        if (!entity.isCrouching() && collision.bouncy()) {
            return 0;
        }

        if (!fullstop.isMostlyDownward() &&
                collision.collisionType != Collision.CollisionType.SOLID &&
                collision.collisionType != Collision.CollisionType.ENTITY) {
            return 0;
        }

        if (collision.collisionType == Collision.CollisionType.ENTITY) {
            if (!SERVER.entityCollisionDamage.get()) {
                return 0;
            }
        }

        double stoppingForce = fullstop.getStoppingForce();
        if (collision.collisionType == Collision.CollisionType.ENTITY
                && collision.collidingEntities != null
                && !collision.collidingEntities.isEmpty()) {
            stoppingForce = collision.collidingEntities.stream()
                    .mapToDouble(other -> entityVelocity(entity).subtract(entityVelocity(other)).length())
                    .average()
                    .orElse(stoppingForce);
        }
        double damage;

        if (!fullstop.isMostlyDownward()) {
            damage = Math.max(stoppingForce - SERVER.velocityDamageThresholdHorizontal.get(), 0);
        } else {
            damage = Math.max(stoppingForce - SERVER.velocityDamageThresholdVertical.get(), 0);
        }

        if (damage <= 0) return 0;
        
        boolean hitWater = false;
        if (collision.blockStates != null && !collision.blockStates.isEmpty()) {
            for (BlockState state : collision.blockStates) {
                if (state.is(Blocks.WATER)) {
                    hitWater = true;
                    break;
                }
            }
        }

        if (hitWater && fullstop.isMostlyDownward()) {
            if (entity.isSwimming()) {
                damage *= 1.2;
            } else {
                damage *= 0.05;
            }
        }

        if (collision.blockStates != null && !collision.blockStates.isEmpty()) {
            for (BlockState state : collision.blockStates) {
                if (state.is(Blocks.HAY_BLOCK)) {
                    damage *= 0.3;
                    break;
                }
                if (state.is(BlockTags.WOOL) || state.is(BlockTags.LEAVES) ||
                        state.is(Blocks.BIG_DRIPLEAF) || state.is(Blocks.SMALL_DRIPLEAF) ||
                        state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.SPONGE) || state.is(Blocks.WET_SPONGE)) {
                    damage *= 0.7;
                    break;
                }
            }
        }

        double averageHardness = 1.0;
        if (!collision.blockStates.isEmpty()) {
            double totalHardness = 0;
            int count = 0;
            for (BlockState state : collision.blockStates) {
                float hardness = state.getDestroySpeed(entity.level(), entity.blockPosition());
                
                if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE) || 
                    state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE) ||
                    state.getBlock() instanceof StainedGlassBlock || state.getBlock() instanceof StainedGlassPaneBlock) {
                    hardness = 0.05f; 
                }

                if (hardness >= 0) {
                    totalHardness += hardness;
                    count++;
                } else {
                    totalHardness += 100.0;
                    count++;
                }
            }
            if (count > 0) {
                averageHardness = totalHardness / count;
            }
        }

        double hardnessMultiplier = 0.5 + (averageHardness / 4.0);
        hardnessMultiplier = Mth.clamp(hardnessMultiplier, 0.2, 2.0);

        damage *= hardnessMultiplier;

        return (float) (damage * 1.07);
    }

    private float applyArmorReduction(LivingEntity entity, float rawDamage, boolean mostlyDownward) {
        double armor = entity.getAttributeValue(Attributes.ARMOR);
        double toughness = entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS);

        double toughnessReduction = Math.max(0.5, 1.0 - (toughness / 40.0));

        rawDamage *= (float) toughnessReduction;

        if (mostlyDownward) {
            int featherFallingLevel = entity.getItemBySlot(EquipmentSlot.FEET).getEnchantmentLevel(Enchantments.FALL_PROTECTION);
            if (featherFallingLevel > 0) {
                float reduction = 0.2f * featherFallingLevel;
                rawDamage *= Math.max(0.0f, 1.0f - reduction);

                if (featherFallingLevel >= 4) {
                    float maxSurvivableDamage = entity.getMaxHealth() - 1.0f;
                    if (maxSurvivableDamage < 1.0f) maxSurvivableDamage = 1.0f;

                    rawDamage = Math.min(rawDamage, maxSurvivableDamage);
                }
            }
            return rawDamage;
        }

        return CombatRules.getDamageAfterAbsorb(rawDamage, (float) armor, (float) toughness);
    }

    public void applyKineticDamage() {
        double previousVelocity = fullstop.getPreviousScaledVelocity().length();
        if (damage < 1) return;
        if (entity instanceof ItemEntity) return;

        BlockPos collisionPos = collision.impactedPositions.isEmpty() ? null : collision.impactedPositions.get(0);
        int collisionEntityId = (collision.collidingEntities != null && !collision.collidingEntities.isEmpty())
                ? collision.collidingEntities.get(0).getId()
                : -1;
        if (fullstop.isCollisionOnCooldown(entity.tickCount, collisionPos, collisionEntityId, 5)) {
            return;
        }

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
            rgb = MathUtils.lerpColor(green, yellow, nt);
        } else if (t < 0.66) {
            double nt = (t - 0.33) / 0.33;
            rgb = MathUtils.lerpColor(yellow, red, nt);
        } else {
            double nt = (t - 0.66) / 0.34;
            rgb = MathUtils.lerpColor(red, darkRed, nt);
        }
        color = TextColor.fromRgb(rgb);

        String velocityToDisplay = String.format("(going %.2f m/s)", previousVelocity);
        DamageSources sources = entity.damageSources();

        if (collision.collisionType != Collision.CollisionType.ENTITY) {
            applyBlockCollisionDamage(velocityToDisplay, color, sources);
        }

        if (collision.collisionType == Collision.CollisionType.ENTITY && collision.collidingEntities != null && !collision.collidingEntities.isEmpty()) {
            applyEntityCollisionDamage(velocityToDisplay, color, sources);
        }

        fullstop.recordCollision(entity.tickCount, collisionPos, collisionEntityId);
    }

    private void applyBlockCollisionDamage(String velocityToDisplay, TextColor color, DamageSources sources) {
        DamageSource baseSource;
        if (fullstop.isMostlyDownward()) {
            baseSource = sources.fall();
        } else if (fullstop.isMostlyUpward()) {
            baseSource = sources.flyIntoWall();
        } else {
            baseSource = sources.flyIntoWall();
        }

        DamageSource customSource = KineticDamageSources.makeSelfSource(baseSource, velocityToDisplay, color, fullstop.isMostlyDownward(), fullstop.isMostlyUpward());

        if (fullstop.isMostlyDownward()) {
            double totalMass = EntityStackUtils.getAllEntitiesInStack(entity).stream().mapToDouble(EntityStackUtils::getEntityMass).sum();
            double selfMass = EntityStackUtils.getEntityMass(entity);
            if (selfMass < 0.001) selfMass = 0.001;

            float crushFactor = (float) (totalMass / selfMass);
            float selfDamage = (float) damage * crushFactor;

            if (entity instanceof LivingEntity living) {
                selfDamage = applyArmorReduction(living, selfDamage, fullstop.isMostlyDownward());
                entity.hurt(customSource, selfDamage);
            } else {
                entity.hurt(customSource, selfDamage);
            }

            float passengerDamage = (float) damage * 0.5f;
            for (Entity passenger : EntityStackUtils.getAllEntitiesInStack(entity)) {
                if (passenger == entity) continue;
                passenger.hurt(customSource, passengerDamage);
            }

        } else {
            if (entity instanceof LivingEntity living) {
                float finalSelfDamage = applyArmorReduction(living, (float) damage, fullstop.isMostlyDownward());
                entity.hurt(customSource, finalSelfDamage);
            } else {
                entity.hurt(customSource, (float) damage);
            }

            if (entity.isPassenger() && damage > 5) {
                entity.stopRiding();
            }
        }
    }

    private void applyEntityCollisionDamage(String velocityToDisplay, TextColor color, DamageSources sources) {
        List<LivingEntity> validTargets = collision.collidingEntities.stream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .filter(living -> !(living instanceof IronGolem))
                .toList();

        List<IronGolem> collidedGolems = collision.collidingEntities.stream()
                .filter(e -> e instanceof IronGolem)
                .map(e -> (IronGolem) e)
                .toList();

        for (IronGolem golem : collidedGolems) {
            float baseVolume = 0.6f;
            float basePitch = 1.0f;

            float volume = baseVolume + (float)damage * 0.05f;
            volume = MathUtils.clamp(volume, 0.6f, 1.8f);

            float pitch = basePitch - (float)damage * 0.02f;
            pitch = MathUtils.clamp(pitch, 0.7f, 1.2f);

            SoundPlayer.playSound(golem, SoundEvents.IRON_GOLEM_HURT, volume, pitch);

            if (entity instanceof LivingEntity livingTarget) {
                golem.setTarget(livingTarget);
            }
        }

        LivingEntity collidedExample = collision.collidingEntities.stream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .findFirst()
                .orElse(null);

        int colliders = validTargets.size();
        float splitEntityDamage = (float) damage / (colliders + 1);
        splitEntityDamage = scaleDamageByRelativeVelocity(splitEntityDamage);

        Entity firstCollider = collision.collidingEntities.get(0);
        if (!entity.onGround() && !firstCollider.onGround()) {
            if (velocitiesAreSimilar(entity.getDeltaMovement(), firstCollider.getDeltaMovement(), 0.1)) {
                splitEntityDamage = 0;
            }
        }

        DamageSource selfSource;
        if (collidedExample != null && entity instanceof LivingEntity living) {
            selfSource = KineticDamageSources.makeEntityCollisionSelfSource(
                    sources.flyIntoWall(),
                    living,
                    collidedExample,
                    velocityToDisplay,
                    color,
                    fullstop.isMostlyDownward(),
                    fullstop.isMostlyUpward()
            );
        } else {
            selfSource = KineticDamageSources.makeSelfSource(
                    sources.flyIntoWall(),
                    velocityToDisplay,
                    color,
                    fullstop.isMostlyDownward(),
                    fullstop.isMostlyUpward()
            );
        }

        if (!(entity instanceof LivingEntity living) || isDamageImmune(living) || fullstop.getIsDamageImmune()) {
            splitEntityDamage = 0;
        }

        double horseFallDampening = 1.0;
        if (entity instanceof Horse && splitEntityDamage < horseFallDampening && fullstop.isMostlyDownward()) {
            splitEntityDamage = 0;
        }

        if (splitEntityDamage <= 0) {
            splitEntityDamage = 0;
        }

        double stackMass = EntityStackUtils.getAllEntitiesInStack(entity).stream().mapToDouble(EntityStackUtils::getEntityMass).sum();
        double selfMass = EntityStackUtils.getEntityMass(entity);
        if (selfMass < 0.001) selfMass = 0.001;

        float selfDamage = splitEntityDamage;
        if (fullstop.isMostlyDownward()) {
            selfDamage *= (float) (stackMass / selfMass);
        }

        if (!(entity instanceof AbstractMinecart) && !(entity instanceof IronGolem)) {
            entity.hurt(selfSource, selfDamage);
        }

        if (fullstop.isMostlyDownward()) {
            float passengerDamage = splitEntityDamage * 0.5f;
            for (Entity passenger : EntityStackUtils.getAllEntitiesInStack(entity)) {
                if (passenger == entity) continue;
                passenger.hurt(selfSource, passengerDamage);
            }
        }

        if (entity instanceof LivingEntity living) {
            for (LivingEntity target : validTargets) {
                DamageSource attackerSource = KineticDamageSources.makeEntityAttackerSource(sources, living, velocityToDisplay, color, fullstop.isMostlyDownward());

                double targetMass = EntityStackUtils.getEntityMass(target);
                if (targetMass < 0.001) targetMass = 0.001;

                float targetDamageScale = (float) (stackMass / targetMass);
                float targetScaledDamage = splitEntityDamage * targetDamageScale;

                if (isDamageImmune(target)) {
                    targetScaledDamage = 0;
                } else {
                    targetScaledDamage = applyArmorReduction(target, targetScaledDamage, fullstop.isMostlyDownward());
                }

                target.hurt(attackerSource, targetScaledDamage);
            }
        }
    }

    private float scaleDamageByRelativeVelocity(float baseDamage) {
        if (collision.collidingEntities == null || collision.collidingEntities.isEmpty()) {
            return baseDamage;
        }

        double attackerSpeed = entityVelocity(entity).length();
        double averageTargetSpeed = collision.collidingEntities.stream()
                .mapToDouble(other -> entityVelocity(other).length())
                .average()
                .orElse(0.0);
        double maxSpeed = Math.max(attackerSpeed, averageTargetSpeed);
        if (maxSpeed <= 0.001) {
            return baseDamage;
        }

        double averageRelativeSpeed = collision.collidingEntities.stream()
                .mapToDouble(other -> entityVelocity(entity).subtract(entityVelocity(other)).length())
                .average()
                .orElse(attackerSpeed);

        double scale = Mth.clamp(averageRelativeSpeed / maxSpeed, 0.0, 2.0);
        return (float) (baseDamage * scale);
    }

    public Physics(Entity entity) {
        fullstop = grabCapability(entity);

        if (entity.tickCount != fullstop.getLastTick()) {
            fullstop.tick(entity);
            fullstop.setLastTick(entity.tickCount);
        }

        this.entity = entity;

        collision = collidingKinetically();

        if (entity instanceof ServerPlayer) {
//            double rawWeight = EntityStackUtils.getEntityMass(entity);
//            double displayKg = rawWeight * 1350.0;
//            double displayLbs = displayKg * 2.20462;
//
//            LogToChat.logToChat(Math.round(displayKg), "Kg", Math.round(displayLbs), "Lbs");
        }

//        if (entity instanceof ServerPlayer) {
//            LogToChat.logToChat(collision.collisionType);
//        }

        damage = calcKineticDamageTotal();

        if (!collision.impactedPositions.isEmpty()) {
            brokeBlock = KineticInteractions.handleBlockImpacts(entity, fullstop.getPreviousNativeVelocity(), collision.impactedPositions, collision.impactedHits);
        }
    }

    public Entity getEntity() {
        return entity;
    }

    public FullStopCapability getFullstop() {
        return fullstop;
    }

    public Collision getCollision() {
        return collision;
    }

    public double getDamage() {
        return damage;
    }

    public boolean hasBrokenBlock() {
        return brokeBlock;
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
