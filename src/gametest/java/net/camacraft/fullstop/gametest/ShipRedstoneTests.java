package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.Rig;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fillSet;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fmt;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.report;

/**
 * Ships working redstone ({@code ShipRedstoneContacts}): a hull pressing a button and holding
 * it, a hull resting on a pressure plate, a heavy hull on a weighted plate. Runs with the ship
 * tests ({@code gradlew runGameTestServer -PshipTests}).
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipRedstoneTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_ship_redstone";
    private static final int TIMEOUT = 1200;

    private ShipRedstoneTests() {
    }

    /** A lane of its own for each test, 256 blocks apart and past the spin tests' lanes, 40 blocks around it force-loaded. */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (60 + lane) * 256);
        ServerLevel level = helper.getLevel();
        for (int cx = (o.getX() - 40) >> 4; cx <= (o.getX() + 40) >> 4; cx++) {
            for (int cz = (o.getZ() - 40) >> 4; cz <= (o.getZ() + 40) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    /**
     * A stone button on the west face of a stone wall (the block it is attached to a redstone lamp,
     * which a pressed button powers), and a floating 2x3x3 plank raft driven east at 2 m/s into the
     * wall: the button is pressed as the hull reaches it (the lamp lights), stays pressed while the
     * hull leans on it (past the 20 ticks a stone button holds for), and is let go once the raft is
     * driven away (the lamp going out four ticks later, as lamps do).
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aRaftPushedIntoAStoneButtonPressesItAndLetsGoWhenItLeaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 0);
        ShipCollisionTests.fill(level, o.offset(0, 1, -2), o.offset(0, 5, 2), Blocks.STONE.defaultBlockState());
        BlockPos buttonPos = o.offset(-1, 2, 0);
        BlockState button = Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.WALL)
                .setValue(ButtonBlock.FACING, Direction.WEST);
        level.setBlock(buttonPos, button, Block.UPDATE_ALL);
        BlockPos lampPos = o.offset(0, 2, 0);
        level.setBlock(lampPos, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL);
        Rig raft = Rig.build(helper, fillSet(level, o.offset(-9, 1, -1), o.offset(-8, 3, 1), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (raft == null) return;
        helper.onEachTick(raft::record);
        int[] phase = {0};
        long[] pressedAt = {-1};
        helper.onEachTick(() -> {
            if (phase[0] == 1) raft.cruise(new Vec3(2.0, 0.0, 0.0), 40.0);
            if (phase[0] == 2) raft.cruise(new Vec3(-2.0, 0.0, 0.0), 40.0);
        });
        helper.startSequence()
                .thenWaitUntil(raft::demandReady)
                .thenExecute(raft::release)
                .thenWaitUntil(raft::demandSimulated)
                .thenExecute(() -> {
                    raft.push(new Vec3(2.0, 0.0, 0.0));
                    phase[0] = 1;
                })
                .thenWaitUntil(() -> {
                    boolean powered = level.getBlockState(buttonPos).getValue(ButtonBlock.POWERED);
                    if (powered && pressedAt[0] < 0) pressedAt[0] = level.getGameTime();
                    helper.assertTrue(powered, "waiting for the raft (at x " + fmt(raft.x() - o.getX()) + ") to press the button");
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getGameTime() - pressedAt[0] >= 45, "leaning on the button"))
                .thenExecute(() -> {
                    boolean powered = level.getBlockState(buttonPos).getValue(ButtonBlock.POWERED);
                    boolean lit = level.getBlockState(lampPos).getValue(RedstoneLampBlock.LIT);
                    report("raft into a stone button", "pressed after " + (pressedAt[0]) + ", still pressed 45 ticks on: " + powered
                            + ", lamp lit: " + lit + " | " + raft.summary());
                    helper.assertTrue(powered, "the button was let go while the hull still leaned on it");
                    helper.assertTrue(lit, "the lamp next to the pressed button isn't lit");
                    raft.push(new Vec3(-2.0, 0.0, 0.0));
                    phase[0] = 2;
                })
                .thenWaitUntil(() -> helper.assertFalse(level.getBlockState(buttonPos).getValue(ButtonBlock.POWERED),
                        "waiting for the button to be let go (raft at x " + fmt(raft.x() - o.getX()) + ")"))
                .thenWaitUntil(() -> helper.assertFalse(level.getBlockState(lampPos).getValue(RedstoneLampBlock.LIT),
                        "waiting for the lamp to go out (it does four ticks after the button is let go)"))
                .thenExecute(() -> {
                    raft.pin();
                    helper.succeed();
                });
    }

    /**
     * A stone pressure plate on a redstone lamp, and a 3x1x3 plank raft dropped from four blocks
     * up onto it: the plate is pressed as the hull comes to rest on it (the lamp under it lights),
     * stays pressed while the hull rests there, and is let go once the raft is lifted away (the
     * lamp going out four ticks later).
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aRaftSetDownOnAStonePlateHoldsItDownWhileItRests(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 1);
        BlockPos lampPos = o;
        BlockPos platePos = o.offset(0, 1, 0);
        level.setBlock(lampPos, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(platePos, Blocks.STONE_PRESSURE_PLATE.defaultBlockState(), Block.UPDATE_ALL);
        Rig raft = Rig.build(helper, fillSet(level, o.offset(-1, 5, -1), o.offset(1, 5, 1), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (raft == null) return;
        helper.onEachTick(raft::record);
        long[] pressedAt = {-1};
        helper.startSequence()
                .thenWaitUntil(raft::demandReady)
                .thenExecute(raft::release)
                .thenWaitUntil(raft::demandSimulated)
                .thenWaitUntil(() -> {
                    boolean powered = level.getBlockState(platePos).getValue(PressurePlateBlock.POWERED);
                    if (powered && pressedAt[0] < 0) pressedAt[0] = level.getGameTime();
                    helper.assertTrue(powered, "waiting for the raft (at y " + fmt(raft.y() - o.getY()) + ") to press the plate");
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getGameTime() - pressedAt[0] >= 45, "resting on the plate"))
                .thenExecute(() -> {
                    boolean powered = level.getBlockState(platePos).getValue(PressurePlateBlock.POWERED);
                    boolean lit = level.getBlockState(lampPos).getValue(RedstoneLampBlock.LIT);
                    report("raft on a stone plate", "pressed at " + pressedAt[0] + ", still pressed 45 ticks on: " + powered + ", lamp lit: "
                            + lit + ", raft at y " + fmt(raft.y() - o.getY()) + " | " + raft.summary());
                    helper.assertTrue(powered, "the plate was let go while the hull still rested on it");
                    helper.assertTrue(lit, "the lamp under the pressed plate isn't lit");
                    raft.push(new Vec3(0.0, 4.0, 0.0));
                })
                .thenWaitUntil(() -> helper.assertFalse(level.getBlockState(platePos).getValue(PressurePlateBlock.POWERED),
                        "waiting for the plate to be let go (raft at y " + fmt(raft.y() - o.getY()) + ")"))
                .thenWaitUntil(() -> helper.assertFalse(level.getBlockState(lampPos).getValue(RedstoneLampBlock.LIT),
                        "waiting for the lamp to go out (it does four ticks after the plate is let go)"))
                .thenExecute(() -> {
                    raft.pin();
                    helper.succeed();
                });
    }

    /**
     * Two gold (light weighted) plates, a 2x1x2 plank raft dropped onto one and a 3x3x3 obsidian
     * ship (135 t) onto the other: the obsidian (135 t) gives the full 15, the planks (2 t: VS's plank
     * weighs 500 kg) less, one level per tonne, and more than nothing.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aHeavyShipOnAGoldPlateGivesMoreSignalThanALightOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 2);
        BlockPos lightPlate = o.offset(-8, 1, 0);
        BlockPos heavyPlate = o.offset(8, 1, 0);
        level.setBlock(lightPlate, Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(heavyPlate, Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE.defaultBlockState(), Block.UPDATE_ALL);
        Rig light = Rig.build(helper, fillSet(level, o.offset(-8, 5, 0), o.offset(-7, 5, 1), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (light == null) return;
        Rig heavy = Rig.build(helper, fillSet(level, o.offset(7, 5, -1), o.offset(9, 7, 1), Blocks.OBSIDIAN.defaultBlockState()),
                Blocks.OBSIDIAN, true);
        if (heavy == null) return;
        helper.onEachTick(light::record);
        helper.onEachTick(heavy::record);
        long[] since = {-1};
        helper.startSequence()
                .thenWaitUntil(light::demandReady)
                .thenWaitUntil(heavy::demandReady)
                .thenExecute(() -> {
                    light.release();
                    heavy.release();
                })
                .thenWaitUntil(light::demandSimulated)
                .thenWaitUntil(() -> {
                    int a = level.getBlockState(lightPlate).getValue(WeightedPressurePlateBlock.POWER);
                    int b = level.getBlockState(heavyPlate).getValue(WeightedPressurePlateBlock.POWER);
                    if (a > 0 && b > 0 && since[0] < 0) since[0] = level.getGameTime();
                    helper.assertTrue(a > 0 && b > 0, "waiting for both plates to be pressed (light " + a + ", heavy " + b + ")");
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getGameTime() - since[0] >= 30, "resting"))
                .thenExecute(() -> {
                    int a = level.getBlockState(lightPlate).getValue(WeightedPressurePlateBlock.POWER);
                    int b = level.getBlockState(heavyPlate).getValue(WeightedPressurePlateBlock.POWER);
                    String out = String.format(Locale.ROOT, "plank raft (%d kg) gives %d, obsidian ship (%d kg) gives %d", Math.round(light.ref.mass()),
                            a, Math.round(heavy.ref.mass()), b);
                    light.pin();
                    heavy.pin();
                    report("ships on gold plates", out);
                    helper.assertTrue(b == 15, "the obsidian ship doesn't give the full signal: " + out);
                    helper.assertTrue(a >= 1 && a < b, "the plank raft's signal isn't between 1 and the obsidian ship's: " + out);
                    helper.succeed();
                });
    }
}
