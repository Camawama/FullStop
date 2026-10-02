package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.server.physics.ship.ShipPistons;
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
    /**
     * Ticks to wait where a piston must not move the raft (there is no glide to wait for). A move is measured once its
     * glide has finished ({@link Moved}): this server runs ticks back to back when it falls behind, a few milliseconds
     * each, and Valkyrien Skies steps in real time, so a count of ticks can be over before the physics has done anything.
     */
    private static final int AFTER = 30;
    /** A push of one block, as the raft's centre of mass moved (blocks). */
    private static final double MIN_MOVE = 0.5, MAX_MOVE = 1.6;
    /**
     * How close (blocks) a move must land to its block, on any backend: a push that coasted a tenth of a block past the
     * head on Krunch Classic (Valkyrien Skies' default) left a pull ending inside the piston base, and VS kicked the raft
     * back out of it, half a block.
     */
    private static final double EXACT = 0.05;
    /**
     * The tests at the default 6 t a piston. Game tests of a batch run at the same time, so a test may not change the
     * config for itself (another sees it): each batch sets what its tests need before it starts.
     */
    private static final String BATCH = "fullstop_pistons";
    /** The ship's own piston: its ship (planks, a piston, a redstone block) is heavier than one piston's 6 t. */
    private static final String SHOVE_BATCH = "fullstop_pistons_shove";
    private static final double DEFAULT_KG = 6000.0;
    /**
     * The tests that a piston's push never breaks anything: anything breaks from 1 m/s (not 5) in this batch, so a
     * piston's contacts (at the head's 10 m/s) judged as crashes would break blocks for certain.
     */
    private static final String STRICT_BATCH = "fullstop_pistons_strict";

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

    @BeforeBatch(batch = STRICT_BATCH)
    public static void anythingBreaks(ServerLevel level) {
        FullStopConfig.SERVER.breakMinSpeed.set(1.0);
        FullStopConfig.SERVER.pistonPushMassKg.set(20000.0);
    }

    @AfterBatch(batch = STRICT_BATCH)
    public static void breakingAsUsual(ServerLevel level) {
        FullStopConfig.SERVER.breakMinSpeed.set(5.0);
        FullStopConfig.SERVER.pistonPushMassKg.set(DEFAULT_KG);
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
        Moved[] move = new Moved[2];
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenExecuteAfter(SETTLE, () -> {
                    startX[0] = rig.x();
                    move[0] = new Moved(rig);
                    piston(level, o.offset(0, 1, 1), Direction.EAST, false);
                    level.setBlock(o.offset(-1, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[0].demandDone())
                .thenExecute(() -> {
                    double moved = rig.x() - startX[0];
                    BlockState piston = level.getBlockState(o.offset(0, 1, 1));
                    rig.pin();
                    report("world piston pushes a raft", "moved " + fmt(moved) + " blocks east, piston extended "
                            + piston.getValue(BlockStateProperties.EXTENDED) + " | " + rig.summary());
                    helper.assertTrue(piston.getValue(BlockStateProperties.EXTENDED), "the piston didn't extend");
                    helper.assertTrue(Math.abs(moved - 1.0) <= EXACT, "the raft moved " + fmt(moved) + " blocks, not one");
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
        Moved[] move = new Moved[2];
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenExecuteAfter(SETTLE, () -> {
                    startX[0] = rig.x();
                    move[0] = new Moved(rig);
                    piston(level, o.offset(0, 1, 1), Direction.EAST, true);
                    level.setBlock(o.offset(-1, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[0].demandDone())
                .thenExecute(() -> {
                    pushedX[0] = rig.x();
                    move[1] = new Moved(rig);
                    level.setBlock(o.offset(-1, 1, 1), Blocks.AIR.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[1].demandDone())
                .thenExecute(() -> {
                    double pushed = pushedX[0] - startX[0];
                    double back = rig.x() - pushedX[0];
                    double home = rig.x() - startX[0];
                    BlockState piston = level.getBlockState(o.offset(0, 1, 1));
                    rig.pin();
                    report("sticky piston pulls a raft back", "pushed " + fmt(pushed) + " blocks east, then " + fmt(back)
                            + " (west is negative), piston extended " + piston.getValue(BlockStateProperties.EXTENDED) + " | " + rig.summary());
                    helper.assertTrue(Math.abs(pushed - 1.0) <= EXACT, "the push moved the raft " + fmt(pushed) + " blocks, not one");
                    helper.assertTrue(!piston.getValue(BlockStateProperties.EXTENDED), "the sticky piston didn't retract");
                    helper.assertTrue(Math.abs(back + 1.0) <= EXACT, "the retraction pulled the raft " + fmt(-back) + " blocks, not one");
                    helper.assertTrue(Math.abs(home) <= EXACT, "the raft ended " + fmt(home) + " blocks from where it started (west is negative: "
                            + "inside the piston base)");
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
        Moved[] move = new Moved[2];
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenExecuteAfter(SETTLE, () -> {
                    startX[0] = rig.x();
                    // a redstone block in place of the plank behind the piston, on the ship: the piston extends into the wall
                    Vec3 behind = rig.ref.toShip(Vec3.atCenterOf(o.offset(4, 1, 1)));
                    helper.assertTrue(behind != null, "can't find the ship's block behind its piston");
                    move[0] = new Moved(rig);
                    level.setBlock(BlockPos.containing(behind), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[0].demandDone())
                .thenExecute(() -> {
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
        Moved[] move = new Moved[2];
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
                    move[0] = new Moved(rig);
                    piston(level, o.offset(0, 1, 1), Direction.EAST, false);
                    level.setBlock(o.offset(-1, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[0].demandDone())
                .thenExecute(() -> {
                    double moved = rig.x() - twoX[0];
                    rig.pin();
                    report("three pistons on the raft", "moved " + fmt(moved) + " blocks | " + rig.summary());
                    helper.assertTrue(Math.abs(moved - 1.0) <= EXACT, "three pistons moved the raft " + fmt(moved) + " blocks, not one");
                    helper.succeed();
                });
    }

    /**
     * A raft's own piston extending into a stone wall shoves the raft off it and breaks nothing: not the wall, not the
     * raft's planks, not the piston (the owner's report: pistons on ships broke when they extended into the terrain).
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = STRICT_BATCH)
    public static void aRaftsPistonPushingAWallBreaksNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 180 * 256);
        fill(level, o.offset(-8, 0, -4), o.offset(8, 0, 6), Blocks.STONE.defaultBlockState());
        java.util.List<BlockPos> wall = fill(level, o.offset(6, 1, -1), o.offset(6, 3, 3), Blocks.STONE.defaultBlockState());
        Set<BlockPos> blocks = fillSet(level, o.offset(2, 1, 0), o.offset(4, 1, 2), Blocks.OAK_PLANKS.defaultBlockState());
        BlockPos pistonPos = o.offset(5, 1, 1);
        level.setBlock(pistonPos, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST), 3);
        blocks.add(pistonPos);
        Rig rig = Rig.build(helper, blocks, Blocks.OAK_PLANKS, false, 9);
        if (rig == null) return;
        double[] startX = {Double.NaN};
        Moved[] move = new Moved[2];
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenExecuteAfter(SETTLE, () -> {
                    startX[0] = rig.x();
                    Vec3 behind = rig.ref.toShip(Vec3.atCenterOf(o.offset(4, 1, 1)));
                    helper.assertTrue(behind != null, "can't find the ship's block behind its piston");
                    move[0] = new Moved(rig);
                    level.setBlock(BlockPos.containing(behind), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[0].demandDone())
                .thenExecute(() -> {
                    double moved = rig.x() - startX[0];
                    int planks = rig.count(Blocks.OAK_PLANKS);
                    int pistons = rig.count(Blocks.PISTON);
                    int wallLeft = 0;
                    for (BlockPos pos : wall) if (level.getBlockState(pos).is(Blocks.STONE)) wallLeft++;
                    ShipPistons.GlideReport glide = move[0].report;
                    rig.pin();
                    String out = "moved " + fmt(moved) + " blocks (west is negative), planks " + planks + "/8, piston "
                            + pistons + "/1, wall " + wallLeft + "/" + wall.size() + ", glide " + glide + " | " + rig.summary();
                    report("a raft's piston against a wall, breaking from 1 m/s", out);
                    helper.assertTrue(wallLeft == wall.size(), "the piston's push broke the wall: " + out);
                    helper.assertTrue(planks == 8, "the push broke the raft's planks: " + out);
                    helper.assertTrue(pistons == 1, "the push broke the raft's piston: " + out);
                    helper.assertTrue(moved <= -MIN_MOVE && moved >= -MAX_MOVE, "the raft moved " + fmt(moved) + " blocks, not one block west");
                    // its centre of mass moved with the redstone block the test put on it: the glide measured a fixed point
                    helper.assertTrue(glide != null && Math.abs(glide.travelled() - 1.0) <= EXACT, "the raft didn't go a block: " + out);
                    helper.succeed();
                });
    }

    /** A world piston pushing a raft into a second raft right in front of it: the push breaks neither. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = STRICT_BATCH)
    public static void aPistonPushingARaftIntoAnotherBreaksNeither(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 181 * 256);
        fill(level, o.offset(-6, 0, -4), o.offset(14, 0, 6), Blocks.STONE.defaultBlockState());
        Rig pushed = raft(helper, o);
        if (pushed == null) return;
        Rig other = Rig.build(helper, fillSet(level, o.offset(5, 1, 0), o.offset(7, 1, 2), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, false);
        if (other == null) return;
        double[] startX = {Double.NaN, Double.NaN};
        Moved[] move = new Moved[1];
        helper.onEachTick(pushed::record);
        helper.onEachTick(other::record);
        helper.startSequence()
                .thenWaitUntil(pushed::demandReady)
                .thenWaitUntil(other::demandReady)
                .thenExecute(() -> {
                    pushed.release();
                    other.release();
                })
                .thenExecuteAfter(SETTLE, () -> {
                    startX[0] = pushed.x();
                    startX[1] = other.x();
                    move[0] = new Moved(pushed);
                    piston(level, o.offset(0, 1, 1), Direction.EAST, false);
                    level.setBlock(o.offset(-1, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[0].demandDone())
                .thenExecute(() -> {
                    double moved = pushed.x() - startX[0];
                    double shoved = other.x() - startX[1];
                    int a = pushed.count(Blocks.OAK_PLANKS);
                    int b = other.count(Blocks.OAK_PLANKS);
                    pushed.pin();
                    other.pin();
                    String out = "pushed raft moved " + fmt(moved) + ", the one in front " + fmt(shoved) + " blocks; planks "
                            + a + "/9 and " + b + "/9 | " + pushed.summary() + " | " + other.summary();
                    report("a piston pushes a raft into another, breaking from 1 m/s", out);
                    helper.assertTrue(a == 9 && b == 9, "the push broke planks: " + out);
                    helper.assertTrue(moved >= MIN_MOVE, "the pushed raft didn't move: " + out);
                    helper.succeed();
                });
    }

    /**
     * The push and the pull go with the head: the head moves a block in its two ticks (0.1 s), and the raft gets there
     * in about that much physics time, not ticks later. Physics time, not game ticks: this server ticks faster than
     * physics.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aStickyPistonsRaftMovesWithItsHead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 182 * 256);
        fill(level, o.offset(-6, 0, -4), o.offset(10, 0, 6), Blocks.STONE.defaultBlockState());
        Rig rig = raft(helper, o);
        if (rig == null) return;
        ShipPistons.GlideReport[] reports = new ShipPistons.GlideReport[2];
        double[] xs = {Double.NaN, Double.NaN, Double.NaN};
        Moved[] move = new Moved[2];
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenExecuteAfter(SETTLE, () -> {
                    xs[0] = rig.x();
                    move[0] = new Moved(rig);
                    piston(level, o.offset(0, 1, 1), Direction.EAST, true);
                    level.setBlock(o.offset(-1, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[0].demandDone())
                .thenExecute(() -> {
                    xs[1] = rig.x();
                    reports[0] = move[0].report;
                    move[1] = new Moved(rig);
                    level.setBlock(o.offset(-1, 1, 1), Blocks.AIR.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[1].demandDone())
                .thenExecute(() -> {
                    xs[2] = rig.x();
                    reports[1] = move[1].report;
                    rig.pin();
                    String out = "pushed " + fmt(xs[1] - xs[0]) + " blocks: " + reports[0] + "; pulled " + fmt(xs[2] - xs[1])
                            + " blocks: " + reports[1] + " | " + rig.summary();
                    report("sticky piston: the raft with its head", out);
                    for (int i = 0; i < 2; i++) {
                        ShipPistons.GlideReport r = reports[i];
                        String which = i == 0 ? "push" : "pull";
                        helper.assertTrue(r != null, "no " + which + " glide finished: " + out);
                        helper.assertTrue(Math.abs(r.travelled() - 1.0) <= EXACT, "the " + which + " didn't land a block on: " + out);
                        helper.assertTrue(r.seconds() <= 0.25, "the " + which + " took " + fmt(r.seconds()) + " s of physics, the head 0.1 s: " + out);
                    }
                    helper.assertTrue(reports[0] != reports[1], "the pull made no glide of its own: " + out);
                    helper.assertTrue(Math.abs(xs[1] - xs[0] - 1.0) <= EXACT && Math.abs(xs[2] - xs[0]) <= EXACT,
                            "the raft didn't go a block and back: " + out);
                    helper.succeed();
                });
    }

    /**
     * The owner's ship: a lone sticky piston, upside down on the ground, powered from behind. It pushes itself up off the
     * ground a block (and stands on its head), and when the power goes its head pulls it back down: onto the ground, never
     * into it (it went straight through the ground: its base is a moving block with no physics shape while it retracts,
     * and the pull, once it had caught up with the head, was stopped short of all the speed it had).
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void anUpsideDownStickyPistonLiftsItsShipAndSetsItBackDown(GameTestHelper helper) {
        upsideDown(helper, 190, false);
    }

    /** The same piston in the middle of a plank raft (heavier than one piston's 6 t: the 20 t batch). */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = SHOVE_BATCH)
    public static void anUpsideDownStickyPistonUnderARaftSetsItBackDown(GameTestHelper helper) {
        upsideDown(helper, 191, true);
    }

    private static void upsideDown(GameTestHelper helper, int slot, boolean raft) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + slot * 256);
        fill(level, o.offset(-4, 0, -4), o.offset(8, 0, 6), Blocks.STONE.defaultBlockState());
        BlockPos pistonPos = o.offset(2, 1, 1);
        Set<BlockPos> blocks = raft ? fillSet(level, o.offset(1, 1, 0), o.offset(3, 1, 2), Blocks.OAK_PLANKS.defaultBlockState())
                : new java.util.HashSet<>();
        level.setBlock(pistonPos, Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN), 3);
        blocks.add(pistonPos);
        Rig rig = Rig.build(helper, blocks, Blocks.STICKY_PISTON, false, 1);
        if (rig == null) return;
        double floor = o.getY() + 1.0;
        Vec3[] base = {null};
        BlockPos[] shipPiston = {null};
        BlockPos[] power = {null};
        double[] bottom = {Double.NaN, Double.NaN, Double.NaN};
        double[] lowest = {Double.POSITIVE_INFINITY};
        boolean[] pulling = {false};
        Moved[] move = new Moved[2];
        helper.onEachTick(rig::record);
        // the lowest the piston's base got while it pulled the ship back down
        helper.onEachTick(() -> {
            if (pulling[0] && base[0] != null) lowest[0] = Math.min(lowest[0], bottomY(rig, base[0]));
        });
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenExecuteAfter(SETTLE, () -> {
                    Vec3 at = rig.ref.toShip(Vec3.atCenterOf(pistonPos));
                    Vec3 behind = rig.ref.toShip(Vec3.atCenterOf(pistonPos.above()));
                    helper.assertTrue(at != null && behind != null, "can't find the ship's piston");
                    shipPiston[0] = BlockPos.containing(at);
                    // the bottom face of the piston's base, a fixed point of the hull
                    base[0] = Vec3.atBottomCenterOf(shipPiston[0]);
                    bottom[0] = bottomY(rig, base[0]);
                    power[0] = BlockPos.containing(behind);
                    move[0] = new Moved(rig);
                    level.setBlock(power[0], Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[0].demandDone())
                .thenExecute(() -> {
                    bottom[1] = bottomY(rig, base[0]);
                    move[1] = new Moved(rig);
                    pulling[0] = true;
                    level.setBlock(power[0], Blocks.AIR.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[1].demandDone())
                .thenExecuteAfter(AFTER, () -> {
                    bottom[2] = bottomY(rig, base[0]);
                    BlockState piston = level.getBlockState(shipPiston[0]);
                    boolean extended = piston.is(Blocks.STICKY_PISTON) && piston.getValue(BlockStateProperties.EXTENDED);
                    boolean floorWhole = level.getBlockState(pistonPos.below()).is(Blocks.STONE);
                    int pistons = rig.count(Blocks.STICKY_PISTON);
                    rig.pin();
                    String out = (raft ? "raft" : "lone piston") + ": base bottom " + fmt(bottom[0] - floor) + " over the floor, pushed up "
                            + fmt(bottom[1] - bottom[0]) + ", pulled back to " + fmt(bottom[2] - floor) + " (lowest " + fmt(lowest[0] - floor)
                            + "), piston extended " + extended + ", pistons " + pistons + "/1, floor whole " + floorWhole + ", glides "
                            + move[0].report + " / " + move[1].report + " | " + rig.summary();
                    report("an upside-down sticky piston on a ship", out);
                    helper.assertTrue(pistons == 1 && floorWhole, "something broke: " + out);
                    helper.assertTrue(Math.abs(bottom[1] - bottom[0] - 1.0) <= EXACT, "the push didn't lift the ship a block: " + out);
                    helper.assertTrue(!extended, "the sticky piston didn't retract: " + out);
                    helper.assertTrue(lowest[0] >= floor - 0.1, "the pull sank the ship into the ground: " + out);
                    helper.assertTrue(Math.abs(bottom[2] - bottom[0]) <= EXACT, "the pull didn't set the ship back down where it was: " + out);
                    helper.succeed();
                });
    }

    /**
     * A slime block under a ship's upside-down piston, resting on the ground: the piston pushes the slime into the ground
     * and the ship springs up off it (a slime launcher) - far over the block a plain push lifts it - and the slime never
     * goes into the ground (it phased through, as a piston's head did).
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aShipsPistonPushingSlimeIntoTheGroundLaunchesTheShip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 192 * 256);
        fill(level, o.offset(-4, 0, -4), o.offset(8, 0, 6), Blocks.STONE.defaultBlockState());
        BlockPos slimePos = o.offset(2, 1, 1);
        BlockPos pistonPos = o.offset(2, 2, 1);
        level.setBlock(slimePos, Blocks.SLIME_BLOCK.defaultBlockState(), 3);
        level.setBlock(pistonPos, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN), 3);
        Set<BlockPos> blocks = new java.util.HashSet<>(java.util.List.of(slimePos, pistonPos));
        Rig rig = Rig.build(helper, blocks, Blocks.SLIME_BLOCK, false, 1);
        if (rig == null) return;
        double floor = o.getY() + 1.0;
        Vec3[] slimeBottom = {null};
        double[] start = {Double.NaN};
        double[] peak = {Double.NEGATIVE_INFINITY};
        double[] lowest = {Double.POSITIVE_INFINITY};
        boolean[] watching = {false};
        Moved[] move = new Moved[1];
        helper.onEachTick(rig::record);
        helper.onEachTick(() -> {
            if (!watching[0] || slimeBottom[0] == null) return;
            double y = bottomY(rig, slimeBottom[0]);
            peak[0] = Math.max(peak[0], y);
            lowest[0] = Math.min(lowest[0], y);
        });
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenExecuteAfter(SETTLE, () -> {
                    Vec3 slime = rig.ref.toShip(Vec3.atCenterOf(slimePos));
                    Vec3 behind = rig.ref.toShip(Vec3.atCenterOf(pistonPos.above()));
                    helper.assertTrue(slime != null && behind != null, "can't find the ship's blocks");
                    // the bottom of the slime block where it is now: it moves down a block (relative to the ship) with the push
                    slimeBottom[0] = Vec3.atBottomCenterOf(BlockPos.containing(slime));
                    start[0] = bottomY(rig, slimeBottom[0]);
                    watching[0] = true;
                    move[0] = new Moved(rig);
                    level.setBlock(BlockPos.containing(behind), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[0].demandDone())
                .thenExecuteAfter(40, () -> {
                    int slime = rig.count(Blocks.SLIME_BLOCK);
                    boolean floorWhole = level.getBlockState(slimePos.below()).is(Blocks.STONE);
                    rig.pin();
                    // the slime's old bottom rose with the ship: a plain push lifts it one block, the launch far more
                    String out = "the slime's old bottom rose to " + fmt(peak[0] - start[0]) + " blocks (lowest " + fmt(lowest[0] - floor)
                            + " over the floor), slime " + slime + "/1, floor whole " + floorWhole + ", glide " + move[0].report
                            + " | " + rig.summary();
                    report("a ship's piston pushes slime into the ground", out);
                    helper.assertTrue(slime == 1 && floorWhole, "something broke: " + out);
                    helper.assertTrue(peak[0] - start[0] >= 2.5, "the ship wasn't launched off the slime: " + out);
                    helper.assertTrue(lowest[0] >= floor - 0.1, "the slime went into the ground: " + out);
                    helper.succeed();
                });
    }

    /** A world piston pushing a slime block into a raft launches the raft: it slides on well past the block a plain push moves it. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aPistonPushingSlimeIntoARaftLaunchesIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 193 * 256);
        fill(level, o.offset(-6, 0, -4), o.offset(24, 0, 6), Blocks.STONE.defaultBlockState());
        Rig rig = raft(helper, o);
        if (rig == null) return;
        double[] startX = {Double.NaN};
        double[] fastest = {0.0};
        boolean[] watching = {false};
        Moved[] move = new Moved[1];
        helper.onEachTick(rig::record);
        helper.onEachTick(() -> {
            if (!watching[0]) return;
            Vec3 v = rig.ref.velocity();
            if (v != null) fastest[0] = Math.max(fastest[0], v.x);
        });
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenExecuteAfter(SETTLE, () -> {
                    startX[0] = rig.x();
                    watching[0] = true;
                    move[0] = new Moved(rig);
                    // obsidian under the slime: slime drags the blocks around it along, and a stone floor (a row of 30) is
                    // more than a piston pushes, so the piston wouldn't extend at all
                    level.setBlock(o.offset(0, 0, 1), Blocks.OBSIDIAN.defaultBlockState(), 3);
                    level.setBlock(o.offset(0, 1, 1), Blocks.SLIME_BLOCK.defaultBlockState(), 3);
                    piston(level, o.offset(-1, 1, 1), Direction.EAST, false);
                    level.setBlock(o.offset(-2, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> move[0].demandDone())
                .thenExecuteAfter(40, () -> {
                    double moved = rig.x() - startX[0];
                    int planks = rig.count(Blocks.OAK_PLANKS);
                    rig.pin();
                    String out = "moved " + fmt(moved) + " blocks east, fastest " + fmt(fastest[0]) + " m/s, planks " + planks + "/9, glide "
                            + move[0].report + " | " + rig.summary();
                    report("a piston pushes slime into a raft", out);
                    helper.assertTrue(planks == 9, "the push broke planks: " + out);
                    helper.assertTrue(fastest[0] >= 0.6 * FullStopConfig.SERVER.slimeRestitution.get() * 10.0,
                            "the raft wasn't launched off the slime: " + out);
                    helper.assertTrue(moved >= 1.5, "the raft only went " + fmt(moved) + " blocks: " + out);
                    helper.succeed();
                });
    }

    /** The world height of a point of the ship given in ship coordinates (a fixed point of the hull). */
    private static double bottomY(Rig rig, Vec3 shipPoint) {
        Vec3 world = rig.ref.toWorld(shipPoint);
        return world == null ? Double.NaN : world.y;
    }

    /**
     * Game-test condition: a piston's move of {@code rig}'s ship is over. Its glide reported (a report other than the one
     * the ship had when this was made, just before the piston fired) and a sixth of a second of real time has passed
     * since, for the glide's last step to reach the ship's position on the server.
     */
    private static final class Moved {
        private static final long SETTLE_NANOS = 160_000_000L;
        private final Rig rig;
        @org.jetbrains.annotations.Nullable
        private final ShipPistons.GlideReport before;
        @org.jetbrains.annotations.Nullable
        ShipPistons.GlideReport report;
        private long seenAt = -1;

        Moved(Rig rig) {
            this.rig = rig;
            this.before = ShipPistons.lastGlide(rig.ref.id());
        }

        void demandDone() {
            ShipPistons.GlideReport now = ShipPistons.lastGlide(rig.ref.id());
            rig.helper.assertTrue(now != null && now != before, "waiting for ship " + rig.ref.id() + "'s glide to finish");
            if (seenAt < 0) {
                seenAt = System.nanoTime();
                report = now;
            }
            rig.helper.assertTrue(System.nanoTime() - seenAt >= SETTLE_NANOS, "waiting for ship " + rig.ref.id() + " to settle");
        }
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
