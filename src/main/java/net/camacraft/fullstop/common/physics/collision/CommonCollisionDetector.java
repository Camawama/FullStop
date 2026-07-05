package net.camacraft.fullstop.common.physics.collision;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.RaycastUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Ray-based block collision classifier. Note: this identifies WHAT was hit; whether
 * an impact deals damage is decided by the measured stopping force plus the entity's
 * own collision flags (see KineticDamageCalculator).
 */
public class CommonCollisionDetector {

    public static Collision detectBlocks(Entity entity, FullStopCapability fullstop) {
        Vec3 velocity = fullstop.getPreviousNativeVelocity();
        if (velocity.lengthSqr() == 0) {
            return Collision.NONE;
        }

        Vec3 direction = RaycastUtil.getRayDirection(fullstop);
        if (direction.lengthSqr() == 0) {
            return Collision.NONE;
        }

        Level level = entity.level();
        double rayLength = RaycastUtil.getRayLength(entity, fullstop);
        if (rayLength <= 0) {
            return Collision.NONE;
        }

        FullStopConfig.RaycastMode mode = FullStopConfig.SERVER.raycastMode.get();
        List<Vec3> rayStarts = RaycastUtil.getRayStarts(entity, mode);

        List<BlockState> collidedBlockStates = new ArrayList<>();
        List<BlockPos> collidedBlockPositions = new ArrayList<>();
        List<BlockHitResult> collidedBlockHits = new ArrayList<>();
        Collision.CollisionType impactType = Collision.CollisionType.NONE;

        for (Vec3 start : rayStarts) {
            Vec3 end = start.add(direction.scale(rayLength));

            ClipContext ctx = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, entity);
            BlockHitResult blockHit = level.clip(ctx);

            if (blockHit.getType() != HitResult.Type.BLOCK) continue;

            BlockPos hitPos = blockHit.getBlockPos();
            BlockState hitState = level.getBlockState(hitPos);
            Direction hitFace = blockHit.getDirection();
            Vec3 hitNormal = Vec3.atLowerCornerOf(hitFace.getNormal());

            boolean isWater = !hitState.getFluidState().isEmpty() && hitState.getFluidState().is(FluidTags.WATER);
            boolean isOpposing;

            if (isWater) {
                if (hitFace != Direction.UP || !fullstop.isMostlyHorizontal()) {
                    continue;
                }
                isOpposing = true;
            } else {
                isOpposing = direction.dot(hitNormal) < -0.1;

                if (fullstop.isMostlyUpward() && hitFace.getAxis().isHorizontal()) {
                    isOpposing = false;
                }

                // Ignore floor hits unless actually falling with some speed (velocity here
                // is native blocks/tick; -0.5 ≈ falling faster than 10 m/s).
                if (hitFace == Direction.UP && velocity.y > -0.5) {
                    isOpposing = false;
                }
            }

            if (!isOpposing || collidedBlockPositions.contains(hitPos)) continue;

            collidedBlockStates.add(hitState);
            collidedBlockPositions.add(hitPos);
            collidedBlockHits.add(blockHit);

            Collision.CollisionType typeHere;
            if (isWater) {
                typeHere = Collision.CollisionType.WATER;
            } else if (hitState.isStickyBlock()) {
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

            if (impactType.priority < typeHere.priority) {
                impactType = typeHere;
            }
        }

        if (impactType == Collision.CollisionType.NONE) {
            return Collision.NONE;
        }

        return new Collision(impactType, collidedBlockStates, List.of(), collidedBlockPositions, collidedBlockHits);
    }
}
