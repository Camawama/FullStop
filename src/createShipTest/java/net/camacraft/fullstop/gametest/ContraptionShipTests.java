package net.camacraft.fullstop.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionTerrainImpacts;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionTerrainImpacts.Contact;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionTerrainImpacts.Outcome;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.Rig;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fillSet;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.report;

/**
 * Create contraptions meeting real Valkyrien Skies ships ({@code contraptionShipImpacts}): a creative motor drives
 * a bearing whose iron arm sweeps a horizontal circle four blocks out, and a ship stands in the arm tip's path.
 * Needs both {@code -PshipTests -Pcreate} (src/createShipTest).
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ContraptionShipTests {
    private static final String BATCH = "fullstop_create_ships";
    private static final int ARM = 4;

    private ContraptionShipTests() {
    }

    /** A plank raft lying in the arm's path is swatted at 64 RPM: pushed off, and the plank the iron tip meets breaks. */
    @GameTest(template = ShipMaterialTests.FLAT, batch = BATCH, timeoutTicks = 1200)
    public static void aFastArmSwatsAPlankRaft(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, 12);
        BlockPos motor = bearingRig(level, o);
        BlockPos armBase = o.above(2);
        Set<BlockPos> raft = fillSet(level, armBase.offset(-1, 0, ARM), armBase.offset(1, 0, ARM + 2), Blocks.OAK_PLANKS.defaultBlockState());
        Rig rig = Rig.build(helper, raft, Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        Vec3[] start = new Vec3[1];
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenIdle(20) // resting on the floor: VS has nothing to move until the arm comes
                .thenExecute(() -> {
                    start[0] = rig.ref.position();
                    speed(level, motor, 64);
                })
                .thenWaitUntil(() -> helper.assertTrue(shipContacts(level, armBase).stream().anyMatch(c -> c.shipId() != null),
                        "the arm hasn't met the raft yet (" + shipContacts(level, armBase) + ")"))
                .thenIdle(20)
                .thenExecute(() -> {
                    List<Contact> contacts = shipContacts(level, armBase);
                    double moved = rig.ref.position().distanceTo(start[0]);
                    int planks = rig.count(Blocks.OAK_PLANKS);
                    report("iron arm swats a plank raft at 64 RPM", contacts + String.format(Locale.ROOT,
                            "; raft moved %.2f blocks, v %s; planks %d -> %d", moved, rig.ref.velocity(), rig.blocks, planks));
                    helper.assertTrue(contacts.stream().anyMatch(c -> c.shipId() != null
                            && (c.outcome() == Outcome.PUSHED_SHIP || c.outcome() == Outcome.BROKE)), "no push or break: " + contacts);
                    helper.assertTrue(moved > 0.5 || planks < rig.blocks, String.format(Locale.ROOT,
                            "the raft neither moved (%.2f blocks) nor lost a plank", moved));
                    speed(level, motor, 0);
                    helper.succeed();
                });
    }

    /** An iron ship held static in the arm's path: nothing gives and it can't be pushed, so the arm jams (overstressed). */
    @GameTest(template = ShipMaterialTests.FLAT, batch = BATCH, timeoutTicks = 1200)
    public static void aStaticIronShipJamsTheArm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, 12);
        BlockPos motor = bearingRig(level, o);
        BlockPos armBase = o.above(2);
        Set<BlockPos> hull = fillSet(level, armBase.offset(-1, 0, ARM), armBase.offset(1, 0, ARM + 1), Blocks.IRON_BLOCK.defaultBlockState());
        Rig rig = Rig.build(helper, hull, Blocks.IRON_BLOCK, true);
        if (rig == null) return;
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> speed(level, motor, 64)) // never released: VS keeps it static
                .thenWaitUntil(() -> helper.assertTrue(bearing(level, o).isOverStressed(), "the arm isn't jammed yet ("
                        + shipContacts(level, armBase) + ")"))
                .thenExecute(() -> {
                    List<Contact> contacts = shipContacts(level, armBase);
                    report("iron arm into a static iron ship", contacts + "; hull " + rig.count(Blocks.IRON_BLOCK) + " of " + rig.blocks);
                    helper.assertTrue(contacts.stream().anyMatch(c -> c.shipId() != null && c.outcome() == Outcome.JAMMED),
                            "no jam against the ship: " + contacts);
                    speed(level, motor, 0);
                    helper.succeed();
                });
    }

    /** A creative motor at {@code o}, a bearing facing up on it, an iron arm glued on top along +X and a stone floor at the bearing's height. */
    private static BlockPos bearingRig(ServerLevel level, BlockPos o) {
        for (BlockPos pos : BlockPos.betweenClosed(o.offset(-7, 1, -7), o.offset(7, 1, 7))) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(o, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), Block.UPDATE_ALL);
        level.setBlock(o.above(), AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP),
                Block.UPDATE_ALL);
        speed(level, o, 0);
        BlockPos armBase = o.above(2);
        BlockState iron = Blocks.IRON_BLOCK.defaultBlockState();
        for (int x = 0; x <= ARM; x++) level.setBlock(armBase.offset(x, 0, 0), iron, Block.UPDATE_ALL);
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(armBase).minmax(new AABB(armBase.offset(ARM, 0, 0)))));
        return o;
    }

    private static KineticBlockEntity bearing(ServerLevel level, BlockPos o) {
        if (level.getBlockEntity(o.above()) instanceof KineticBlockEntity k) return k;
        throw new IllegalStateException("no bearing");
    }

    private static List<Contact> shipContacts(ServerLevel level, BlockPos armBase) {
        for (AbstractContraptionEntity c : level.getEntitiesOfClass(AbstractContraptionEntity.class, new AABB(armBase).inflate(6.0))) {
            if (c.isAlive()) return ContraptionTerrainImpacts.recent(c.getId());
        }
        return List.of();
    }

    private static void speed(ServerLevel level, BlockPos motor, int rpm) {
        if (level.getBlockEntity(motor) instanceof CreativeMotorBlockEntity be) be.generatedSpeed.setValue(rpm);
    }
}
