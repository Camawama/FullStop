package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.camacraft.fullstop.server.physics.pressure.LiquidDepth;
import net.camacraft.fullstop.server.physics.pressure.PressureHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Underwater pressure goes by the water over a body, not by where in the world the body is
 * ({@code LiquidDepth}, {@code PressureHandler.handleDepth}; settings {@code deepWaterStartDepth} 63 and
 * {@code pressureDamageDepth} 95 blocks of water overhead).
 *
 * <p>Pigs without AI (they stay where they are put, and do not swim up) with 1000 health sit on the floor of stone
 * basins and shafts of still water. A pool three deep at the bottom of the flat world (Y -60, under the old Y rule's
 * damage line of Y -32) and one at Y 200 press on nobody; 40 blocks of water read 40 and do nothing; 70 drain the air
 * faster and the pig drowns; 100 hurt with {@code fullstop:pressure}; so do 100 blocks reached only round a stone
 * overhang, or round an air pocket over the pig's head; a shaft of falling water presses on nobody. The cost of the
 * measurement is counted for a hundred bodies under a roof (the search) and under the open sky (one column).
 * Opt-in with the rest of FullStop's game tests: {@code gradlew runGameTestServer -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class PressureDepthTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_pressure";
    private static final float HEALTH = 1000.0f;
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private PressureDepthTests() {
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 400)
    public static void aPoolThreeDeepAtTheBottomOfTheWorldPressesOnNobody(GameTestHelper helper) {
        shallowPool(helper, 0, lane(helper, 0));
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 400)
    public static void aPoolThreeDeepAtY200PressesOnNobody(GameTestHelper helper) {
        shallowPool(helper, 1, lane(helper, 1).atY(200));
    }

    private static void shallowPool(GameTestHelper helper, int lane, BlockPos o) {
        ServerLevel level = helper.getLevel();
        shaft(level, o, 2, 3, false);
        LivingEntity[] pig = new LivingEntity[1];
        whenTicking(helper, o)
                .thenExecute(() -> pig[0] = pig(level, o))
                .thenExecuteAfter(100, () -> {
                    double overhead = PressureHandler.waterOverhead(pig[0]);
                    report("a pool 3 deep at Y " + o.getY(), String.format(Locale.ROOT,
                            "water overhead %.2f, health lost %.1f, air %d", overhead, lost(pig[0]), pig[0].getAirSupply()));
                    helper.assertTrue(pig[0].isUnderWater(), "the pig's eyes are not under water");
                    helper.assertTrue(overhead > 1.5 && overhead <= 3.01, String.format(Locale.ROOT, "3 blocks of water read %.2f", overhead));
                    helper.assertTrue(lost(pig[0]) == 0.0, String.format(Locale.ROOT, "a 3-deep pool cost %.1f health", lost(pig[0])));
                    // vanilla drowning alone: one air a tick
                    helper.assertTrue(pig[0].getAirSupply() >= 300 - 110, "the air ran out faster than vanilla's in a 3-deep pool: " + pig[0].getAirSupply());
                    pig[0].discard();
                })
                .thenSucceed();
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 400)
    public static void fortyBlocksOfWaterReadFortyAndDoNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 2);
        shaft(level, o, 0, 40, false);
        LivingEntity[] pig = new LivingEntity[1];
        whenTicking(helper, o)
                .thenExecute(() -> pig[0] = pig(level, o))
                .thenExecuteAfter(60, () -> {
                    double overhead = PressureHandler.waterOverhead(pig[0]);
                    report("a shaft of 40 blocks of water", String.format(Locale.ROOT,
                            "water overhead %.2f, health lost %.1f, air %d", overhead, lost(pig[0]), pig[0].getAirSupply()));
                    helper.assertTrue(overhead >= 39.0 && overhead <= 40.01, String.format(Locale.ROOT, "40 blocks of water read %.2f", overhead));
                    helper.assertTrue(lost(pig[0]) == 0.0, String.format(Locale.ROOT, "40 blocks of water cost %.1f health", lost(pig[0])));
                    helper.assertTrue(pig[0].getAirSupply() >= 300 - 70, "the air ran out faster than vanilla's under 40 blocks: " + pig[0].getAirSupply());
                    pig[0].discard();
                })
                .thenSucceed();
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 600)
    public static void seventyBlocksOfWaterDrainTheAirFasterAndThePigDrowns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 3);
        shaft(level, o, 0, 70, false);
        LivingEntity[] pig = new LivingEntity[1];
        whenTicking(helper, o)
                .thenExecute(() -> pig[0] = pig(level, o))
                .thenExecuteAfter(60, () -> {
                    double overhead = PressureHandler.waterOverhead(pig[0]);
                    report("a shaft of 70 blocks of water after 60 ticks", String.format(Locale.ROOT,
                            "water overhead %.2f, health lost %.1f, air %d", overhead, lost(pig[0]), pig[0].getAirSupply()));
                    helper.assertTrue(overhead >= 69.0 && overhead <= 70.01, String.format(Locale.ROOT, "70 blocks of water read %.2f", overhead));
                    helper.assertTrue(lost(pig[0]) == 0.0, "70 blocks of water hurt before the air was gone");
                    helper.assertTrue(pig[0].getAirSupply() <= 300 - 150, "the air did not run out faster under 70 blocks: " + pig[0].getAirSupply());
                })
                .thenWaitUntil(() -> helper.assertTrue(lost(pig[0]) > 0.0, "the pig never drowned under 70 blocks of water"))
                .thenExecute(() -> {
                    DamageSource last = pig[0].getLastDamageSource();
                    report("a shaft of 70 blocks of water, the first hurt", String.valueOf(last == null ? null : last.getMsgId()));
                    helper.assertTrue(last != null && last.is(DamageTypeTags.IS_DROWNING), "the first hurt under 70 blocks was not drowning: " + (last == null ? null : last.getMsgId()));
                    pig[0].discard();
                })
                .thenSucceed();
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 400)
    public static void aHundredBlocksOfWaterHurt(GameTestHelper helper) {
        deep(helper, 4, "a shaft of 100 blocks of water", 0);
    }

    /** The pig's own column is cut by stone two blocks up; its water joins the full shaft beside it. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 400)
    public static void aHundredBlocksOfWaterRoundAnOverhangHurt(GameTestHelper helper) {
        deep(helper, 5, "100 blocks of water round a stone overhang", 1);
    }

    /** Over the pig's block is air, then stone; its water joins the full shaft two blocks to the side. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 400)
    public static void aHundredBlocksOfWaterRoundAnAirPocketHurt(GameTestHelper helper) {
        deep(helper, 6, "100 blocks of water round an air pocket", 2);
    }

    private static void deep(GameTestHelper helper, int lane, String what, int shape) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, lane);
        shaft(level, o, 1, 100, false);
        if (shape == 1) {
            set(level, o.offset(0, 2, 0), STONE);
            set(level, o.offset(0, 3, 0), STONE);
        } else if (shape == 2) {
            // a row of three along x: the pig's cell with air over it, a stone cap over that; the middle cell's
            // second block is stone so the pocket stays dry; the far cell is the open way up
            set(level, o.offset(0, 2, 0), STONE);
            set(level, o.offset(1, 1, 0), STONE);
            set(level, o.offset(-1, 1, 0), STONE);
            set(level, o.offset(0, 1, 1), STONE);
            set(level, o.offset(0, 1, -1), STONE);
            set(level, o.offset(0, 1, 0), AIR);
        }
        LivingEntity[] pig = new LivingEntity[1];
        whenTicking(helper, o)
                .thenExecute(() -> pig[0] = pig(level, o))
                .thenExecuteAfter(90, () -> {
                    double overhead = PressureHandler.waterOverhead(pig[0]);
                    DamageSource last = pig[0].getLastDamageSource();
                    report(what, String.format(Locale.ROOT, "water overhead %.2f, health lost %.1f in 90 ticks, last hurt %s, the block over the pig: %s",
                            overhead, lost(pig[0]), last == null ? null : last.getMsgId(), level.getBlockState(o.above())));
                    helper.assertTrue(pig[0].isUnderWater(), "the pig's eyes are not under water");
                    helper.assertTrue(overhead >= 95.0, String.format(Locale.ROOT, "%s read %.2f", what, overhead));
                    helper.assertTrue(lost(pig[0]) >= 2.0, String.format(Locale.ROOT, "%s cost %.1f health in 90 ticks", what, lost(pig[0])));
                    helper.assertTrue(last != null && last.is(FullStopDamageSources.PRESSURE), "the hurt was not the pressure's: " + (last == null ? null : last.getMsgId()));
                    if (shape == 2) helper.assertTrue(level.getBlockState(o.above()).isAir(), "the air pocket filled up");
                    pig[0].discard();
                })
                .thenSucceed();
    }

    /** One source at the top of a 100-block shaft: falling water all the way down carries no depth. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 1400)
    public static void aShaftOfFallingWaterPressesOnNobody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 7);
        shaft(level, o, 0, 100, true);
        set(level, o.above(99), WATER);
        LivingEntity[] pig = new LivingEntity[1];
        whenTicking(helper, o)
                .thenWaitUntil(() -> helper.assertTrue(!level.getFluidState(o).isEmpty(), "the water hasn't fallen to the bottom yet"))
                .thenExecute(() -> pig[0] = pig(level, o))
                .thenExecuteAfter(90, () -> {
                    double overhead = PressureHandler.waterOverhead(pig[0]);
                    report("a 100-block shaft of falling water", String.format(Locale.ROOT,
                            "water overhead %.2f, health lost %.1f, eyes in %s", overhead, lost(pig[0]), level.getFluidState(BlockPos.containing(pig[0].getEyePosition()))));
                    helper.assertTrue(overhead < 2.0, String.format(Locale.ROOT, "falling water read %.2f", overhead));
                    helper.assertTrue(lost(pig[0]) == 0.0, String.format(Locale.ROOT, "falling water cost %.1f health", lost(pig[0])));
                    pig[0].discard();
                })
                .thenSucceed();
    }

    /**
     * A hundred pigs on the floor of an 11x11 basin 20 deep: under a stone roof every measurement is the search
     * (the worst case), under the open sky it is one column. Counts the measurements and block looks of 100 ticks
     * and times 2000 forced measurements of each kind.
     */
    @GameTest(template = FLAT, batch = BATCH + "_cost", timeoutTicks = 900)
    public static void aHundredSubmergedBodiesCostAColumnOrASearchASecondEach(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 8);
        int depth = 20;
        shaft(level, o, 5, depth, false);
        fill(level, o.offset(-5, depth, -5), o.offset(5, depth, 5), STONE);
        List<LivingEntity> pigs = new ArrayList<>();
        long[] counted = new long[4];
        whenTicking(helper, o)
                .thenExecute(() -> {
                    for (int i = 0; i < 100; i++) {
                        BlockPos at = o.offset(-5 + i % 10, 0, -5 + i / 10);
                        pigs.add(pig(level, at));
                    }
                })
                .thenExecuteAfter(40, () -> {
                    counted[0] = LiquidDepth.measurements();
                    counted[1] = LiquidDepth.blockLooks();
                })
                .thenExecuteAfter(100, () -> {
                    long measurements = LiquidDepth.measurements() - counted[0];
                    long looks = LiquidDepth.blockLooks() - counted[1];
                    double nanos = timeForced(pigs, 20);
                    double overhead = PressureHandler.waterOverhead(pigs.get(0));
                    report("100 bodies under a roofed 11x11 basin 20 deep, 100 ticks", String.format(Locale.ROOT,
                            "%d measurements, %d block looks (%.0f a measurement, %.0f a body a second); a forced measurement %.1f us; reads %.1f",
                            measurements, looks, looks / (double) Math.max(1, measurements), looks / 100.0 / 5.0, nanos / 1000.0, overhead));
                    helper.assertTrue(measurements <= 100 * 6, "more than a measurement a second a body: " + measurements);
                    helper.assertTrue(looks <= measurements * 256L * 8L, "the search looked at more blocks than its limit allows: " + looks);
                    helper.assertTrue(overhead >= 19.0 && overhead <= 20.01, String.format(Locale.ROOT, "20 blocks of water under a roof read %.2f", overhead));
                    // the roof off: the open sky over the basin
                    fill(level, o.offset(-5, depth, -5), o.offset(5, depth, 5), AIR);
                })
                .thenExecuteAfter(40, () -> {
                    counted[2] = LiquidDepth.measurements();
                    counted[3] = LiquidDepth.blockLooks();
                })
                .thenExecuteAfter(100, () -> {
                    long measurements = LiquidDepth.measurements() - counted[2];
                    long looks = LiquidDepth.blockLooks() - counted[3];
                    double nanos = timeForced(pigs, 20);
                    double overhead = PressureHandler.waterOverhead(pigs.get(0));
                    report("100 bodies under an open 11x11 basin 20 deep, 100 ticks", String.format(Locale.ROOT,
                            "%d measurements, %d block looks (%.0f a measurement, %.0f a body a second); a forced measurement %.1f us; reads %.1f",
                            measurements, looks, looks / (double) Math.max(1, measurements), looks / 100.0 / 5.0, nanos / 1000.0, overhead));
                    helper.assertTrue(looks <= measurements * 30L, "an open column of 20 took more than a column of looks: " + looks);
                    helper.assertTrue(overhead >= 19.0 && overhead <= 20.01, String.format(Locale.ROOT, "20 blocks of open water read %.2f", overhead));
                    for (LivingEntity pig : pigs) pig.discard();
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // Rigs
    // ------------------------------------------------------------------

    /**
     * A stone floor under {@code o} and a stone-walled shaft over it, {@code half} blocks to each side of {@code o}
     * inside and {@code depth} high, filled with still water (or left dry), open to the sky.
     */
    private static void shaft(ServerLevel level, BlockPos o, int half, int depth, boolean dry) {
        fill(level, o.offset(-half - 1, -2, -half - 1), o.offset(half + 1, -1, half + 1), STONE);
        fill(level, o.offset(-half - 1, 0, -half - 1), o.offset(half + 1, depth, half + 1), STONE);
        fill(level, o.offset(-half, 0, -half), o.offset(half, depth - 1, half), dry ? AIR : WATER);
        fill(level, o.offset(-half, depth, -half), o.offset(half, depth, half), AIR);
    }

    /** A lane of its own for each rig, clear of the other suites' lanes (WaterLandingTests: 180 and up). */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        ServerLevel level = helper.getLevel();
        BlockPos plot = helper.absolutePos(BlockPos.ZERO);
        int groundTop = level.getMinBuildHeight() + 4; // the flat world: bedrock, dirt, dirt, grass
        BlockPos o = new BlockPos(plot.getX() + 8, groundTop, plot.getZ() + 4096 + 3072 + (220 + lane) * 256);
        for (int cx = (o.getX() - 8) >> 4; cx <= (o.getX() + 16) >> 4; cx++) {
            for (int cz = (o.getZ() - 8) >> 4; cz <= (o.getZ() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    private static void fill(ServerLevel level, BlockPos from, BlockPos to, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) set(level, pos, state);
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    private static GameTestSequence whenTicking(GameTestHelper helper, BlockPos o) {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                helper.getLevel().isPositionEntityTicking(o) && helper.getLevel().isPositionEntityTicking(o.offset(8, 0, 8)),
                "the lane doesn't tick entities yet"));
    }

    private static double lost(LivingEntity pig) {
        return pig == null ? 0.0 : pig.getMaxHealth() - pig.getHealth();
    }

    /** A pig without AI standing in the block {@code at}: it stays there, and its eyes are in that block. */
    private static LivingEntity pig(ServerLevel level, BlockPos at) {
        Mob pig = EntityType.PIG.create(level);
        if (pig == null) throw new IllegalStateException("no pig");
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, -90.0f, 0.0f);
        pig.setNoAi(true);
        pig.setPersistenceRequired();
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTH);
        pig.setHealth(HEALTH);
        level.addFreshEntity(pig);
        return pig;
    }

    /** Nanoseconds a measurement takes: every body's reading forgotten and taken again, {@code rounds} times over. */
    private static double timeForced(List<LivingEntity> bodies, int rounds) {
        long start = System.nanoTime();
        double sum = 0.0;
        for (int round = 0; round < rounds; round++) {
            for (LivingEntity body : bodies) {
                LiquidDepth.forget(body);
                sum += PressureHandler.waterOverhead(body);
            }
        }
        long taken = System.nanoTime() - start;
        if (sum < 0.0) throw new IllegalStateException();
        return taken / (double) (rounds * Math.max(1, bodies.size()));
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }
}
