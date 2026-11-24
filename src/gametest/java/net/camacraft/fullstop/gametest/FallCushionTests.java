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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * A mod's fall cushion is honoured ({@code common.physics.rules.HarmlessFalls}, {@code fallCushionsHonoured}): a body whose
 * fall distance a mod set below 0 (DimDoors' escape from Limbo sets -500) takes no landing damage while the cushion
 * lasts, as vanilla's fall damage would deal nothing. Pigs with 1000 health are dropped 30 blocks onto stone: with -500
 * (unhurt), with -10 (the cushion used up after ten blocks: hurt as any), with none (the control), and with -500 and the
 * switch off (hurt again, so the rule is what spares it); and 100 blocks with -500 set at the start (unhurt, however long
 * the fall) and with -50 (used up halfway: hurt), and a cushioned pig dropped again without one two seconds after its
 * landing (hurt: the cushion ends with its landing). DimDoors' Limbo rule (a player's landing there) needs DimDoors
 * and a player: pack harness {@code fs_limbo}. Opt-in with the rest of FullStop's game tests:
 * {@code gradlew runGameTestServer -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class FallCushionTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_fall_cushion";
    /** The switch-off test has a batch of its own: it flips the server config, and a batch's tests run at once. */
    private static final String BATCH_OFF = "fullstop_fall_cushion_off";
    private static final int TIMEOUT = 900;
    private static final float HEALTH = 1000.0f;
    private static final double DROP = 30.0;

    private FallCushionTests() {
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigOnAFallCushionLandsThirtyBlocksDownUnhurt(GameTestHelper helper) {
        drop(helper, 0, -500.0f, lost -> helper.assertTrue(lost == 0.0, String.format(Locale.ROOT,
                "a 30-block fall on a -500 fall cushion cost %.1f health (vanilla: nothing)", lost)));
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigWhoseCushionRanOutIsHurtAsAny(GameTestHelper helper) {
        drop(helper, 1, -10.0f, lost -> helper.assertTrue(lost >= 5.0, String.format(Locale.ROOT,
                "a 30-block fall on a -10 cushion (used up after ten blocks) cost only %.1f health", lost)));
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigWithoutACushionIsHurt(GameTestHelper helper) {
        drop(helper, 2, 0.0f, lost -> helper.assertTrue(lost >= 5.0, String.format(Locale.ROOT,
                "a 30-block fall onto stone cost only %.1f health", lost)));
    }

    /** However long the fall: the cushion set at its start spares a landing a hundred blocks down. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigOnAFallCushionLandsAHundredBlocksDownUnhurt(GameTestHelper helper) {
        drop(helper, 4, -500.0f, 100.0, lost -> helper.assertTrue(lost == 0.0, String.format(Locale.ROOT,
                "a 100-block fall on a -500 fall cushion set at its start cost %.1f health (vanilla: nothing)", lost)));
    }

    /** A cushion of 50 used up halfway down a 100-block fall: the second half is a fall like any. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigWhoseCushionRanOutHalfwayDownAHundredBlocksIsHurt(GameTestHelper helper) {
        drop(helper, 5, -50.0f, 100.0, lost -> helper.assertTrue(lost >= 5.0, String.format(Locale.ROOT,
                "a 100-block fall on a -50 cushion (used up halfway) cost only %.1f health", lost)));
    }

    /** The cushion ends with its landing: the same pig dropped again 30 blocks, with no cushion, two seconds later is hurt. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aCushionDoesNotOutlastItsLanding(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 6);
        fill(level, o.offset(-2, -2, -2), o.offset(2, -1, 2), Blocks.STONE);
        fill(level, o.offset(-2, 0, -2), o.offset(2, 3, 2), Blocks.AIR);
        LivingEntity[] pig = new LivingEntity[1];
        double[] first = new double[1];
        whenTicking(helper, o)
                .thenExecute(() -> {
                    pig[0] = pig(level, new Vec3(o.getX() + 0.5, o.getY() + DROP, o.getZ() + 0.5));
                    pig[0].fallDistance = -500.0f;
                })
                .thenWaitUntil(() -> helper.assertTrue(onFloor(pig[0], o), "the pig hasn't reached the floor"))
                .thenExecuteAfter(40, () -> {
                    first[0] = pig[0].getMaxHealth() - pig[0].getHealth();
                    pig[0].teleportTo(o.getX() + 0.5, o.getY() + DROP, o.getZ() + 0.5);
                    pig[0].setDeltaMovement(Vec3.ZERO);
                })
                .thenWaitUntil(() -> helper.assertTrue(pig[0].getY() < o.getY() + DROP - 2.0, "the pig hasn't started its second fall"))
                .thenWaitUntil(() -> helper.assertTrue(onFloor(pig[0], o), "the pig hasn't reached the floor again"))
                .thenExecuteAfter(20, () -> {
                    double second = pig[0].getMaxHealth() - pig[0].getHealth() - first[0];
                    LOGGER.info("[FullStop gametest] a pig dropped {} blocks on a -500 cushion, then again without one: {} and {} health lost",
                            (int) DROP, String.format(Locale.ROOT, "%.1f", first[0]), String.format(Locale.ROOT, "%.1f", second));
                    helper.assertTrue(first[0] == 0.0, String.format(Locale.ROOT, "the cushioned fall cost %.1f", first[0]));
                    helper.assertTrue(second >= 5.0, String.format(Locale.ROOT, "the second fall, without a cushion, cost only %.1f: the cushion outlasted its landing", second));
                    pig[0].discard();
                })
                .thenSucceed();
    }

    @GameTest(template = FLAT, batch = BATCH_OFF, timeoutTicks = TIMEOUT)
    public static void withTheSwitchOffAPigOnAFallCushionIsHurt(GameTestHelper helper) {
        FullStopConfig.SERVER.fallCushionsHonoured.set(false);
        drop(helper, 3, -500.0f, lost -> {
            FullStopConfig.SERVER.fallCushionsHonoured.set(true);
            helper.assertTrue(lost >= 5.0, String.format(Locale.ROOT,
                    "with the switch off a 30-block fall on a cushion cost only %.1f health: the rule isn't what spares it", lost));
        });
    }

    // ------------------------------------------------------------------
    // Rigs
    // ------------------------------------------------------------------

    /** A pig dropped {@link #DROP} blocks onto a stone floor with its fall distance set to {@code cushion}. */
    private static void drop(GameTestHelper helper, int lane, float cushion, DoubleConsumer check) {
        drop(helper, lane, cushion, DROP, check);
    }

    /** A pig dropped {@code height} blocks onto a stone floor with its fall distance set to {@code cushion} at the start. */
    private static void drop(GameTestHelper helper, int lane, float cushion, double height, DoubleConsumer check) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, lane);
        fill(level, o.offset(-2, -2, -2), o.offset(2, -1, 2), Blocks.STONE);
        fill(level, o.offset(-2, 0, -2), o.offset(2, 3, 2), Blocks.AIR);
        LivingEntity[] pig = new LivingEntity[1];
        whenTicking(helper, o)
                .thenExecute(() -> {
                    pig[0] = pig(level, new Vec3(o.getX() + 0.5, o.getY() + height, o.getZ() + 0.5));
                    pig[0].fallDistance = cushion;
                })
                .thenWaitUntil(() -> helper.assertTrue(onFloor(pig[0], o), "the pig hasn't reached the floor"))
                .thenExecuteAfter(20, () -> {
                    double lost = pig[0].getMaxHealth() - pig[0].getHealth();
                    LOGGER.info("[FullStop gametest] a pig dropped {} blocks onto stone with fall distance {} (cushions honoured: {}): {} health lost",
                            (int) height, cushion, FullStopConfig.SERVER.fallCushionsHonoured.get(), String.format(Locale.ROOT, "%.1f", lost));
                    check.accept(lost);
                    pig[0].discard();
                })
                .thenSucceed();
    }

    /** A lane of its own for each rig, clear of the other suites' lanes (theirs: 4096 + 3072 + n * 256, n up to 295). */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        ServerLevel level = helper.getLevel();
        BlockPos plot = helper.absolutePos(BlockPos.ZERO);
        int groundTop = level.getMinBuildHeight() + 4;
        BlockPos o = new BlockPos(plot.getX() + 8, groundTop, plot.getZ() + 4096 + 3072 + (300 + lane) * 256);
        for (int cx = (o.getX() - 8) >> 4; cx <= (o.getX() + 16) >> 4; cx++) {
            for (int cz = (o.getZ() - 8) >> 4; cz <= (o.getZ() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    private static boolean onFloor(LivingEntity pig, BlockPos o) {
        return pig == null || !pig.isAlive() || (pig.onGround() && pig.getY() <= o.getY() + 0.01);
    }

    private static void fill(ServerLevel level, BlockPos from, BlockPos to, Block block) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static GameTestSequence whenTicking(GameTestHelper helper, BlockPos o) {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                helper.getLevel().isPositionEntityTicking(o.above(10)) && helper.getLevel().isPositionEntityTicking(o.above(40)),
                "the lane doesn't tick entities yet"));
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
}
