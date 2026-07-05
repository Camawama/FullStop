package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.sound.FSSoundPlayer;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources.VelocityDisplay;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class KineticDamageApplier {

    /** Ticks after a kinetic hit during which further kinetic damage is suppressed. */
    private static final int DAMAGE_COOLDOWN_TICKS = 5;

    public static void apply(Entity entity, FullStopCapability fullstop, Collision collision, double damage) {
        if (damage < 1) return;
        if (entity instanceof ItemEntity) return;

        // Time-based rather than per-block, so sliding along a wall (a new block
        // every tick) can't deal damage every tick.
        if (fullstop.isDamageOnCooldown(entity.tickCount)) {
            return;
        }

        double previousSpeed = fullstop.getPreviousScaledVelocity().length();
        VelocityDisplay velocity = FullStopDamageSources.velocityDisplay(previousSpeed);
        DamageSources sources = entity.damageSources();

        if (collision.collisionType != Collision.CollisionType.ENTITY) {
            applyBlockCollisionDamage(entity, fullstop, collision, damage, velocity);
        } else if (!collision.collidingEntities.isEmpty()) {
            applyEntityCollisionDamage(entity, fullstop, collision, damage, velocity, sources);
        }

        fullstop.markDamageApplied(entity.tickCount, DAMAGE_COOLDOWN_TICKS);
    }

    private static void applyBlockCollisionDamage(Entity entity, FullStopCapability fullstop, Collision collision,
                                                  double damage, VelocityDisplay velocity) {
        boolean hitDripstone = false;
        for (BlockState state : collision.blockStates) {
            if (state.is(Blocks.POINTED_DRIPSTONE) && state.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.UP) {
                hitDripstone = true;
                break;
            }
        }

        boolean downward = fullstop.isMostlyDownward();
        boolean upward = fullstop.isMostlyUpward();

        DamageSource customSource = FullStopDamageSources.kineticSelf(
                entity, velocity, downward, upward, hitDripstone && downward);

        if (downward) {
            // Riders press down on whatever they sit on: the bottom of the stack takes
            // its share of the whole stack's mass.
            double totalMass = EntityStackUtils.getAllEntitiesInStack(entity).stream()
                    .mapToDouble(EntityStackUtils::getEntityMass).sum();
            double selfMass = Math.max(EntityStackUtils.getEntityMass(entity), 0.001);

            float crushFactor = (float) (totalMass / selfMass);
            float selfDamage = (float) damage * crushFactor;

            if (entity instanceof LivingEntity living) {
                selfDamage = DamageMitigation.applyArmorReduction(living, selfDamage, true, false);
            }
            if (selfDamage > 0) {
                entity.hurt(customSource, selfDamage);
            }

            float passengerDamage = (float) damage * 0.5f;
            if (passengerDamage > 0) {
                for (Entity passenger : EntityStackUtils.getAllEntitiesInStack(entity)) {
                    if (passenger == entity) continue;
                    passenger.hurt(customSource, passengerDamage);
                }
            }
        } else {
            float selfDamage = (float) damage;
            if (entity instanceof LivingEntity living) {
                selfDamage = DamageMitigation.applyArmorReduction(living, selfDamage, false, upward);
            }
            if (selfDamage > 0) {
                entity.hurt(customSource, selfDamage);
            }

            if (entity.isPassenger() && damage > 5) {
                entity.stopRiding();
            }
        }
    }

    private static void applyEntityCollisionDamage(Entity entity, FullStopCapability fullstop, Collision collision,
                                                   double damage, VelocityDisplay velocity, DamageSources sources) {
        List<LivingEntity> validTargets = collision.collidingEntities.stream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .filter(living -> !(living instanceof IronGolem))
                .toList();

        reactGolems(entity, collision, damage);

        LivingEntity collidedExample = collision.collidingEntities.stream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .findFirst()
                .orElse(null);

        int colliders = validTargets.size();
        float splitEntityDamage = (float) damage / (colliders + 1);
        splitEntityDamage = scaleDamageByRelativeVelocity(entity, collision, splitEntityDamage);

        Entity firstCollider = collision.collidingEntities.get(0);
        if (!entity.onGround() && !firstCollider.onGround()) {
            if (VelocityMath.velocitiesAreSimilar(entity.getDeltaMovement(), firstCollider.getDeltaMovement(), 0.1)) {
                splitEntityDamage = 0;
            }
        }

        Vec3 myVel = VelocityMath.entityVelocity(entity);
        Vec3 otherVel = VelocityMath.entityVelocity(firstCollider);
        double myMomentum = myVel.length() * EntityStackUtils.getEntityMass(entity);
        double otherMomentum = otherVel.length() * EntityStackUtils.getEntityMass(firstCollider);
        double impactSpeed = myVel.subtract(otherVel).length();

        boolean otherHasMoreMomentum = otherMomentum > myMomentum;
        boolean bothMovingFast = myVel.length() > 2.0 && otherVel.length() > 2.0;

        VelocityDisplay displayVelocity;
        if (bothMovingFast) {
            displayVelocity = FullStopDamageSources.mutualVelocityDisplay(impactSpeed);
        } else if (otherHasMoreMomentum) {
            displayVelocity = FullStopDamageSources.velocityDisplay(otherVel.length());
        } else {
            displayVelocity = velocity;
        }

        boolean downward = fullstop.isMostlyDownward();
        boolean upward = fullstop.isMostlyUpward();

        DamageSource selfSource;
        if (collidedExample != null && entity instanceof LivingEntity living) {
            if (bothMovingFast) {
                selfSource = FullStopDamageSources.entityMutualCollision(entity, collidedExample, displayVelocity);
            } else if (otherHasMoreMomentum) {
                selfSource = FullStopDamageSources.entityAttacker(sources, collidedExample, displayVelocity, downward);
            } else {
                selfSource = FullStopDamageSources.entityCollisionSelf(entity, collidedExample, displayVelocity, downward, upward);
            }
        } else {
            selfSource = FullStopDamageSources.kineticSelf(entity, displayVelocity, downward, upward, false);
        }

        if (!(entity instanceof LivingEntity self) || DamageImmunityRules.isDamageImmune(self) || fullstop.getIsDamageImmune()) {
            splitEntityDamage = 0;
        }

        if (entity instanceof Horse && splitEntityDamage < 1.0 && downward) {
            splitEntityDamage = 0;
        }

        double stackMass = EntityStackUtils.getAllEntitiesInStack(entity).stream()
                .mapToDouble(EntityStackUtils::getEntityMass).sum();
        double selfMass = Math.max(EntityStackUtils.getEntityMass(entity), 0.001);

        float selfDamage = splitEntityDamage;
        if (downward) {
            selfDamage *= (float) (stackMass / selfMass);
        }

        if (selfDamage > 0 && !(entity instanceof AbstractMinecart) && !(entity instanceof IronGolem)) {
            entity.hurt(selfSource, selfDamage);
        }

        if (downward && splitEntityDamage > 0) {
            float passengerDamage = splitEntityDamage * 0.5f;
            for (Entity passenger : EntityStackUtils.getAllEntitiesInStack(entity)) {
                if (passenger == entity) continue;
                passenger.hurt(selfSource, passengerDamage);
            }
        }

        if (entity instanceof LivingEntity living) {
            for (LivingEntity target : validTargets) {
                DamageSource attackerSource;
                if (otherHasMoreMomentum) {
                    attackerSource = FullStopDamageSources.entityCollisionSelf(target, living, displayVelocity, downward, upward);
                } else {
                    attackerSource = FullStopDamageSources.entityAttacker(sources, living, velocity, downward);
                }

                double targetMass = Math.max(EntityStackUtils.getEntityMass(target), 0.001);
                float targetScaledDamage = splitEntityDamage * (float) (stackMass / targetMass);

                if (DamageImmunityRules.isDamageImmune(target)) {
                    continue;
                }
                targetScaledDamage = DamageMitigation.applyArmorReduction(target, targetScaledDamage, downward, upward);

                if (targetScaledDamage > 0) {
                    target.hurt(attackerSource, targetScaledDamage);
                }
            }
        }
    }

    /** Golems shrug off impacts, clang, and get angry at whoever hit them. */
    private static void reactGolems(Entity entity, Collision collision, double damage) {
        for (Entity collided : collision.collidingEntities) {
            if (!(collided instanceof IronGolem golem)) continue;

            float volume = Mth.clamp(0.6f + (float) damage * 0.05f, 0.6f, 1.8f);
            float pitch = Mth.clamp(1.0f - (float) damage * 0.02f, 0.7f, 1.2f);

            FSSoundPlayer.playSoundServer(golem, SoundEvents.IRON_GOLEM_HURT, SoundSource.NEUTRAL, volume, pitch);

            if (entity instanceof LivingEntity livingTarget) {
                golem.setTarget(livingTarget);
            }
        }
    }

    private static float scaleDamageByRelativeVelocity(Entity entity, Collision collision, float baseDamage) {
        if (collision.collidingEntities.isEmpty()) {
            return baseDamage;
        }

        double attackerSpeed = VelocityMath.entityVelocity(entity).length();
        double averageTargetSpeed = collision.collidingEntities.stream()
                .mapToDouble(other -> VelocityMath.entityVelocity(other).length())
                .average()
                .orElse(0.0);
        double maxSpeed = Math.max(attackerSpeed, averageTargetSpeed);
        if (maxSpeed <= 0.001) {
            return baseDamage;
        }

        double averageRelativeSpeed = collision.collidingEntities.stream()
                .mapToDouble(other -> VelocityMath.entityVelocity(entity).subtract(VelocityMath.entityVelocity(other)).length())
                .average()
                .orElse(attackerSpeed);

        double scale = Mth.clamp(averageRelativeSpeed / maxSpeed, 0.0, 2.0);
        return (float) (baseDamage * scale);
    }
}
