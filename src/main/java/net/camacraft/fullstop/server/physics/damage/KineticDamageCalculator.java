package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

public class KineticDamageCalculator {

    public static double calculateDamage(Entity entity, FullStopCapability fullstop, Collision collision) {
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
                    .mapToDouble(other -> VelocityMath.entityVelocity(entity).subtract(VelocityMath.entityVelocity(other)).length())
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
}
