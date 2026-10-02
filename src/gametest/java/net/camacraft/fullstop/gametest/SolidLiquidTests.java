package net.camacraft.fullstop.gametest;

import net.camacraft.camafy.liquid.LiquidProperties;
import net.camacraft.camafy.liquid.Liquids;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;

/**
 * Full Stop's side of a liquid that acts as a solid under a sudden blow (Camafy's
 * {@code LiquidProperties.solidAboveMps}, Flow Fun's oobleck): the collision rays meet it as a block
 * when the body moves into it at or above the threshold, judged by the speed INTO the face it meets,
 * and not below. Lava stands in for the liquid here: for the length of each test it is bound to a
 * copy of its own description that acts solid from 4 m/s, and given back after (the tests have a
 * batch of their own, so nothing else runs meanwhile). Opt-in with the rest of Full Stop's game
 * tests: {@code gradlew runGameTestServer -PshipTests}. The landing itself, with the real oobleck,
 * is {@link OobleckLandingTests} ({@code -PflowfunTests}).
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class SolidLiquidTests {

    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_solid_liquid";
    private static final double THRESHOLD_MPS = 4.0;
    private static final ResourceLocation STAND_IN = new ResourceLocation(FullStop.MODID, "test_solid_lava");

    private SolidLiquidTests() {
    }

    /** A body falling at 20 m/s meets a solid-acting liquid's surface as a block; at 2 m/s it doesn't. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 40)
    public static void aFastBodyMeetsASolidActingLiquidAsABlock(GameTestHelper helper) {
        withSolidLava(helper, level -> {
            BlockPos pool = basin(helper, 4, 4, Blocks.LAVA.defaultBlockState());
            Vec3 above = Vec3.atBottomCenterOf(pool).add(0, 3.0, 0);
            Vec3 below = Vec3.atBottomCenterOf(pool).add(0, -1.0, 0);
            BlockHitResult fast = CommonCollisionDetector.clipSolidLiquid(level, above, below, new Vec3(0, -1.0, 0));
            helper.assertTrue(fast != null, "a fall at 20 m/s didn't meet the solid-acting lava");
            helper.assertTrue(fast.getBlockPos().equals(pool) && fast.getDirection() == Direction.UP,
                    "the fall met " + fast.getBlockPos() + " " + fast.getDirection() + ", not the pool's top at " + pool);
            double surface = pool.getY() + level.getFluidState(pool).getHeight(level, pool);
            helper.assertTrue(Math.abs(fast.getLocation().y - surface) < 0.01, String.format(Locale.ROOT,
                    "met at y %.3f, not the liquid's surface at %.3f", fast.getLocation().y, surface));
            BlockHitResult slow = CommonCollisionDetector.clipSolidLiquid(level, above, below, new Vec3(0, -0.1, 0));
            helper.assertTrue(slow == null, "a fall at 2 m/s met the lava as a block");
        });
    }

    /**
     * What counts is the speed INTO the face met: skimming fast across the surface (20 m/s sideways,
     * 2 m/s down) doesn't make the top solid; running into the pool's side at 20 m/s does.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 40)
    public static void onlyTheSpeedIntoTheFaceCounts(GameTestHelper helper) {
        withSolidLava(helper, level -> {
            BlockPos pool = basin(helper, 10, 4, Blocks.LAVA.defaultBlockState());
            Vec3 velocity = new Vec3(1.0, -0.1, 0);
            // just above the surface at the cell's west edge, crossing the surface inside the cell
            Vec3 from = Vec3.atBottomCenterOf(pool).add(-0.5, 0.95, 0);
            Vec3 to = from.add(velocity.normalize().scale(2.5));
            helper.assertTrue(CommonCollisionDetector.clipSolidLiquid(level, from, to, velocity) == null,
                    "skimming across the surface (2 m/s into it) met it as a block");
            // the pool's side, from the west, at 20 m/s into it
            Vec3 side = Vec3.atBottomCenterOf(pool).add(-2.5, 0.4, 0);
            BlockHitResult wall = CommonCollisionDetector.clipSolidLiquid(level, side, side.add(3.0, 0, 0), new Vec3(1.0, 0, 0));
            helper.assertTrue(wall != null && wall.getDirection() == Direction.WEST,
                    "running into the pool's side at 20 m/s didn't meet its west face: " + wall);
        });
    }

    /**
     * Flowing liquid counts at its own height: the old rays picked only source blocks, so a body
     * landing on a liquid's spreading edge met nothing.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 40)
    public static void aFlowingCellIsMetAtItsHeight(GameTestHelper helper) {
        withSolidLava(helper, level -> {
            BlockPos cell = basin(helper, 16, 4, Fluids.LAVA.getFlowing(4, false).createLegacyBlock());
            Vec3 above = Vec3.atBottomCenterOf(cell).add(0, 3.0, 0);
            BlockHitResult hit = CommonCollisionDetector.clipSolidLiquid(level, above, above.add(0, -4.0, 0), new Vec3(0, -1.0, 0));
            helper.assertTrue(hit != null && hit.getBlockPos().equals(cell), "a fall onto a flowing cell didn't meet it: " + hit);
            double height = level.getFluidState(cell).getHeight(level, cell);
            helper.assertTrue(Math.abs(hit.getLocation().y - (cell.getY() + height)) < 0.01, String.format(Locale.ROOT,
                    "met at y %.3f, not at the flowing cell's height %.3f", hit.getLocation().y, cell.getY() + height));
        });
    }

    /** Water (no threshold) is never met as a block, however fast; a waterlogged slab is not a liquid's block. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 40)
    public static void waterStaysALiquid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pool = basin(helper, 22, 4, Blocks.WATER.defaultBlockState());
        Vec3 above = Vec3.atBottomCenterOf(pool).add(0, 3.0, 0);
        helper.assertTrue(CommonCollisionDetector.clipSolidLiquid(level, above, above.add(0, -4.0, 0), new Vec3(0, -3.0, 0)) == null,
                "a fall at 60 m/s met water as a block");
        helper.assertTrue(CommonCollisionDetector.isLiquidBlock(Blocks.WATER.defaultBlockState()), "water's block isn't a liquid block");
        helper.assertTrue(!CommonCollisionDetector.isLiquidBlock(Blocks.STONE_SLAB.defaultBlockState()
                        .setValue(BlockStateProperties.WATERLOGGED, true).setValue(BlockStateProperties.SLAB_TYPE, net.minecraft.world.level.block.state.properties.SlabType.BOTTOM)),
                "a waterlogged slab counted as a liquid's block");
        helper.succeed();
    }

    // ------------------------------------------------------------------
    // Rigs
    // ------------------------------------------------------------------

    /** Runs {@code body} with lava acting solid from 4 m/s, then gives lava its own description back. */
    private static void withSolidLava(GameTestHelper helper, java.util.function.Consumer<ServerLevel> body) {
        LiquidProperties solid = Liquids.LAVA.toBuilder(STAND_IN).solidAboveMps(THRESHOLD_MPS).build();
        Liquids.register(solid);
        Liquids.bind(Fluids.LAVA, solid);
        Liquids.bind(Fluids.FLOWING_LAVA, solid);
        try {
            body.accept(helper.getLevel());
            helper.succeed();
        } finally {
            Liquids.bind(Fluids.LAVA, Liquids.LAVA);
            Liquids.bind(Fluids.FLOWING_LAVA, Liquids.LAVA);
            // no threshold under the stand-in's id any more, so no ray looks for it after the test
            Liquids.register(Liquids.LAVA.toBuilder(STAND_IN).build());
        }
    }

    /** One cell of {@code state} in a stone cup at (x, 1, z) of the plot; returns the cell. */
    private static BlockPos basin(GameTestHelper helper, int x, int z, net.minecraft.world.level.block.state.BlockState state) {
        ServerLevel level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(x, 2, z));
        for (BlockPos pos : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 0, 1))) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
        }
        level.setBlock(cell, state, 2);
        // air above, so the surface is the cell's own
        for (BlockPos pos : BlockPos.betweenClosed(cell.offset(-1, 1, -1), cell.offset(1, 4, 1))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
        return cell.immutable();
    }
}
