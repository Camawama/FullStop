package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Set;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.Rig;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fill;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fillSet;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fmt;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.report;

/**
 * Pistons moving Valkyrien Skies ships ({@code ShipPistons}): a world piston pushes a raft one block, a sticky one
 * pulls it back, a raft's own piston pushing a wall shoves the raft, and a raft heavier than its pistons can move
 * stays put until there are enough of them. Real VS rafts on a stone floor, compiled only with {@code -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipPistonTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final int TIMEOUT = ShipCollisionTests.TIMEOUT;
    /** Ticks a raft gets to settle on the floor after it is let go. */
    private static final int SETTLE = 30;
    /** Ticks after the piston fires before the raft's move is measured. */
    private static final int AFTER = 30;
    /** A push of one block, as the raft's centre of mass moved (blocks). */
    private static final double MIN_MOVE = 0.5, MAX_MOVE = 1.6;
    /**
     * The tests at the default 6 t a piston. Game tests of a batch run at the same time, so a test may not change the
     * config for itself (another sees it): each batch sets what its tests need before it starts.
     */
    private static final String BATCH = "fullstop_pistons";
    /** The ship's own piston: its ship (planks, a piston, a redstone block) is heavier than one piston's 6 t. */
    private static final String SHOVE_BATCH = "fullstop_pistons_shove";
    private static final double DEFAULT_KG = 6000.0;

    private ShipPistonTests() {
    }

    @BeforeBatch(batch = BATCH)
    public static void atTheDefault(ServerLevel level) {
        FullStopConfig.SERVER.pistonPushMassKg.set(DEFAULT_KG);
    }

    @BeforeBatch(batch = SHOVE_BATCH)
    public static void strongPistons(ServerLevel level) {
        FullStopConfig.SERVER.pistonPushMassKg.set(20000.0);
    }

    @AfterBatch(batch = SHOVE_BATCH)
    public static void backToTheDefault(ServerLevel level) {
        FullStopConfig.SERVER.pistonPushMassKg.set(DEFAULT_KG);
    }

    /** A piston in the world, powered, with its head against the raft's side: the raft moves a block the way it pushes. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aWorldPistonPushesARaftOneBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 90 * 256);
        fill(level, o.offset(-6, 0, -4), o.offset(10, 0, 6), Blocks.STONE.defaultBlockState());
        Rig rig = raft(helper, o);
        if (rig == null) return;
        double[] startX = {Double.NaN};
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenExecuteAfter(SETTLE, () -> {
                    startX[0] = rig.x();
                    piston(level, o.offset(0, 1, 1), Direction.EAST, false);
                    level.setBlock(o.offset(-1, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenExecuteAfter(AFTER, () -> {
                    double moved = rig.x() - startX[0];
                    BlockState piston = level.getBlockState(o.offset(0, 1, 1));
                    rig.pin();
                    report("world piston pushes a raft", "moved " + fmt(moved) + " blocks east, piston extended "
                            + piston.getValue(BlockStateProperties.EXTENDED) + " | " + rig.summary());
                    helper.assertTrue(piston.getValue(BlockStateProperties.EXTENDED), "the piston didn't extend");
                    helper.assertTrue(moved >= MIN_MOVE && moved <= MAX_MOVE, "the raft moved " + fmt(moved) + " blocks, not one");
                    helper.succeed();
                });
    }

    /** A sticky piston pushes the raft a block and, unpowered, pulls it back where it was. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aStickyPistonPullsTheRaftBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 91 * 256);
        fill(level, o.offset(-6, 0, -4), o.offset(10, 0, 6), Blocks.STONE.defaultBlockState());
        Rig rig = raft(helper, o);
        if (rig == null) return;
        double[] startX = {Double.NaN};
        double[] pushedX = {Double.NaN};
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenExecuteAfter(SETTLE, () -> {
                    startX[0] = rig.x();
                    piston(level, o.offset(0, 1, 1), Direction.EAST, true);
                    level.setBlock(o.offset(-1, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenExecuteAfter(AFTER, () -> {
                    pushedX[0] = rig.x();
                    level.setBlock(o.offset(-1, 1, 1), Blocks.AIR.defaultBlockState(), 3);
                })
                .thenExecuteAfter(AFTER, () -> {
                    double pushed = pushedX[0] - startX[0];
                    double back = rig.x() - pushedX[0];
                    BlockState piston = level.getBlockState(o.offset(0, 1, 1));
                    rig.pin();
                    report("sticky piston pulls a raft back", "pushed " + fmt(pushed) + " blocks east, then " + fmt(back)
                            + " (west is negative), piston extended " + piston.getValue(BlockStateProperties.EXTENDED) + " | " + rig.summary());
                    helper.assertTrue(pushed >= MIN_MOVE && pushed <= MAX_MOVE, "the push moved the raft " + fmt(pushed) + " blocks, not one");
                    helper.assertTrue(!piston.getValue(BlockStateProperties.EXTENDED), "the sticky piston didn't retract");
                    helper.assertTrue(back <= -MIN_MOVE && back >= -MAX_MOVE, "the retraction pulled the raft " + fmt(-back) + " blocks, not one");
                    helper.succeed();
                });
    }

    /** A raft carrying a piston that extends into a stone wall: the raft is shoved a block away from the wall. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = SHOVE_BATCH)
    public static void aRaftsOwnPistonPushingAWallShovesTheRaft(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 92 * 256);
        fill(level, o.offset(-8, 0, -4), o.offset(8, 0, 6), Blocks.STONE.defaultBlockState());
        fill(level, o.offset(6, 1, -1), o.offset(6, 3, 3), Blocks.STONE.defaultBlockState());
        Set<BlockPos> blocks = fillSet(level, o.offset(2, 1, 0), o.offset(4, 1, 2), Blocks.OAK_PLANKS.defaultBlockState());
        BlockPos pistonPos = o.offset(5, 1, 1);
        level.setBlock(pistonPos, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST), 3);
        blocks.add(pistonPos);
        Rig rig = Rig.build(helper, blocks, Blocks.OAK_PLANKS, false, 9);
        if (rig == null) return;
        // the ship (nine planks and a piston, then a redstone block, about 9.5 t) is heavier than one piston's default
        // 6 t: this test is about the shove (the mass rule has its own), so its batch lets one piston move 20 t
        double[] startX = {Double.NaN};
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenExecuteAfter(SETTLE, () -> {
                    startX[0] = rig.x();
                    // a redstone block in place of the plank behind the piston, on the ship: the piston extends into the wall
                    Vec3 behind = rig.ref.toShip(Vec3.atCenterOf(o.offset(4, 1, 1)));
                    helper.assertTrue(behind != null, "can't find the ship's block behind its piston");
                    level.setBlock(BlockPos.containing(behind), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenExecuteAfter(AFTER, () -> {
                    double moved = rig.x() - startX[0];
                    double mass = rig.ref.physicalMass();
                    rig.pin();
                    report("a raft's piston pushes a wall", "the raft (" + Math.round(mass) + " kg) moved " + fmt(moved)
                            + " blocks (west is negative) | " + rig.summary());
                    helper.assertTrue(moved <= -MIN_MOVE && moved >= -MAX_MOVE, "the raft moved " + fmt(moved) + " blocks, not one block west");
                    helper.succeed();
                });
    }

    /**
     * A 3x1x4 dirt raft, 15 t at Valkyrien Skies' 1250 kg a block: two pistons (12 t at 6 t each) can't move it and
     * don't even extend; a third one (18 t) moves it a block, and the two ride along with it.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aHeavyRaftNeedsEnoughPistons(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 93 * 256);
        fill(level, o.offset(-6, 0, -4), o.offset(10, 0, 6), Blocks.STONE.defaultBlockState());
        Set<BlockPos> blocks = fillSet(level, o.offset(1, 1, 0), o.offset(3, 1, 3), Blocks.DIRT.defaultBlockState());
        Rig rig = Rig.build(helper, blocks, Blocks.DIRT, false);
        if (rig == null) return;
        double[] startX = {Double.NaN};
        double[] twoX = {Double.NaN};
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenExecuteAfter(SETTLE, () -> {
                    startX[0] = rig.x();
                    piston(level, o.offset(0, 1, 0), Direction.EAST, false);
                    piston(level, o.offset(0, 1, 2), Direction.EAST, false);
                    level.setBlock(o.offset(-1, 1, 0), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                    level.setBlock(o.offset(-1, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenExecuteAfter(AFTER, () -> {
                    twoX[0] = rig.x();
                    double moved = twoX[0] - startX[0];
                    boolean extended = level.getBlockState(o.offset(0, 1, 0)).getValue(BlockStateProperties.EXTENDED)
                            || level.getBlockState(o.offset(0, 1, 2)).getValue(BlockStateProperties.EXTENDED);
                    report("two pistons on a " + Math.round(rig.ref.physicalMass()) + " kg raft", "moved " + fmt(moved)
                            + " blocks, a piston extended: " + extended);
                    helper.assertTrue(!extended, "a piston extended against a raft too heavy for the pair");
                    helper.assertTrue(Math.abs(moved) < 0.3, "two pistons moved the raft " + fmt(moved) + " blocks");
                    piston(level, o.offset(0, 1, 1), Direction.EAST, false);
                    level.setBlock(o.offset(-1, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenExecuteAfter(AFTER, () -> {
                    double moved = rig.x() - twoX[0];
                    rig.pin();
                    report("three pistons on the raft", "moved " + fmt(moved) + " blocks | " + rig.summary());
                    helper.assertTrue(moved >= MIN_MOVE && moved <= MAX_MOVE, "three pistons moved the raft " + fmt(moved) + " blocks, not one");
                    helper.succeed();
                });
    }

    /** A 3x1x3 oak-plank raft (4.5 t) resting on the floor at x 1..3, z 0..2: right in front of the pistons at x 0. */
    private static Rig raft(GameTestHelper helper, BlockPos o) {
        Set<BlockPos> blocks = fillSet(helper.getLevel(), o.offset(1, 1, 0), o.offset(3, 1, 2), Blocks.OAK_PLANKS.defaultBlockState());
        return Rig.build(helper, blocks, Blocks.OAK_PLANKS, false);
    }

    private static void piston(ServerLevel level, BlockPos pos, Direction facing, boolean sticky) {
        level.setBlock(pos, (sticky ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState().setValue(BlockStateProperties.FACING, facing), 3);
    }
}
