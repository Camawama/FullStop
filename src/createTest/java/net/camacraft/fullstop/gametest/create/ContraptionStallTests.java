package net.camacraft.fullstop.gametest.create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionStress;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionTerrainImpacts;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionTerrainImpacts.Contact;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionTerrainImpacts.Outcome;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * A contraption that meets something it can't get through stalls there and is held ({@code contraptionImpactStress}):
 * its block's face rests against the obstacle, its network stays overstressed, and nothing grinds on, until its motor
 * stops or turns the other way or the obstacle goes. With the real Create 6.0.8: a creative motor drives a bearing
 * facing up whose glued iron arm (four blocks out) sweeps a horizontal circle into a block four blocks out from the
 * axis; and a gantry carriage with an iron ram running into bedrock. Opt-in: {@code gradlew runGameTestServer -Pcreate}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ContraptionStallTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "create_flat_48x8x48";
    private static final String BATCH = "fullstop_create_stall";
    private static final int ARM = 4;
    /** Twenty seconds held. */
    private static final int HOLD_TICKS = 400;

    private ContraptionStallTests() {
    }

    /**
     * An iron arm at 64 RPM into bedrock stalls with its tip against the bedrock and stays there for twenty seconds,
     * its network overstressed all along; stopping the motor lets it go and Create sets the arm down as blocks at the
     * last quarter turn it passed (whole: none of it rounded into the bedrock and dropped), and starting it again into
     * the bedrock stalls it again.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = HOLD_TICKS + 400)
    public static void anArmStaysStalledAgainstBedrockUntilTheMotorStops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Rig rig = rig(helper, 40);
        BlockPos target = rig.armBase().offset(0, 0, ARM);
        Held held = new Held();
        whenTicking(helper, rig.motor())
                .thenExecute(() -> level.setBlock(target, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(5)
                .thenExecute(() -> speed(level, rig.motor(), 64))
                .thenWaitUntil(() -> helper.assertTrue(ContraptionStress.jammed(bearing(level, rig)), "the arm hasn't jammed ("
                        + contacts(level, rig) + ")"))
                .thenExecute(() -> held.start(level, rig, target))
                .thenExecuteFor(HOLD_TICKS, () -> held.check(helper, level, rig, target))
                .thenExecute(() -> {
                    report("iron arm held against bedrock", held.summary());
                    helper.assertTrue(level.getBlockState(target).is(Blocks.BEDROCK), "the bedrock broke");
                    speed(level, rig.motor(), 0);
                })
                .thenWaitUntil(() -> helper.assertTrue(!ContraptionStress.jammed(bearing(level, rig)) && contraption(level, rig.armBase()) == null,
                        "stopping the motor didn't let it go and set the arm down"))
                .thenExecute(() -> {
                    String why = ContraptionStress.lastRelease(bearing(level, rig));
                    int iron = count(level, rig.armBase(), Blocks.IRON_BLOCK);
                    int dropped = drops(level, rig.armBase());
                    report("iron arm against bedrock, motor stopped", "let go: " + why + ", overstressed " + bearing(level, rig).isOverStressed()
                            + "; set down as " + iron + " iron blocks, " + dropped + " dropped, bedrock still there " + level.getBlockState(target).is(Blocks.BEDROCK));
                    helper.assertTrue("its drive stopped".equals(why), "it let go because " + why);
                    helper.assertTrue(level.getBlockState(target).is(Blocks.BEDROCK), "the arm was set down in the bedrock's place");
                    helper.assertTrue(iron == ARM + 1 && dropped == 0, "the arm was set down through the bedrock: " + iron
                            + " iron blocks placed, " + dropped + " dropped");
                    speed(level, rig.motor(), 64);
                })
                .thenWaitUntil(() -> helper.assertTrue(ContraptionStress.jammed(bearing(level, rig)), "started again into the bedrock, it doesn't jam"))
                .thenExecute(() -> {
                    double gap = tipGap(level, rig, target);
                    report("iron arm against bedrock, motor started again", "jammed again; the tip's gap to the bedrock " + fmt(gap));
                    helper.assertTrue(gap > -0.05, "started again, the tip went " + fmt(-gap) + " blocks into the bedrock");
                    helper.assertTrue(gap < 0.1, "started again, it jammed " + fmt(gap) + " blocks short of the bedrock");
                    speed(level, rig.motor(), 0);
                })
                .thenSucceed();
    }

    /** The same arm held against bedrock lets go when the bedrock is taken away, and turns on freely. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 600)
    public static void anArmLetsGoWhenTheBedrockIsRemoved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Rig rig = rig(helper, 41);
        BlockPos target = rig.armBase().offset(0, 0, ARM);
        float[] angle = new float[1];
        whenTicking(helper, rig.motor())
                .thenExecute(() -> level.setBlock(target, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(5)
                .thenExecute(() -> speed(level, rig.motor(), 64))
                .thenWaitUntil(() -> helper.assertTrue(ContraptionStress.jammed(bearing(level, rig)), "the arm hasn't jammed"))
                .thenIdle(60)
                .thenExecute(() -> {
                    helper.assertTrue(ContraptionStress.jammed(bearing(level, rig)), "it let go of the bedrock by itself ("
                            + ContraptionStress.lastRelease(bearing(level, rig)) + ")");
                    level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                })
                .thenWaitUntil(() -> helper.assertTrue(!ContraptionStress.jammed(bearing(level, rig)) && !bearing(level, rig).isOverStressed()
                        && bearing(level, rig).getSpeed() != 0.0f, "the arm doesn't turn again with the bedrock gone"))
                .thenExecute(() -> angle[0] = angle(level, rig))
                .thenIdle(10)
                .thenExecute(() -> {
                    float moved = Math.abs(angle(level, rig) - angle[0]);
                    String why = ContraptionStress.lastRelease(bearing(level, rig));
                    report("iron arm, bedrock removed", "let go: " + why + "; turned " + fmt(moved) + " degrees in 10 ticks");
                    helper.assertTrue("the obstacle is gone".equals(why), "it let go because " + why);
                    helper.assertTrue(moved > 30.0f, "the arm turned only " + fmt(moved) + " degrees in 10 ticks after letting go");
                    speed(level, rig.motor(), 0);
                })
                .thenSucceed();
    }

    /** Reversing the motor lets a held arm go, and it turns away from the bedrock. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 600)
    public static void reversingTheMotorTurnsTheArmAway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Rig rig = rig(helper, 42);
        BlockPos target = rig.armBase().offset(0, 0, ARM);
        double[] gap = new double[1];
        whenTicking(helper, rig.motor())
                .thenExecute(() -> level.setBlock(target, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(5)
                .thenExecute(() -> speed(level, rig.motor(), 16))
                .thenWaitUntil(() -> helper.assertTrue(ContraptionStress.jammed(bearing(level, rig)), "the arm hasn't jammed"))
                .thenIdle(20)
                .thenExecute(() -> {
                    gap[0] = tipGap(level, rig, target);
                    speed(level, rig.motor(), -16);
                })
                .thenWaitUntil(() -> helper.assertTrue(!ContraptionStress.jammed(bearing(level, rig)), "reversing the motor didn't let it go"))
                .thenIdle(10)
                .thenExecute(() -> {
                    double after = tipGap(level, rig, target);
                    String why = ContraptionStress.lastRelease(bearing(level, rig));
                    report("iron arm at 16 RPM against bedrock, motor reversed", "let go: " + why + "; the tip's gap " + fmt(gap[0])
                            + " -> " + fmt(after) + " blocks");
                    helper.assertTrue("its drive turned the other way".equals(why), "it let go because " + why);
                    helper.assertTrue(after > gap[0] + 0.5, "the arm didn't turn away from the bedrock (" + fmt(gap[0]) + " -> " + fmt(after) + ")");
                    speed(level, rig.motor(), 0);
                })
                .thenSucceed();
    }

    /**
     * An iron arm at 16 RPM only cracks stone: it stalls against it and stays there, the stone uncracked further and
     * still standing after twenty seconds (it used to start again after a second and a half and grind through).
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = HOLD_TICKS + 300)
    public static void anArmThatOnlyCracksStoneStallsInsteadOfGrindingThrough(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Rig rig = rig(helper, 43);
        BlockPos target = rig.armBase().offset(0, 0, ARM);
        Held held = new Held();
        int[] contactsAtStall = new int[1];
        whenTicking(helper, rig.motor())
                .thenExecute(() -> level.setBlock(target, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(5)
                .thenExecute(() -> speed(level, rig.motor(), 16))
                .thenWaitUntil(() -> helper.assertTrue(ContraptionStress.jammed(bearing(level, rig)), "the arm hasn't jammed on the stone ("
                        + contacts(level, rig) + ")"))
                .thenExecute(() -> {
                    held.start(level, rig, target);
                    contactsAtStall[0] = contacts(level, rig).size();
                })
                .thenExecuteFor(HOLD_TICKS, () -> held.check(helper, level, rig, target))
                .thenExecute(() -> {
                    List<Contact> contacts = contacts(level, rig);
                    report("iron arm at 16 RPM held against stone", held.summary() + "; contacts " + contacts);
                    helper.assertTrue(level.getBlockState(target).is(Blocks.STONE), "the stone broke while the arm was held");
                    helper.assertTrue(contacts.size() == contactsAtStall[0], "the held arm went on hitting the stone: " + contacts);
                    helper.assertTrue(contacts.stream().anyMatch(c -> c.outcome() == Outcome.JAMMED), "no jam recorded: " + contacts);
                    speed(level, rig.motor(), 0);
                })
                .thenSucceed();
    }

    /**
     * A gantry carriage with an iron ram at full speed into bedrock stops against it. Create takes a gantry apart as
     * soon as its shaft stops turning (the jam's overstress does that), so it is set down as blocks right in front of
     * the bedrock: the ram next to it, the bedrock whole, nothing dropped, the jam let go with the contraption gone,
     * and it doesn't start up into the bedrock again.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 600)
    public static void aGantryStopsAgainstBedrockAndIsSetDownThere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 44);
        fill(level, o.offset(-9, 1, -3), o.offset(9, 6, 3), Blocks.AIR.defaultBlockState());
        fill(level, o.offset(-9, 1, -3), o.offset(9, 1, 3), Blocks.STONE.defaultBlockState());
        int top = o.getY() + 4;
        BlockPos[] shaft = new BlockPos[13];
        for (int x = -6; x <= 6; x++) {
            GantryShaftBlock.Part part = x == -6 ? GantryShaftBlock.Part.START : x == 6 ? GantryShaftBlock.Part.END : GantryShaftBlock.Part.MIDDLE;
            shaft[x + 6] = new BlockPos(o.getX() + x, top, o.getZ());
            level.setBlock(shaft[x + 6], AllBlocks.GANTRY_SHAFT.getDefaultState()
                    .setValue(BlockStateProperties.FACING, Direction.EAST).setValue(GantryShaftBlock.PART, part)
                    .setValue(GantryShaftBlock.POWERED, false), Block.UPDATE_ALL);
        }
        BlockPos motor = new BlockPos(o.getX() - 7, top, o.getZ());
        level.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST),
                Block.UPDATE_ALL);
        speed(level, motor, 0);
        BlockPos carriage = new BlockPos(o.getX(), top - 1, o.getZ());
        level.setBlock(carriage, AllBlocks.GANTRY_CARRIAGE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false), Block.UPDATE_ALL);
        level.setBlock(carriage.below(), Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        glue(level, carriage.below(), carriage);
        BlockPos east = new BlockPos(o.getX() + 3, o.getY() + 2, o.getZ());
        BlockPos west = new BlockPos(o.getX() - 3, o.getY() + 2, o.getZ());
        KineticBlockEntity[] drive = new KineticBlockEntity[1];
        int[] running = {0};
        int[] overstressed = {0};
        whenTicking(helper, o)
                .thenExecute(() -> {
                    level.setBlock(east, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(west, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                })
                .thenIdle(5)
                .thenExecute(() -> speed(level, motor, 256))
                .thenWaitUntil(() -> {
                    // the jam lasts only until the gantry takes itself apart (a tick or two): look for its release too
                    drive[0] = null;
                    for (BlockPos s : shaft) {
                        if (level.getBlockEntity(s) instanceof KineticBlockEntity k
                                && (ContraptionStress.jammed(k) || ContraptionStress.lastRelease(k) != null)) drive[0] = k;
                    }
                    helper.assertTrue(drive[0] != null, "the gantry hasn't jammed on the bedrock (" + near(level, carriage) + ")");
                })
                .thenWaitUntil(() -> helper.assertTrue(gantry(level, carriage) == null && !ContraptionStress.jammed(drive[0]),
                        "the jammed gantry is still running (" + near(level, carriage) + ")"))
                .thenExecuteFor(100, () -> {
                    if (gantry(level, carriage) != null) running[0]++;
                    if (drive[0].isOverStressed()) overstressed[0]++;
                })
                .thenExecute(() -> {
                    BlockPos ram = null;
                    for (int x = west.getX() + 1; x < east.getX(); x++) {
                        BlockPos pos = new BlockPos(x, east.getY(), east.getZ());
                        if (level.getBlockState(pos).is(Blocks.IRON_BLOCK)) ram = pos;
                    }
                    String why = ContraptionStress.lastRelease(drive[0]);
                    int dropped = level.getEntitiesOfClass(ItemEntity.class, new AABB(o).inflate(12.0)).size();
                    report("gantry ram into bedrock", "let go: " + why + "; set down with its ram at " + (ram == null ? "nowhere" : ram.toShortString())
                            + " (bedrock at x " + west.getX() + " and " + east.getX() + "), " + dropped + " dropped; over the next 100 ticks running "
                            + running[0] + " ticks, overstressed " + overstressed[0]);
                    helper.assertTrue("the contraption is gone".equals(why), "it let go because " + why);
                    helper.assertTrue(level.getBlockState(east).is(Blocks.BEDROCK) && level.getBlockState(west).is(Blocks.BEDROCK), "the bedrock broke");
                    helper.assertTrue(ram != null && (ram.getX() == east.getX() - 1 || ram.getX() == west.getX() + 1),
                            "the ram wasn't set down against the bedrock (" + (ram == null ? "no ram" : ram.toShortString()) + ")");
                    helper.assertTrue(dropped == 0, dropped + " of the gantry's blocks dropped");
                    helper.assertTrue(running[0] == 0 && overstressed[0] == 0, "the stopped gantry started up into the bedrock again (running "
                            + running[0] + " ticks, overstressed " + overstressed[0] + ")");
                    speed(level, motor, 0);
                })
                .thenSucceed();
    }

    // ---- what a held arm must do every tick --------------------------------------------------------------------

    /** Watches a held arm: its angle, its network, its tip against the obstacle. */
    private static final class Held {
        float angle;
        double maxTurn;
        int notOverstressed;
        int notJammed;
        double minGap = Double.POSITIVE_INFINITY;
        double maxGap = Double.NEGATIVE_INFINITY;
        int ticks;

        void start(ServerLevel level, Rig rig, BlockPos target) {
            angle = angle(level, rig);
        }

        void check(GameTestHelper helper, ServerLevel level, Rig rig, BlockPos target) {
            ticks++;
            KineticBlockEntity bearing = bearing(level, rig);
            maxTurn = Math.max(maxTurn, Math.abs(angleDiff(angle(level, rig), angle)));
            if (!bearing.isOverStressed()) notOverstressed++;
            if (!ContraptionStress.jammed(bearing)) notJammed++;
            double gap = tipGap(level, rig, target);
            minGap = Math.min(minGap, gap);
            maxGap = Math.max(maxGap, gap);
            helper.assertTrue(notJammed == 0, "the held arm let go after " + ticks + " ticks (" + ContraptionStress.lastRelease(bearing) + ")");
            helper.assertTrue(maxTurn < 0.01, "the held arm turned " + fmt(maxTurn) + " degrees");
            helper.assertTrue(notOverstressed <= 1, "the held arm's network wasn't overstressed for " + notOverstressed + " ticks");
            helper.assertTrue(gap > -0.05, "the held arm's tip is " + fmt(-gap) + " blocks into the obstacle");
        }

        String summary() {
            return String.format(Locale.ROOT, "%d ticks: turned %.4f degrees, not overstressed %d ticks, the tip's gap to the obstacle %.3f..%.3f blocks",
                    ticks, maxTurn, notOverstressed, minGap, maxGap);
        }
    }

    /**
     * How far the arm's nearest block (each taken as an axis-aligned cube, as the hit test does) is from overlapping
     * the obstacle cell: positive, clear by that much; negative, into it by that much. Every block of the arm counts:
     * an arm set down and started again may point the other way in its own frame.
     */
    private static double tipGap(ServerLevel level, Rig rig, BlockPos cell) {
        AbstractContraptionEntity c = contraption(level, rig.armBase());
        if (c == null || c.getContraption() == null) return Double.NaN;
        double nearest = Double.POSITIVE_INFINITY;
        for (BlockPos local : c.getContraption().getBlocks().keySet()) {
            Vec3 at = c.toGlobalVector(Vec3.atCenterOf(local), 1.0f);
            double dx = Math.abs(at.x - (cell.getX() + 0.5)) - 1.0;
            double dy = Math.abs(at.y - (cell.getY() + 0.5)) - 1.0;
            double dz = Math.abs(at.z - (cell.getZ() + 0.5)) - 1.0;
            nearest = Math.min(nearest, Math.max(dx, Math.max(dy, dz)));
        }
        return nearest;
    }

    private static float angle(ServerLevel level, Rig rig) {
        AbstractContraptionEntity c = contraption(level, rig.armBase());
        return c instanceof ControlledContraptionEntity controlled ? controlled.getAngle(1.0f) : Float.NaN;
    }

    private static double angleDiff(float a, float b) {
        double d = ((a - b) % 360.0 + 540.0) % 360.0 - 180.0;
        return d;
    }

    // ---- the rigs ---------------------------------------------------------------------------------------------

    /** A creative motor driving a bearing facing up; its glued iron arm from {@code armBase} out along +X. */
    record Rig(BlockPos motor, BlockPos bearing, BlockPos armBase) {}

    private static Rig rig(GameTestHelper helper, int laneIndex) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, laneIndex);
        fill(level, o.offset(-7, 0, -7), o.offset(7, 4, 7), Blocks.AIR.defaultBlockState());
        fill(level, o.offset(-7, 1, -7), o.offset(7, 1, 7), Blocks.STONE.defaultBlockState());
        level.setBlock(o, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), Block.UPDATE_ALL);
        BlockPos bearing = o.above();
        level.setBlock(bearing, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP),
                Block.UPDATE_ALL);
        speed(level, o, 0);
        BlockPos armBase = bearing.above();
        BlockState iron = Blocks.IRON_BLOCK.defaultBlockState();
        fill(level, armBase, armBase.offset(ARM, 0, 0), iron);
        glue(level, armBase, armBase.offset(ARM, 0, 0));
        return new Rig(o, bearing, armBase);
    }

    private static KineticBlockEntity bearing(ServerLevel level, Rig rig) {
        if (level.getBlockEntity(rig.bearing()) instanceof KineticBlockEntity k) return k;
        throw new IllegalStateException("no bearing at " + rig.bearing());
    }

    private static List<Contact> contacts(ServerLevel level, Rig rig) {
        AbstractContraptionEntity c = contraption(level, rig.armBase());
        return c == null ? List.of() : ContraptionTerrainImpacts.recent(c.getId());
    }

    private static AbstractContraptionEntity contraption(ServerLevel level, BlockPos near) {
        for (AbstractContraptionEntity c : level.getEntitiesOfClass(AbstractContraptionEntity.class, new AABB(near).inflate(8.0))) {
            if (c.isAlive()) return c;
        }
        return null;
    }

    /** How many {@code block}s are in the arm's layer round {@code armBase}. */
    private static int count(ServerLevel level, BlockPos armBase, Block block) {
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(armBase.offset(-ARM - 1, 0, -ARM - 1), armBase.offset(ARM + 1, 0, ARM + 1))) {
            if (level.getBlockState(pos).is(block)) n++;
        }
        return n;
    }

    /** Dropped items near {@code at} (a contraption's block Create couldn't place). */
    private static int drops(ServerLevel level, BlockPos at) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(8.0)).size();
    }

    private static AbstractContraptionEntity gantry(ServerLevel level, BlockPos near) {
        return contraption(level, near);
    }

    private static String near(ServerLevel level, BlockPos at) {
        AbstractContraptionEntity c = contraption(level, at);
        return c == null ? "no contraption" : "gantry at " + c.position() + ", contacts " + ContraptionTerrainImpacts.recent(c.getId());
    }

    private static void glue(ServerLevel level, BlockPos from, BlockPos to) {
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(from).minmax(new AABB(to))));
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

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }
}
