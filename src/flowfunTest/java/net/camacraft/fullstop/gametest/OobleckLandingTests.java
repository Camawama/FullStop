package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Landing on Flow Fun's oobleck (shear-thickening: solid under a sudden blow, Camafy's
 * {@code solidAboveMps}, 5 m/s): a fall stops on its surface like on a block and Full Stop hurts the
 * body about as much as stone would; a body set down gently sinks; a short drop is caught at the
 * surface without harm and then sinks once still. Needs Flow Fun on the test runtime:
 * {@code gradlew runGameTestServer -PshipTests -PflowfunTests} (Flow Fun from mavenLocal:
 * {@code gradlew publishToMavenLocal} in Flow Fun first; {@code -Pflowfun_version=...} if not 0.1.1).
 *
 * <p>Bodies have 1000 health so every landing is survivable and the whole cost of it is measured.
 * The player-sized body is a husk (a player's box, and no burning in the sun).</p>
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class OobleckLandingTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_oobleck";
    private static final int TIMEOUT = 600;
    private static final ResourceLocation OOBLECK = new ResourceLocation("flowfun", "oobleck");
    private static final double DROP = 20.0;
    private static final int HALF = 2;
    private static final float HEALTH = 1000.0f;

    private OobleckLandingTests() {
    }

    /** A pig dropped 20 blocks onto oobleck stops on the surface and is hurt about as much as on stone. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigDroppedTwentyBlocksOntoOobleckStopsOnItLikeOnStone(GameTestHelper helper) {
        compareWithStone(helper, EntityType.PIG, 0);
    }

    /** The same with a player-sized body (a husk: a player's box, and it doesn't burn in the sun). */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPlayerSizedBodyDroppedOntoOobleckStopsOnItLikeOnStone(GameTestHelper helper) {
        compareWithStone(helper, EntityType.HUSK, 2);
    }

    /** A pig set down on the surface with no speed sinks into it, unhurt. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigSetDownOnOobleckSinks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 4);
        double surface = pool(level, o, oobleck(helper), 3);
        Mob[] pig = new Mob[1];
        whenTicking(helper, o)
                .thenExecute(() -> pig[0] = body(level, EntityType.PIG, new Vec3(o.getX() + 0.5, surface, o.getZ() + 0.5)))
                .thenExecuteAfter(80, () -> {
                    double sank = surface - pig[0].getY();
                    report("a pig set down on oobleck", String.format(Locale.ROOT, "sank %.2f blocks in 4 s, lost %.1f health",
                            sank, lost(pig[0])));
                    helper.assertTrue(sank > 0.5, String.format(Locale.ROOT, "a pig set down on oobleck sank only %.2f blocks", sank));
                    helper.assertTrue(lost(pig[0]) == 0.0, "a pig set down on oobleck was hurt");
                    pig[0].discard();
                })
                .thenSucceed();
    }

    /**
     * A pig dropped one block lands at about 7.6 m/s, above oobleck's 5 m/s: the surface catches it,
     * with no harm (far below any landing damage), and once still it sinks.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aOneBlockDropIsCaughtUnhurtThenSinks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 6);
        double surface = pool(level, o, oobleck(helper), 3);
        Mob[] pig = new Mob[1];
        double[] arrival = {Double.NaN};
        whenTicking(helper, o)
                .thenExecute(() -> pig[0] = body(level, EntityType.PIG, new Vec3(o.getX() + 0.5, surface + 1.0, o.getZ() + 0.5)))
                .thenWaitUntil(() -> {
                    if (Double.isNaN(arrival[0]) && pig[0].getY() <= surface + 0.02) arrival[0] = pig[0].getY();
                    helper.assertTrue(!Double.isNaN(arrival[0]), "the pig hasn't reached the surface");
                })
                .thenExecuteAfter(80, () -> {
                    double caughtAt = surface - arrival[0];
                    double sank = surface - pig[0].getY();
                    report("a one-block drop onto oobleck", String.format(Locale.ROOT,
                            "caught %.2f blocks below the surface, lost %.1f health, then sank %.2f", caughtAt, lost(pig[0]), sank));
                    helper.assertTrue(caughtAt < 0.15, String.format(Locale.ROOT,
                            "a one-block drop went %.2f blocks into the oobleck on impact", caughtAt));
                    helper.assertTrue(lost(pig[0]) == 0.0, "a one-block drop onto oobleck hurt");
                    helper.assertTrue(sank > 0.5, String.format(Locale.ROOT, "once still, the pig sank only %.2f blocks", sank));
                    pig[0].discard();
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // Rigs
    // ------------------------------------------------------------------

    /** Drops one body onto stone and one onto oobleck, 20 blocks each, and compares what each landing cost. */
    private static void compareWithStone(GameTestHelper helper, EntityType<? extends Mob> type, int lanes) {
        ServerLevel level = helper.getLevel();
        BlockPos stoneLane = lane(helper, lanes);
        BlockPos oobleckLane = lane(helper, lanes + 1);
        double stoneTop = pool(level, stoneLane, null, 0);
        double surface = pool(level, oobleckLane, oobleck(helper), 3);
        Mob[] bodies = new Mob[2];
        double[] arrival = {Double.NaN};
        boolean[] landed = {false, false};
        whenTicking(helper, stoneLane, oobleckLane)
                .thenExecute(() -> {
                    bodies[0] = body(level, type, new Vec3(stoneLane.getX() + 0.5, stoneTop + DROP, stoneLane.getZ() + 0.5));
                    bodies[1] = body(level, type, new Vec3(oobleckLane.getX() + 0.5, surface + DROP, oobleckLane.getZ() + 0.5));
                })
                .thenWaitUntil(() -> {
                    if (!landed[0] && bodies[0].onGround()) landed[0] = true;
                    // the impact tick: where the body first is at or below the surface (caught: on it;
                    // gone through: a tick's fall, over a block, below it)
                    if (!landed[1] && bodies[1].getY() <= surface + 0.02) {
                        arrival[0] = bodies[1].getY();
                        landed[1] = true;
                    }
                    helper.assertTrue(landed[0] && landed[1], "the bodies haven't both landed");
                })
                .thenExecuteAfter(20, () -> {
                    double stone = lost(bodies[0]);
                    double goo = lost(bodies[1]);
                    double caughtAt = surface - arrival[0];
                    report("20-block falls (" + type.getDescriptionId() + ")", String.format(Locale.ROOT,
                            "stone %.1f health, oobleck %.1f (met %.2f blocks below its surface)", stone, goo, caughtAt));
                    helper.assertTrue(stone >= 3.0, String.format(Locale.ROOT, "a 20-block fall onto stone cost only %.1f health", stone));
                    helper.assertTrue(caughtAt < 0.15, String.format(Locale.ROOT,
                            "the fall went %.2f blocks into the oobleck on impact", caughtAt));
                    helper.assertTrue(goo >= 0.7 * stone && goo <= 1.3 * stone, String.format(Locale.ROOT,
                            "landing on oobleck cost %.1f health, not about the %.1f of stone", goo, stone));
                    bodies[0].discard();
                    bodies[1].discard();
                })
                .thenSucceed();
    }

    private static FlowingFluid oobleck(GameTestHelper helper) {
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(OOBLECK);
        if (!(fluid instanceof FlowingFluid flowing) || fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            throw new net.minecraft.gametest.framework.GameTestAssertException(
                    "Flow Fun's oobleck isn't on the runtime: run with -PflowfunTests after publishing Flow Fun to mavenLocal");
        }
        return flowing;
    }

    /**
     * A stone floor under the lane's origin and, with a liquid, a 5x5 stone basin of it {@code depth} deep.
     * Returns the y of the surface a body lands on (the liquid's, else the floor's top).
     */
    private static double pool(ServerLevel level, BlockPos o, FlowingFluid liquid, int depth) {
        fill(level, o.offset(-HALF - 1, -2, -HALF - 1), o.offset(HALF + 1, -1, HALF + 1), Blocks.STONE.defaultBlockState());
        if (liquid == null || depth <= 0) return o.getY();
        fill(level, o.offset(-HALF - 1, 0, -HALF - 1), o.offset(HALF + 1, depth, HALF + 1), Blocks.STONE.defaultBlockState());
        fill(level, o.offset(-HALF, 0, -HALF), o.offset(HALF, depth - 1, HALF), liquid.getSource(false).createLegacyBlock());
        fill(level, o.offset(-HALF, depth, -HALF), o.offset(HALF, depth + 2, HALF), Blocks.AIR.defaultBlockState());
        BlockPos top = o.offset(0, depth - 1, 0);
        return top.getY() + level.getFluidState(top).getHeight(level, top);
    }

    /** A lane of its own for each rig, clear of the other suites' (theirs end at 4096 + 3072 + 184 * 256). */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        ServerLevel level = helper.getLevel();
        BlockPos plot = helper.absolutePos(BlockPos.ZERO);
        int groundTop = level.getMinBuildHeight() + 4;
        BlockPos o = new BlockPos(plot.getX() + 8, groundTop, plot.getZ() + 4096 + 3072 + (200 + lane) * 256);
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
            level.setBlock(pos, state, Block.UPDATE_ALL);
        }
    }

    private static GameTestSequence whenTicking(GameTestHelper helper, BlockPos... lanes) {
        return helper.startSequence().thenWaitUntil(() -> {
            for (BlockPos o : lanes) {
                helper.assertTrue(helper.getLevel().isPositionEntityTicking(o.above(5))
                        && helper.getLevel().isPositionEntityTicking(o.above(30)), "the lanes don't tick entities yet");
            }
        });
    }

    private static <T extends Mob> Mob body(ServerLevel level, EntityType<T> type, Vec3 at) {
        T mob = type.create(level);
        if (mob == null) throw new IllegalStateException("couldn't create " + type);
        mob.moveTo(at.x, at.y, at.z, 0.0f, 0.0f);
        mob.removeFreeWill();
        mob.setPersistenceRequired();
        var health = mob.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.setBaseValue(HEALTH);
        mob.setHealth(HEALTH);
        level.addFreshEntity(mob);
        return mob;
    }

    private static double lost(Mob body) {
        return body == null ? 0.0 : body.getMaxHealth() - body.getHealth();
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop test] {}: {}", what, result);
    }
}
