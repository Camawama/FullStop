package net.camacraft.fullstop.common.physics.math;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Raycast fast paths for the collision detector's hot loop.
 *
 * <p>{@link Level#clip} is wrapped by Valkyrien Skies (and potentially other
 * mods), which makes every ray several times more expensive than the vanilla
 * traversal, and every {@code ClipContext} constructor builds an
 * EntityCollisionContext that reads SynchedEntityData behind a lock. The
 * methods here let the detector (a) prove cheaply that no ray can hit anything
 * and fire none at all, and (b) when rays do fire far from any ship, run the
 * exact vanilla COLLIDER/SOURCE_ONLY traversal with a single shared
 * {@link CollisionContext} and no per-ray allocations.
 */
public final class FastRaycast {

    /**
     * Above this many block cells the sweep test is skipped (returns "might hit")
     * rather than scanned — bounds the cost for huge entities or extreme speeds.
     */
    private static final int MAX_SWEEP_CELLS = 128;

    private FastRaycast() {
    }

    /**
     * Whether any ray inside {@code swept} could possibly hit a block or fluid.
     * Every cell a clip traverses lies inside the swept volume, so if every cell
     * in it is air, no clip can return a hit and the rays can all be skipped.
     * Conservative: returns true when the volume is too large to scan cheaply.
     *
     * <p>Valkyrien Skies ship blocks live outside the world grid and are NOT
     * visible to this scan — callers near ships must check
     * {@code ShipCompat.anyShipsNearby} separately.
     */
    public static boolean mayHitAnything(Level level, AABB swept) {
        int minX = Mth.floor(swept.minX);
        int minY = Mth.floor(swept.minY);
        int minZ = Mth.floor(swept.minZ);
        int maxX = Mth.floor(swept.maxX);
        int maxY = Mth.floor(swept.maxY);
        int maxZ = Mth.floor(swept.maxZ);

        long cells = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (cells > MAX_SWEEP_CELLS) {
            return true;
        }

        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (!level.getBlockState(pos).isAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vanilla-equivalent {@code level.clip(new ClipContext(from, to, COLLIDER,
     * SOURCE_ONLY, entity))}, minus the per-ray ClipContext/EntityCollisionContext
     * construction and minus any mods' Level.clip wrappers. Only valid when the
     * caller has already established that no VS ship intersects the ray's reach
     * (ship blocks are only reachable through the wrapped Level.clip).
     *
     * @param shapeContext one {@code CollisionContext.of(entity)}, reused across rays
     */
    public static BlockHitResult clip(Level level, Vec3 from, Vec3 to, CollisionContext shapeContext) {
        return BlockGetter.traverseBlocks(from, to, null, (unused, pos) -> {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                return null;
            }

            VoxelShape blockShape = ClipContext.Block.COLLIDER.get(state, level, pos, shapeContext);
            BlockHitResult blockHit = level.clipWithInteractionOverride(from, to, pos, blockShape, state);

            FluidState fluid = state.getFluidState();
            BlockHitResult fluidHit = ClipContext.Fluid.SOURCE_ONLY.canPick(fluid)
                    ? fluid.getShape(level, pos).clip(from, to, pos)
                    : null;

            double blockDist = blockHit == null ? Double.MAX_VALUE : from.distanceToSqr(blockHit.getLocation());
            double fluidDist = fluidHit == null ? Double.MAX_VALUE : from.distanceToSqr(fluidHit.getLocation());
            return blockDist <= fluidDist ? blockHit : fluidHit;
        }, unused -> {
            Vec3 back = from.subtract(to);
            return BlockHitResult.miss(to, Direction.getNearest(back.x, back.y, back.z), BlockPos.containing(to));
        });
    }
}
