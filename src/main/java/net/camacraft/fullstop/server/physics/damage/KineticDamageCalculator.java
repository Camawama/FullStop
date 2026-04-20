package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

public class KineticDamageCalculator {

    public static double calculateDamage(Entity entity, FullStopCapability fullstop, Collision collision) {
        if (entity instanceof LivingEntity living) {
            if (DamageImmunityRules.isDamageImmune(living)) return 0;
        }

        if (entity instanceof Mob mob) {
            if (mob.isLeashed() && fullstop.isMostlyDownward() && collision.fake()) return 0;
        }

        if (!entity.isCrouching() && (collision.collisionType == Collision.CollisionType.SLIME ||
                collision.collisionType == Collision.CollisionType.BED ||
                collision.collisionType == Collision.CollisionType.HONEY)) {
            return 0;
        }

        if (collision.fake()) return 0;

        double stoppingForce = fullstop.getStoppingForce();
        boolean isDownwardImpact = fullstop.getPreviousScaledVelocity().y < -0.1 || (collision.collisionType == Collision.CollisionType.SOLID && fullstop.isMostlyDownward());

        if (isDownwardImpact) {
            stoppingForce = Math.abs(fullstop.getPreviousScaledVelocity().y);
        }

        // --- Dripstone Check (High Priority) ---
        if (isDownwardImpact && collision.blockStates != null) {
            for (BlockState state : collision.blockStates) {
                if (state.is(Blocks.POINTED_DRIPSTONE) && state.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.UP) {
                    // This is a special case that bypasses normal thresholds and modifiers.
                    double kineticDamage = Math.max(stoppingForce - SERVER.velocityDamageThresholdVertical.get(), 0);
                    double dripstoneDamage = 1.0 + (kineticDamage * SERVER.dripstoneDamageMultiplier.get());
                    return dripstoneDamage;
                }
            }
        }

        if (collision.collisionType == Collision.CollisionType.ENTITY) {
            if (!SERVER.entityCollisionDamage.get()) {
                return 0;
            }
            stoppingForce = collision.collidingEntities.stream()
                    .mapToDouble(other -> VelocityMath.entityVelocity(entity).subtract(VelocityMath.entityVelocity(other)).length())
                    .average()
                    .orElse(stoppingForce);
        }

        double damage;
        if (!isDownwardImpact) {
            damage = Math.max(stoppingForce - SERVER.velocityDamageThresholdHorizontal.get(), 0);
        } else {
            damage = Math.max(stoppingForce - SERVER.velocityDamageThresholdVertical.get(), 0);
        }

        if (damage <= 0) return 0;

        // --- Damage Modifiers for other surfaces ---
        boolean hitWater = false;
        if (collision.blockStates != null) {
            for (BlockState state : collision.blockStates) {
                if (state.is(Blocks.WATER)) {
                    hitWater = true;
                    break;
                }
            }
        }

        if (hitWater && isDownwardImpact) {
            damage *= entity.isSwimming() ? 1.2 : 0.05;
        }

        if (collision.blockStates != null) {
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
        if (collision.blockStates != null && !collision.blockStates.isEmpty()) {
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

        return damage * 1.07;
    }
}
