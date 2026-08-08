package net.camacraft.fullstop.common.physics.collision;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.FastRaycast;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Ray-based block collision classifier. Note: this identifies WHAT was hit; whether
 * an impact deals damage is decided by the measured stopping force plus the entity's
 * own collision flags (see KineticDamageCalculator).
 */
public class CommonCollisionDetector {

    /**
     * Minimum speed (m/s) INTO a block face for the hit to count as a collision.
     * The rays have a 0.15 minimum length, so an entity standing flush against a
     * wall "hits" it every tick with near-zero velocity — those touches spammed
     * the impact sound/particles on every small deceleration (walking back and
     * forth beside a wall, brushing along honey) and lit the debug rays red while
     * merely hugging a wall. Also shared with the debug renderer's coloring.
     */
    public static final double MIN_APPROACH_SPEED_MPS = 2.0;

    public static Collision detectBlocks(Entity entity, FullStopCapability fullstop) {
        // Pre-impact velocity (faster of the last two ticks): the damage tick of a
        // two-tick impact must still see the block, even though last tick's travel
        // was only the small post-contact remnant.
        Vec3 velocity = fullstop.getPreImpactNativeVelocity();
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

        // Every ray lies inside the ray-start envelope swept along the travel
        // direction. If that volume is provably all air — and no VS ship (whose
        // blocks live outside the world grid) is near it — no clip can hit
        // anything, so the rays are skipped wholesale. The envelope floor is
        // minY + 0.1 (where the bottom rays actually start, see RaycastUtil),
        // NOT the box floor: using the box floor would pull the block layer a
        // grounded entity stands on into the scan and defeat the skip for every
        // sprinting player and galloping horse on flat ground.
        AABB box = entity.getBoundingBox();
        AABB rayEnvelope = new AABB(box.minX, Math.min(box.minY + 0.1, box.maxY), box.minZ,
                box.maxX, box.maxY, box.maxZ);
        AABB swept = rayEnvelope.expandTowards(direction.scale(rayLength)).inflate(0.001);
        boolean worldBlocksNearby = FastRaycast.mayHitAnything(level, swept);
        boolean shipsNearby = ShipCompat.anyShipsNearby(level, swept);
        if (!worldBlocksNearby && !shipsNearby) {
            return Collision.NONE;
        }

        FullStopConfig.RaycastMode mode = FullStopConfig.SERVER.raycastMode.get();
        List<Vec3> rayStarts = RaycastUtil.getRayStarts(entity, mode);
        // One shape context for all rays; each ClipContext would otherwise build
        // its own EntityCollisionContext (SynchedEntityData reads) per ray.
        CollisionContext shapeContext = CollisionContext.of(entity);

        List<BlockState> collidedBlockStates = new ArrayList<>();
        List<BlockPos> collidedBlockPositions = new ArrayList<>();
        List<BlockHitResult> collidedBlockHits = new ArrayList<>();
        Collision.CollisionType impactType = Collision.CollisionType.NONE;

        for (Vec3 start : rayStarts) {
            Vec3 end = start.add(direction.scale(rayLength));

            // Ship blocks are only reachable through the (expensive, VS-wrapped)
            // Level.clip; far from ships the vanilla-equivalent fast path is used.
            BlockHitResult blockHit = shipsNearby
                    ? level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, entity))
                    : FastRaycast.clip(level, start, end, shapeContext);

            if (blockHit.getType() != HitResult.Type.BLOCK) continue;

            BlockPos hitPos = blockHit.getBlockPos();
            BlockState hitState = level.getBlockState(hitPos);
            Direction hitFace = blockHit.getDirection();
            Vec3 hitNormal = Vec3.atLowerCornerOf(hitFace.getNormal());

            boolean isWater = !hitState.getFluidState().isEmpty() && hitState.getFluidState().is(FluidTags.WATER);
            boolean isOpposing;

            if (isWater) {
                // A water surface counts when skimming across it (water-skip) or
                // plunging into it fast (-0.35 blocks/tick ≈ 7 m/s downward —
                // stepping off a 2-block ledge into a pond must stay silent).
                // The old mostly-horizontal-only rule made vertical dives
                // undetectable, which killed the belly-flop feature outright.
                boolean skimming = fullstop.isMostlyHorizontal();
                boolean plunging = velocity.y < -0.35;
                if (hitFace != Direction.UP || (!skimming && !plunging)) {
                    continue;
                }
                isOpposing = true;
            } else {
                isOpposing = direction.dot(hitNormal) < -0.1;

                // A face only counts when actually approached with some speed —
                // the 0.15 minimum ray reach otherwise reports a "collision" with
                // the wall the entity is standing flush against every single tick.
                if (-fullstop.getPreImpactScaledVelocity().dot(hitNormal) < MIN_APPROACH_SPEED_MPS) {
                    isOpposing = false;
                }

                // A side face on a block whose TOP is level with the entity's
                // feet is the floor ahead, not a wall. The bottom rays start
                // only 0.1 above the surface, so any tiny downward tilt in the
                // travel direction (boat gravity drift, a micro-hop at speed)
                // dips them below that margin and into the NEXT floor block
                // through its seam-side face — which then reads as a wall hit
                // at full horizontal speed. Boats gliding on ice hit this every
                // seam: phantom collision sounds, smashed ice under the boat,
                // and boat-breaking "wall" damage while driving on flat ground.
                if (hitFace.getAxis().isHorizontal() && hitPos.getY() + 1.0 <= box.minY + 0.15) {
                    isOpposing = false;
                }

                // Wall faces are ignored while mostly ascending so jumping flush
                // against a wall never reads as a wall impact — but ONLY below the
                // horizontal damage threshold. Unconditional, this blinded the
                // detector to genuine high-speed climbing impacts (elytra swooping
                // up into a cliff face dealt zero damage).
                if (fullstop.isMostlyUpward() && hitFace.getAxis().isHorizontal()
                        && fullstop.getPreImpactScaledVelocity().horizontalDistance()
                                < FullStopConfig.SERVER.velocityDamageThresholdHorizontal.get()) {
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
