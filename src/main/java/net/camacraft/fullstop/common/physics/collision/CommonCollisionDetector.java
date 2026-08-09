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
import net.minecraft.world.level.block.SlimeBlock;
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

    /**
     * Minimum overlap (blocks) between the entity's swept box and a hit block's
     * cell on the axes perpendicular to the hit face for the hit to be real.
     * Vanilla collision resolution parks an entity within 1.0e-7 of the surface
     * it slides along, so anything at or under that is "flush", not overlapping;
     * 1.0e-4 leaves comfortable float headroom while a genuine approach overlaps
     * a face's cross-section by far more.
     */
    private static final double MIN_CROSS_SECTION_OVERLAP = 1.0e-4;

    /**
     * Whether the entity's swept volume genuinely overlaps the hit block's cell
     * on the two axes perpendicular to the hit face.
     *
     * <p>This kills the seam-graze false positives: vanilla leaves an entity
     * flush against the surface it slides along (within 1.0e-7), so the rays
     * cast from the flush corners run exactly IN the surface plane. The block
     * traversal then walks cells on the far side of that plane, and the clip
     * registers a hit on the seam face of the NEXT block along the surface — a
     * face whose normal fully opposes travel, so its "approach speed" is the
     * entity's entire speed. At high speed that read as a head-on collision
     * while merely rubbing: slime walls mirror-bounced runners back and forth,
     * honey walls dead-stopped and released them in pulses, and the debug rays
     * lit red. A real approach to a face always overlaps its cross-section; a
     * rub along the surface overlaps it by exactly zero.
     */
    public static boolean crossSectionOverlaps(AABB swept, BlockPos hitPos, Direction hitFace) {
        Direction.Axis axis = hitFace.getAxis();
        if (axis != Direction.Axis.X
                && Math.min(swept.maxX, hitPos.getX() + 1.0) - Math.max(swept.minX, hitPos.getX()) <= MIN_CROSS_SECTION_OVERLAP) {
            return false;
        }
        if (axis != Direction.Axis.Y
                && Math.min(swept.maxY, hitPos.getY() + 1.0) - Math.max(swept.minY, hitPos.getY()) <= MIN_CROSS_SECTION_OVERLAP) {
            return false;
        }
        if (axis != Direction.Axis.Z
                && Math.min(swept.maxZ, hitPos.getZ() + 1.0) - Math.max(swept.minZ, hitPos.getZ()) <= MIN_CROSS_SECTION_OVERLAP) {
            return false;
        }
        return true;
    }

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
        // Effective floor, not the raw box floor: a riding passenger's box dips
        // below their vehicle's hull into the floor layer (see effectiveFloorY).
        double effectiveFloor = RaycastUtil.effectiveFloorY(entity);
        AABB rayEnvelope = new AABB(box.minX, Math.min(effectiveFloor + 0.1, box.maxY), box.minZ,
                box.maxX, box.maxY, box.maxZ);
        AABB swept = rayEnvelope.expandTowards(direction.scale(rayLength)).inflate(0.001);
        boolean worldBlocksNearby = FastRaycast.mayHitAnything(level, swept);
        boolean shipsNearby = ShipCompat.anyShipsNearby(level, swept);
        if (!worldBlocksNearby && !shipsNearby) {
            return Collision.NONE;
        }

        FullStopConfig.RaycastMode mode = FullStopConfig.SERVER.raycastMode.get();
        List<Vec3> rayStarts = RaycastUtil.getRayStarts(entity, mode);
        // Full entity box swept along the travel direction, for the seam-graze
        // cross-section test (unlike rayEnvelope, this one keeps the true floor).
        AABB sweptBox = box.expandTowards(direction.scale(rayLength));
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

                // Rays grazing exactly along a surface hit the seam faces of the
                // next blocks along it, whose normals oppose travel at full
                // speed. Only faces whose cross-section the entity's swept box
                // genuinely overlaps are real — see crossSectionOverlaps.
                if (!crossSectionOverlaps(sweptBox, hitPos, hitFace)) {
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
                // Feet = the EFFECTIVE floor: a boat passenger's own box floor
                // is 0.45 inside the ice layer, which made this rule miss.
                if (hitFace.getAxis().isHorizontal() && hitPos.getY() + 1.0 <= effectiveFloor + 0.15) {
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
                // instanceof rather than a Blocks reference so modded SlimeBlock
                // subclasses bounce instead of behaving like honey.
                if (hitState.getBlock() instanceof SlimeBlock) {
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
