package net.camacraft.fullstop.server.physics.collision;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.RaycastUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

public class ServerCollisionDetector {

    public static Collision detect(Entity entity, FullStopCapability fullstop) {
        Vec3 previousVelocity = fullstop.getPreviousScaledVelocity().scale(0.05);
        if (previousVelocity.lengthSqr() == 0) {
            return Collision.NONE;
        }

        Vec3 direction = RaycastUtil.getRayDirection(fullstop);
        if (direction.lengthSqr() == 0) {
            return Collision.NONE;
        }

        Level level = entity.level();
        double rayLength = RaycastUtil.getRayLength(entity, fullstop);
        List<Vec3> rayStarts = RaycastUtil.getRayStarts(entity);

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
}
