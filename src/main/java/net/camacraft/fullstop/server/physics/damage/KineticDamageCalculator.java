package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Computes kinetic impact damage.
 *
 * The trigger is the measured stopping force (speed actually lost this tick),
 * gated by real collision evidence (the entity's own collision flags). The
 * raycast Collision only classifies what was hit. Because the trigger is a
 * per-tick delta, leaning against a wall while holding W is inherently
 * damage-free: after the first contact tick there is no further deceleration.
 */
public class KineticDamageCalculator {

    public static double calculateDamage(Entity entity, FullStopCapability fullstop, Collision collision) {
        if (collision.fake()) return 0;

        if (entity instanceof LivingEntity living) {
            if (DamageImmunityRules.isDamageImmune(living)) return 0;
        }

        if (entity instanceof Mob mob) {
            if (mob.isLeashed() && fullstop.isMostlyDownward()) return 0;
        }

        boolean bouncySurface = collision.collisionType == Collision.CollisionType.SLIME ||
                collision.collisionType == Collision.CollisionType.BED ||
                collision.collisionType == Collision.CollisionType.HONEY;
        if (bouncySurface && !entity.isCrouching()) {
            return 0;
        }

        boolean isDownwardImpact = fullstop.isMostlyDownward();

        // Block impacts must be corroborated by the entity actually having collided;
        // the raycast alone (e.g. a near-miss past a corner) is not enough.
        boolean blockImpact = collision.collisionType != Collision.CollisionType.ENTITY
                && collision.collisionType != Collision.CollisionType.WATER;
        if (blockImpact && !(entity.horizontalCollision || entity.verticalCollision || entity.onGround())) {
            return 0;
        }

        double horizontalExceedance = Math.max(fullstop.getHorizontalStoppingForce() - SERVER.velocityDamageThresholdHorizontal.get(), 0);
        double verticalExceedance = Math.max(fullstop.getVerticalStoppingForce() - SERVER.velocityDamageThresholdVertical.get(), 0);

        // Dripstone is special: landing on an upward spike hurts from the first block of exceedance.
        for (BlockState state : collision.blockStates) {
            if (state.is(Blocks.POINTED_DRIPSTONE)) {
                Direction tipDirection = state.getValue(PointedDripstoneBlock.TIP_DIRECTION);
                if (isDownwardImpact && tipDirection == Direction.UP) {
                    return 1.0 + (verticalExceedance * SERVER.dripstoneDamageMultiplier.get());
                } else if (!isDownwardImpact) {
                    return 2.0;
                }
            }
        }

        double damage;
        if (collision.collisionType == Collision.CollisionType.ENTITY) {
            if (!SERVER.entityCollisionDamage.get()) {
                return 0;
            }
            double averageRelativeSpeed = collision.collidingEntities.stream()
                    .mapToDouble(other -> VelocityMath.entityVelocity(entity).subtract(VelocityMath.entityVelocity(other)).length())
                    .average()
                    .orElse(0.0);
            double threshold = isDownwardImpact
                    ? SERVER.velocityDamageThresholdVertical.get()
                    : SERVER.velocityDamageThresholdHorizontal.get();
            damage = Math.max(averageRelativeSpeed - threshold, 0);
        } else {
            damage = Math.max(horizontalExceedance, verticalExceedance);
        }

        if (damage <= 0) return 0;

        boolean hitWater = false;
        for (BlockState state : collision.blockStates) {
            if (state.is(Blocks.WATER)) {
                hitWater = true;
                break;
            }
        }

        if (hitWater && isDownwardImpact) {
            damage *= entity.isSwimming() ? 1.2 : 0.05;
        }

        for (BlockState state : collision.blockStates) {
            if (state.is(FullStopTags.SOFT_LANDING)) {
                damage *= 0.3;
                break;
            }
            if (state.is(FullStopTags.CUSHIONING)) {
                damage *= 0.7;
                break;
            }
        }

        double averageHardness = 1.0;
        if (!collision.blockStates.isEmpty()) {
            double totalHardness = 0;
            int count = 0;
            for (BlockState state : collision.blockStates) {
                float hardness = state.getDestroySpeed(entity.level(), entity.blockPosition());
                if (state.is(FullStopTags.FRAGILE)) {
                    hardness = 0.05f;
                }
                if (hardness >= 0) {
                    totalHardness += hardness;
                } else {
                    totalHardness += 100.0; // unbreakable blocks hit like bedrock
                }
                count++;
            }
            if (count > 0) {
                averageHardness = totalHardness / count;
            }
        }

        double hardnessMultiplier = 0.5 + (averageHardness / 4.0);
        hardnessMultiplier = Mth.clamp(hardnessMultiplier, 0.2, 2.0);
        damage *= hardnessMultiplier;

        return damage * 1.07;
    }
}
