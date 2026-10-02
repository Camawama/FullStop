package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Landing in water that is too shallow to stop a fall: the body still hits the bottom, and the
 * bottom hurts like any ground for the speed the water left it. The owner's report of
 * 2026-10-01: a fall into a shallow pool dealt nothing. Cause: since the 2.0 refactor every
 * collision ray picked fluids, and a ray from a body already in water stops at the water it
 * starts in, so the bottom was never seen and the hit was billed as a feet-first dive (5% of
 * the damage). Rays from a body in water look through it again ({@code CommonCollisionDetector}).
 *
 * <p>Pigs with 1000 health are dropped from 30 blocks above the surface of a stone-floored basin
 * of water 1, 2 and 8 deep, and onto the bare stone floor for comparison; the health each loses
 * is the damage of the whole landing (surface and bottom). Opt-in with the rest of FullStop's game
 * tests: {@code gradlew runGameTestServer -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class WaterLandingTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_water";
    private static final int TIMEOUT = 600;
    /** How far above the water's surface (or the bare floor) the pigs are dropped from. */
    private static final double DROP = 30.0;
    /** The basin's inside half-width (a 5x5 pool). */
    private static final int HALF = 2;
    /** Health the pigs get (vanilla caps max health at 1024): enough to live through every landing. */
    private static final float HEALTH = 1000.0f;
    /** What a pig's feet-first water entry from 30 blocks costs by itself (measured: 2.0), with a margin. */
    private static final double SURFACE_ENTRY = 2.5;

    private WaterLandingTests() {
    }

    /** The same fall onto the bare stone floor: what the bottom of a shallow pool should come close to. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigDroppedThirtyBlocksOntoStoneIsHurt(GameTestHelper helper) {
        landing(helper, 0, 0, lost -> helper.assertTrue(lost >= 5.0,
                String.format(Locale.ROOT, "a 30-block fall onto stone cost only %.1f health", lost)));
    }

    /**
     * One block of water barely slows a 30-block fall (the body crosses it within a tick): it hits
     * the stone under it nearly as hard as bare stone.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aFallIntoWaterOneBlockDeepHurtsOnTheBottom(GameTestHelper helper) {
        landing(helper, 1, 1, lost -> helper.assertTrue(lost >= 5.0 && lost > SURFACE_ENTRY,
                String.format(Locale.ROOT, "the bottom of a 1-deep pool cost only %.1f health (a 30-block fall)", lost)));
    }

    /**
     * Two blocks of water slow it some: the bottom still hurts, less than one block of water. Measured
     * against the 1-deep pool in the same batch by running both rigs here.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aFallIntoWaterTwoBlocksDeepHurtsLessThanOneBlockDeep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos shallow = lane(helper, 2);
        BlockPos deeper = lane(helper, 3);
        int shallowSurface = pool(level, shallow, 1);
        int deeperSurface = pool(level, deeper, 2);
        LivingEntity[] pigs = new LivingEntity[2];
        whenTicking(helper, shallow, deeper)
                .thenExecute(() -> {
                    pigs[0] = pig(level, air(shallow, shallowSurface + DROP));
                    pigs[1] = pig(level, air(deeper, deeperSurface + DROP));
                })
                .thenWaitUntil(() -> helper.assertTrue(onBottom(pigs[0], shallow) && onBottom(pigs[1], deeper),
                        "the pigs haven't reached the bottom"))
                .thenExecuteAfter(20, () -> {
                    double one = lost(pigs[0]);
                    double two = lost(pigs[1]);
                    report("30-block falls into 1- and 2-deep water", String.format(Locale.ROOT,
                            "1 deep: %.1f health lost, 2 deep: %.1f", one, two));
                    helper.assertTrue(two > SURFACE_ENTRY, String.format(Locale.ROOT,
                            "the bottom of a 2-deep pool added nothing to the water entry (%.1f) after a 30-block fall", two));
                    helper.assertTrue(two < one, String.format(Locale.ROOT,
                            "2 deep (%.1f) hurt no less than 1 deep (%.1f)", two, one));
                    pigs[0].discard();
                    pigs[1].discard();
                })
                .thenSucceed();
    }

    /**
     * Eight blocks of water stop the fall before the bottom: nothing beyond the water entry itself
     * (2 health for a pig entering feet first at about 24 m/s, the same at every depth before the
     * fix: the surface hit is unchanged).
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aFallIntoWaterEightBlocksDeepHurtsOnlyAtTheSurface(GameTestHelper helper) {
        landing(helper, 4, 8, lost -> helper.assertTrue(lost <= SURFACE_ENTRY,
                String.format(Locale.ROOT, "an 8-deep pool cost %.1f health, more than the water entry's %.1f (a 30-block fall)",
                        lost, SURFACE_ENTRY)));
    }

    // ------------------------------------------------------------------
    // Rigs
    // ------------------------------------------------------------------

    /** Drops a pig 30 blocks into a pool {@code depth} deep (0: onto the bare floor) in lane {@code lane}. */
    private static void landing(GameTestHelper helper, int lane, int depth, java.util.function.DoubleConsumer check) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, lane);
        int surface = pool(level, o, depth);
        LivingEntity[] pig = new LivingEntity[1];
        whenTicking(helper, o, o)
                .thenExecute(() -> pig[0] = pig(level, air(o, surface + DROP)))
                .thenWaitUntil(() -> helper.assertTrue(onBottom(pig[0], o), "the pig hasn't reached the bottom"))
                .thenExecuteAfter(20, () -> {
                    double lost = lost(pig[0]);
                    report(depth == 0 ? "a 30-block fall onto stone" : "a 30-block fall into water " + depth + " deep",
                            String.format(Locale.ROOT, "%.1f health lost", lost));
                    check.accept(lost);
                    pig[0].discard();
                })
                .thenSucceed();
    }

    /**
     * A stone floor under the lane's origin, a 5x5 basin walled in stone and filled with {@code depth}
     * blocks of still water. Returns the y of the water's surface (the floor's top when dry).
     */
    private static int pool(ServerLevel level, BlockPos o, int depth) {
        int floor = o.getY() - 1;
        fill(level, o.offset(-HALF - 1, -2, -HALF - 1), o.offset(HALF + 1, -1, HALF + 1),
                Blocks.STONE.defaultBlockState());
        if (depth > 0) {
            fill(level, o.offset(-HALF - 1, 0, -HALF - 1), o.offset(HALF + 1, depth, HALF + 1),
                    Blocks.STONE.defaultBlockState());
            fill(level, o.offset(-HALF, 0, -HALF), o.offset(HALF, depth - 1, HALF),
                    Blocks.WATER.defaultBlockState());
            // nothing above the water but air
            fill(level, o.offset(-HALF, depth, -HALF), o.offset(HALF, depth + 2, HALF),
                    Blocks.AIR.defaultBlockState());
        }
        return floor + 1 + depth;
    }

    /** A lane of its own for each rig, clear of the other suites' lanes (theirs: 4096 + 3072 + n * 256, n up to 170). */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        ServerLevel level = helper.getLevel();
        BlockPos plot = helper.absolutePos(BlockPos.ZERO);
        int groundTop = level.getMinBuildHeight() + 4; // the flat world: bedrock, dirt, dirt, grass
        BlockPos o = new BlockPos(plot.getX() + 8, groundTop, plot.getZ() + 4096 + 3072 + (180 + lane) * 256);
        for (int cx = (o.getX() - 8) >> 4; cx <= (o.getX() + 16) >> 4; cx++) {
            for (int cz = (o.getZ() - 8) >> 4; cz <= (o.getZ() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    private static void fill(ServerLevel level, BlockPos from, BlockPos to, net.minecraft.world.level.block.state.BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }

    private static Vec3 air(BlockPos o, double y) {
        return new Vec3(o.getX() + 0.5, y, o.getZ() + 0.5);
    }

    /** A sequence that starts once both lanes tick entities. */
    private static GameTestSequence whenTicking(GameTestHelper helper, BlockPos a, BlockPos b) {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                helper.getLevel().isPositionEntityTicking(a.above(10)) && helper.getLevel().isPositionEntityTicking(a.above(40))
                        && helper.getLevel().isPositionEntityTicking(b.above(10)) && helper.getLevel().isPositionEntityTicking(b.above(40)),
                "the lanes don't tick entities yet"));
    }

    /** The pig stands on the floor of its basin (or is gone). */
    private static boolean onBottom(LivingEntity pig, BlockPos o) {
        return pig == null || !pig.isAlive() || (pig.onGround() && pig.getY() <= o.getY() + 0.01);
    }

    private static double lost(LivingEntity pig) {
        return pig == null ? 0.0 : pig.getMaxHealth() - pig.getHealth();
    }

    private static LivingEntity pig(ServerLevel level, Vec3 at) {
        Mob pig = EntityType.PIG.create(level);
        if (pig == null) throw new IllegalStateException("no pig");
        pig.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        pig.goalSelector.removeAllGoals(g -> true);
        pig.setPersistenceRequired();
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTH);
        pig.setHealth(HEALTH);
        level.addFreshEntity(pig);
        return pig;
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }
}
