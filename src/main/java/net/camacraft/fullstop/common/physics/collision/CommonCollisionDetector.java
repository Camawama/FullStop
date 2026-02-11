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
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommonCollisionDetector {

    public static Collision detectBlocks(Entity entity, FullStopCapability fullstop) {
        Vec3 velocity = fullstop.getPreviousScaledVelocity().scale(0.05);
        if (velocity.lengthSqr() == 0) {
            return Collision.NONE;
        }

        Vec3 direction = RaycastUtil.getRayDirection(fullstop);
        if (direction.lengthSqr() == 0) {
            return Collision.NONE;
        }

        Level level = entity.level();
        double rayLength = RaycastUtil.getRayLength(entity, fullstop);
        
        FullStopConfig.RaycastMode mode = FullStopConfig.SERVER.raycastMode.get();
        List<Vec3> rayStarts = RaycastUtil.getRayStarts(entity, mode);

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

                boolean isWater = hitState.getCollisionShape(level, hitPos).isEmpty() && !hitState.getFluidState().isEmpty() && hitState.getFluidState().is(FluidTags.WATER);
                boolean isOpposing;

                if (isWater) {
                    if (hitFace != Direction.UP || !fullstop.isMostlyHorizontal()) {
                        continue;
                    }
                    isOpposing = true;
                } else {
                    isOpposing = direction.dot(hitNormal) < -0.1;

                    // JUMPING FIX: If moving mostly upwards, ignore collisions with vertical faces.
                    // This prevents "scraping" the side of a block while jumping from causing a collision.
                    if (fullstop.isMostlyUpward() && hitFace.getAxis().isHorizontal()) {
                        isOpposing = false;
                    }

                    // Fix for running on flat ground: Ignore floor collisions if not falling significantly
                    if (hitFace == Direction.UP && velocity.y > -0.5) {
                        isOpposing = false;
                    }
                }

                if (isOpposing && !collidedBlockPositions.contains(hitPos)) {
                    VoxelShape shape = hitState.getCollisionShape(level, hitPos);
                    if (!shape.isEmpty() && shape.bounds().move(hitPos.getX(), hitPos.getY(), hitPos.getZ()).intersects(entity.getBoundingBox().inflate(0.01))) {
                        continue;
                    }

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

        return new Collision(impactType, highestY, lowestY, collidedBlockStates, Collections.emptyList(), collidedBlockPositions, collidedBlockHits);
    }
}
