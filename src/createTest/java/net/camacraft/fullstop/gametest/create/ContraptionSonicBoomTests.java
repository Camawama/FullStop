package net.camacraft.fullstop.gametest.create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.server.physics.SonicBooms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Contraptions break the sound barrier ({@code contraptionSonicBooms}), with the real Create 6.0.8 and the real speed of
 * sound (343 m/s): a creative motor at 256 RPM (26.8 rad/s) drives a bearing whose glued arm reaches 14 blocks from the
 * axis, so its tip moves at about 389 m/s; the same rig with a 4-block arm (121 m/s) never booms. Opt-in:
 * {@code gradlew runGameTestServer -Pcreate}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ContraptionSonicBoomTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "create_flat_48x8x48";
    private static final String BATCH = "fullstop_create_sonic";

    private ContraptionSonicBoomTests() {
    }

    /**
     * A 14-block arm at 256 RPM: its tip passes the speed of sound, so it begins one supersonic stretch (one boom for
     * everyone the shock reaches) and keeps it going while it spins, with a shock train of one crack per turn (one
     * tip: 256/60 = 4.3 a second); stopped, the stretch ends.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 600)
    public static void aLongArmAtFullSpeedBreaksTheSoundBarrier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Rig rig = rig(helper, 50, 14);
        double[] rate = {0.0};
        int[] stretches = {0};
        whenTicking(helper, rig.motor())
                .thenIdle(5)
                .thenExecute(() -> speed(level, rig.motor(), 256))
                .thenWaitUntil(() -> helper.assertTrue(SonicBooms.isLive(level, key(level, rig)), "the arm hasn't gone supersonic ("
                        + describe(level, rig) + ")"))
                .thenIdle(60)
                .thenExecute(() -> {
                    SonicBooms.Key key = key(level, rig);
                    rate[0] = SonicBooms.trainRate(level, key);
                    stretches[0] = SonicBooms.stretchesStarted(level, key);
                    helper.assertTrue(SonicBooms.isLive(level, key), "the stretch ended while the arm still spins");
                    helper.assertTrue(stretches[0] == 1, "the spinning arm began " + stretches[0] + " stretches, not one");
                    helper.assertTrue(Math.abs(rate[0] - 256.0 / 60.0) < 0.3, String.format(Locale.ROOT,
                            "its shock train runs at %.2f a second, not one a turn (%.2f)", rate[0], 256.0 / 60.0));
                    speed(level, rig.motor(), 0);
                })
                .thenWaitUntil(() -> {
                    AbstractContraptionEntity c = contraption(level, rig.armBase());
                    helper.assertTrue(c == null || !SonicBooms.isLive(level, SonicBooms.Key.entity(c)), "stopped, it's still supersonic");
                })
                .thenExecute(() -> report("14-block arm at 256 RPM", String.format(Locale.ROOT,
                        "one supersonic stretch (%d), shock train %.2f pulses a second while it spun; stopped, it ended", stretches[0], rate[0])))
                .thenSucceed();
    }

    /** A 4-block arm at 256 RPM (its tip at about 121 m/s) never booms. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 300)
    public static void aShortArmAtFullSpeedStaysSubsonic(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Rig rig = rig(helper, 52, 4);
        whenTicking(helper, rig.motor())
                .thenIdle(5)
                .thenExecute(() -> speed(level, rig.motor(), 256))
                .thenWaitUntil(() -> helper.assertTrue(contraption(level, rig.armBase()) != null, "the arm hasn't assembled"))
                .thenIdle(100)
                .thenExecute(() -> {
                    SonicBooms.Key key = key(level, rig);
                    int stretches = SonicBooms.stretchesStarted(level, key);
                    report("4-block arm at 256 RPM", "supersonic stretches " + stretches + " (" + describe(level, rig) + ")");
                    helper.assertTrue(stretches == 0, "a 121 m/s tip began " + stretches + " supersonic stretches");
                    speed(level, rig.motor(), 0);
                })
                .thenSucceed();
    }

    // ---- the rigs ---------------------------------------------------------------------------------------------

    record Rig(BlockPos motor, BlockPos armBase, int length) {}

    /** A creative motor under a bearing facing up; its glued plank arm from above the bearing out along +X, {@code length} blocks. */
    private static Rig rig(GameTestHelper helper, int laneIndex, int length) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, laneIndex);
        int reach = length + 2;
        fill(level, o.offset(-reach, 0, -reach), o.offset(reach, 4, reach), Blocks.AIR.defaultBlockState());
        fill(level, o.offset(-3, 1, -3), o.offset(3, 1, 3), Blocks.STONE.defaultBlockState());
        level.setBlock(o, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), Block.UPDATE_ALL);
        BlockPos bearing = o.above();
        level.setBlock(bearing, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP),
                Block.UPDATE_ALL);
        speed(level, o, 0);
        BlockPos armBase = bearing.above();
        BlockState plank = Blocks.OAK_PLANKS.defaultBlockState();
        fill(level, armBase, armBase.offset(length, 0, 0), plank);
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(armBase).minmax(new AABB(armBase.offset(length, 0, 0)))));
        return new Rig(o, armBase, length);
    }

    private static SonicBooms.Key key(ServerLevel level, Rig rig) {
        AbstractContraptionEntity c = contraption(level, rig.armBase());
        return c == null ? new SonicBooms.Key(false, Long.MIN_VALUE) : SonicBooms.Key.entity(c);
    }

    private static String describe(ServerLevel level, Rig rig) {
        AbstractContraptionEntity c = contraption(level, rig.armBase());
        if (c == null) return "no contraption";
        double tip = Math.hypot(rig.length() + 0.5, 0.5) * 256.0 * 2.0 * Math.PI / 60.0;
        return String.format(Locale.ROOT, "contraption %d, its tip at about %.0f m/s of %.0f", c.getId(), tip, SonicBooms.SOUND_MPS);
    }

    private static AbstractContraptionEntity contraption(ServerLevel level, BlockPos near) {
        for (AbstractContraptionEntity c : level.getEntitiesOfClass(AbstractContraptionEntity.class, new AABB(near).inflate(20.0))) {
            if (c.isAlive()) return c;
        }
        return null;
    }

    private static void speed(ServerLevel level, BlockPos motor, int rpm) {
        if (level.getBlockEntity(motor) instanceof CreativeMotorBlockEntity be) be.generatedSpeed.setValue(rpm);
    }

    /** A lane of its own for each rig, at a fixed place well away from the other suites. */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        ServerLevel level = helper.getLevel();
        FullStopConfig.SERVER.debugContraptionLog.set(true);
        int groundTop = level.getMinBuildHeight() + 4;
        BlockPos o = new BlockPos(-20000 - lane * 96, groundTop, 20000);
        for (int cx = (o.getX() - 24) >> 4; cx <= (o.getX() + 24) >> 4; cx++) {
            for (int cz = (o.getZ() - 24) >> 4; cz <= (o.getZ() + 24) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    private static GameTestSequence whenTicking(GameTestHelper helper, BlockPos o) {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                helper.getLevel().isPositionEntityTicking(o.above(2)), "the lane doesn't tick entities yet"));
    }

    private static void fill(ServerLevel level, BlockPos from, BlockPos to, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }
}
