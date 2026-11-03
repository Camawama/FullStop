package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Blocks that hold a body cushion its fall, as in vanilla (the owner, 2026-10-31: "Falling onto powdered snow gives fall
 * damage, when it should actually dampen fall damage"; {@code common.physics.rules.StuckBlocks}). Pigs with 1000 health
 * are dropped 30 and 10 blocks into a 5x5 well of powder snow two deep over stone, 30 blocks into a well of cobwebs, 30
 * blocks onto a sweet berry bush on dirt, and, for comparison, onto bare stone (which must still hurt) and into powder
 * snow with the rule switched off (which then hurts again, so the rule is what spares them). Vanilla's powder snow lands a
 * fast fall on a 0.9-high shape whose {@code fallOn} only makes a sound and then lets the body sink; a web or a bush is
 * entered and takes the body's velocity. Full Stop measured each of those as a hard stop and billed it. Opt-in with the
 * rest of FullStop's game tests: {@code gradlew runGameTestServer -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class StuckBlockTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_stuck";
    /** The rule-off test has a batch of its own: it flips the server config, and a batch's tests run at once. */
    private static final String BATCH_OFF = "fullstop_stuck_off";
    private static final int TIMEOUT = 600;
    /** The well's inside half-width (5x5). */
    private static final int HALF = 2;
    /** Health the pigs get (vanilla caps max health at 1024): enough to live through every landing. */
    private static final float HEALTH = 1000.0f;

    private StuckBlockTests() {
    }

    /** The control: the same fall onto bare stone hurts. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigDroppedThirtyBlocksOntoStoneIsHurt(GameTestHelper helper) {
        drop(helper, 0, Blocks.STONE.defaultBlockState(), null, 0, 30.0, lost -> helper.assertTrue(lost >= 5.0,
                String.format(Locale.ROOT, "a 30-block fall onto stone cost only %.1f health", lost)));
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigDroppedTenBlocksOntoStoneIsHurt(GameTestHelper helper) {
        drop(helper, 1, Blocks.STONE.defaultBlockState(), null, 0, 10.0, lost -> helper.assertTrue(lost > 0.0,
                String.format(Locale.ROOT, "a 10-block fall onto stone cost nothing (%.1f)", lost)));
    }

    /** Vanilla: the fast fall lands on the snow's 0.9-high shape (a sound, no damage) and the pig sinks to the stone. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigDroppedThirtyBlocksIntoPowderSnowIsNotHurt(GameTestHelper helper) {
        drop(helper, 2, Blocks.STONE.defaultBlockState(), Blocks.POWDER_SNOW.defaultBlockState(), 2, 30.0, unhurt(helper,
                "a 30-block fall into powder snow"));
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigDroppedTenBlocksIntoPowderSnowIsNotHurt(GameTestHelper helper) {
        drop(helper, 3, Blocks.STONE.defaultBlockState(), Blocks.POWDER_SNOW.defaultBlockState(), 2, 10.0, unhurt(helper,
                "a 10-block fall into powder snow"));
    }

    /** A web has no shape: the pig enters it and its velocity goes to a twentieth, then to nothing. It hangs there. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigDroppedThirtyBlocksIntoACobwebIsNotHurt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 4);
        int top = well(level, o, Blocks.STONE.defaultBlockState(), Blocks.COBWEB.defaultBlockState(), 2);
        LivingEntity[] pig = new LivingEntity[1];
        whenTicking(helper, o)
                .thenExecute(() -> pig[0] = pig(level, air(o, top + 30.0)))
                .thenWaitUntil(() -> helper.assertTrue(pig[0] == null || !pig[0].isAlive()
                        || (pig[0].getY() < top && Math.abs(pig[0].getY() - pig[0].yOld) < 0.01), "the pig hasn't settled in the web"))
                .thenExecuteAfter(20, () -> {
                    double lost = lost(pig[0]);
                    report("a 30-block fall into cobwebs", String.format(Locale.ROOT, "%.1f health lost, hanging at %.2f under the top",
                            lost, top - (pig[0] == null ? top : pig[0].getY())));
                    unhurt(helper, "a 30-block fall into cobwebs").accept(lost);
                    pig[0].discard();
                })
                .thenSucceed();
    }

    /** A bush (age 0: no pricks) over dirt; the pig goes through it onto the dirt at the speed the bush left it. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigDroppedThirtyBlocksOntoASweetBerryBushIsNotHurt(GameTestHelper helper) {
        drop(helper, 5, Blocks.DIRT.defaultBlockState(), Blocks.SWEET_BERRY_BUSH.defaultBlockState(), 1, 30.0, unhurt(helper,
                "a 30-block fall onto a sweet berry bush"));
    }

    /** With stuckBlocksCushion off the old reading is back: the snow's stop is billed like a floor's. */
    @GameTest(template = FLAT, batch = BATCH_OFF, timeoutTicks = TIMEOUT)
    public static void aPigDroppedThirtyBlocksIntoPowderSnowWithTheRuleOffIsHurt(GameTestHelper helper) {
        FullStopConfig.SERVER.stuckBlocksCushion.set(false);
        drop(helper, 6, Blocks.STONE.defaultBlockState(), Blocks.POWDER_SNOW.defaultBlockState(), 2, 30.0, lost -> {
            FullStopConfig.SERVER.stuckBlocksCushion.set(true);
            helper.assertTrue(lost > 0.0, String.format(Locale.ROOT,
                    "with the rule off a 30-block fall into powder snow cost nothing (%.1f): the rule isn't what spares the pig", lost));
        });
    }

    // ------------------------------------------------------------------
    // Rigs
    // ------------------------------------------------------------------

    private static DoubleConsumer unhurt(GameTestHelper helper, String what) {
        return lost -> helper.assertTrue(lost == 0.0, String.format(Locale.ROOT, "%s cost %.1f health (vanilla: nothing)", what, lost));
    }

    /**
     * Drops a pig {@code height} blocks above the top of a well {@code depth} deep of {@code filling} (none for a bare
     * floor of {@code floor}) in lane {@code lane}, waits for it to reach the floor, and hands the health it lost to {@code check}.
     */
    private static void drop(GameTestHelper helper, int lane, BlockState floor, BlockState filling, int depth, double height,
                             DoubleConsumer check) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, lane);
        int top = well(level, o, floor, filling, depth);
        LivingEntity[] pig = new LivingEntity[1];
        whenTicking(helper, o)
                .thenExecute(() -> pig[0] = pig(level, air(o, top + height)))
                .thenWaitUntil(() -> helper.assertTrue(onFloor(pig[0], o), "the pig hasn't reached the floor"))
                .thenExecuteAfter(20, () -> {
                    double lost = lost(pig[0]);
                    String what = String.format(Locale.ROOT, "a %.0f-block fall %s", height,
                            filling == null ? "onto bare " + name(floor) : "into " + name(filling) + " " + depth + " deep over " + name(floor));
                    report(what, String.format(Locale.ROOT, "%.1f health lost", lost));
                    check.accept(lost);
                    pig[0].discard();
                })
                .thenSucceed();
    }

    private static String name(BlockState state) {
        return net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()).getPath();
    }

    /**
     * A floor of {@code floor} two thick under the lane's origin, a 5x5 well walled in stone and filled {@code depth} high
     * with {@code filling}, air above. Returns the y of the filling's top (the floor's top when there is none).
     */
    private static int well(ServerLevel level, BlockPos o, BlockState floor, BlockState filling, int depth) {
        fill(level, o.offset(-HALF - 1, -2, -HALF - 1), o.offset(HALF + 1, -1, HALF + 1), floor);
        if (depth > 0) {
            fill(level, o.offset(-HALF - 1, 0, -HALF - 1), o.offset(HALF + 1, depth, HALF + 1), Blocks.STONE.defaultBlockState());
            fill(level, o.offset(-HALF, 0, -HALF), o.offset(HALF, depth - 1, HALF), filling);
        }
        // nothing above but air, up to where the pig is dropped from
        fill(level, o.offset(-HALF, depth, -HALF), o.offset(HALF, depth + 2, HALF), Blocks.AIR.defaultBlockState());
        return o.getY() + depth;
    }

    /** A lane of its own for each rig, clear of the other suites' lanes (theirs: 4096 + 3072 + n * 256, n up to 240). */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        ServerLevel level = helper.getLevel();
        BlockPos plot = helper.absolutePos(BlockPos.ZERO);
        int groundTop = level.getMinBuildHeight() + 4; // the flat world: bedrock, dirt, dirt, grass
        BlockPos o = new BlockPos(plot.getX() + 8, groundTop, plot.getZ() + 4096 + 3072 + (250 + lane) * 256);
        for (int cx = (o.getX() - 8) >> 4; cx <= (o.getX() + 16) >> 4; cx++) {
            for (int cz = (o.getZ() - 8) >> 4; cz <= (o.getZ() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    private static void fill(ServerLevel level, BlockPos from, BlockPos to, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }

    private static Vec3 air(BlockPos o, double y) {
        return new Vec3(o.getX() + 0.5, y, o.getZ() + 0.5);
    }

    /** A sequence that starts once the lane ticks entities, on the ground and up where the pig starts. */
    private static GameTestSequence whenTicking(GameTestHelper helper, BlockPos o) {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                helper.getLevel().isPositionEntityTicking(o.above(10)) && helper.getLevel().isPositionEntityTicking(o.above(40)),
                "the lane doesn't tick entities yet"));
    }

    /** The pig stands on the well's floor (or is gone). */
    private static boolean onFloor(LivingEntity pig, BlockPos o) {
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
