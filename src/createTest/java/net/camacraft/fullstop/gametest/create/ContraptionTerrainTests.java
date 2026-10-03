package net.camacraft.fullstop.gametest.create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlock;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity;
import com.simibubi.create.content.contraptions.mounted.CartAssembleRailType;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Create contraptions meeting the terrain, both sides giving ({@code contraptionTerrainImpacts},
 * {@code contraptionBlocksBreak}, {@code contraptionImpactStress}), with the real Create 6.0.8: a creative motor
 * drives a mechanical bearing facing up whose glued arm (four blocks out from its anchor) sweeps a horizontal
 * circle; a block stands in the arm tip's path four blocks from the axis. Also a gantry ramming dirt and a
 * minecart contraption running into bedrock. Opt-in: {@code gradlew runGameTestServer -Pcreate}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ContraptionTerrainTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "create_flat_48x8x48";
    private static final String BATCH = "fullstop_create_terrain";
    private static final int TIMEOUT = 600;
    /** The arm reaches this many blocks out from the bearing's axis block. */
    private static final int ARM = 4;

    private ContraptionTerrainTests() {
    }

    /** An iron arm at 64 RPM (Create moves no obsidian unless its movableObsidian setting allows it) (its tip at about 27 m/s) breaks the stone in its way; the network feels it; the arm turns on. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void anIronArmAt64RpmBreaksStone(GameTestHelper helper) {
        Rig rig = rig(helper, 30, Blocks.IRON_BLOCK.defaultBlockState());
        BlockPos target = rig.armBase().offset(0, 0, ARM);
        ServerLevel level = helper.getLevel();
        whenTicking(helper, rig.motor())
                .thenExecute(() -> level.setBlock(target, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(5)
                .thenExecute(() -> speed(level, rig.motor(), 64))
                .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(target).isAir(), "the stone still stands ("
                        + contacts(level, rig) + ")"))
                .thenIdle(20)
                .thenExecute(() -> {
                    KineticBlockEntity bearing = bearing(level, rig);
                    double peak = ContraptionStress.takePeak(bearing);
                    List<Contact> contacts = contacts(level, rig);
                    report("iron arm into stone at 64 RPM", contacts + "; peak load " + fmt(peak) + " SU; overstressed "
                            + bearing.isOverStressed() + ", speed " + bearing.getSpeed() + "; stats " + ContraptionTerrainImpacts.stats(level));
                    helper.assertTrue(contacts.stream().anyMatch(c -> c.outcome() == Outcome.BROKE), "no contact broke the stone: " + contacts);
                    helper.assertTrue(peak > 0.0, "the bearing's network never felt the hit");
                    // its death messages say what it is: a bearing's arm is a spinning contraption
                    String kind = net.camacraft.fullstop.common.compat.create.contraption.ContraptionKinds.of(level, contraption(level, rig.armBase()));
                    helper.assertTrue("spinning".equals(kind), "a bearing's arm reads as " + kind);
                    helper.assertTrue(!bearing.isOverStressed() && bearing.getSpeed() != 0.0f, "the arm stopped: overstressed "
                            + bearing.isOverStressed() + ", speed " + bearing.getSpeed());
                    speed(level, rig.motor(), 0);
                })
                .thenSucceed();
    }

    /** The same arm into bedrock: nothing gives, the network is overstressed and stops; once the bedrock is gone it turns again. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void anIronArmJamsOnBedrockAndRecovers(GameTestHelper helper) {
        Rig rig = rig(helper, 31, Blocks.IRON_BLOCK.defaultBlockState());
        BlockPos target = rig.armBase().offset(0, 0, ARM);
        ServerLevel level = helper.getLevel();
        long[] jammedAt = {0};
        whenTicking(helper, rig.motor())
                .thenExecute(() -> level.setBlock(target, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(5)
                .thenExecute(() -> speed(level, rig.motor(), 64))
                .thenWaitUntil(() -> helper.assertTrue(bearing(level, rig).isOverStressed(), "the network isn't overstressed yet ("
                        + contacts(level, rig) + ")"))
                .thenExecute(() -> {
                    jammedAt[0] = level.getGameTime();
                    List<Contact> contacts = contacts(level, rig);
                    report("iron arm into bedrock", contacts + "; jammed " + ContraptionStress.jammed(bearing(level, rig))
                            + ", load " + fmt(ContraptionStress.loadSu(bearing(level, rig))) + " SU");
                    helper.assertTrue(contacts.stream().anyMatch(c -> c.outcome() == Outcome.JAMMED), "no jam recorded: " + contacts);
                    helper.assertTrue(level.getBlockState(target).is(Blocks.BEDROCK), "the bedrock broke");
                    helper.assertTrue(bearing(level, rig).getSpeed() == 0.0f, "the overstressed bearing still turns");
                })
                .thenIdle(10)
                .thenExecute(() -> level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL))
                .thenWaitUntil(() -> helper.assertTrue(!bearing(level, rig).isOverStressed() && bearing(level, rig).getSpeed() != 0.0f,
                        "the network hasn't recovered (load " + fmt(ContraptionStress.loadSu(bearing(level, rig))) + " SU)"))
                .thenExecute(() -> {
                    report("iron arm into bedrock", "recovered " + (level.getGameTime() - jammedAt[0]) + " ticks after the jam");
                    speed(level, rig.motor(), 0);
                })
                .thenSucceed();
    }

    /** A wool arm at 64 RPM into stone: the wool tip is the weaker, knocked off and dropped; the stone stands, the rest turns on. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aWoolArmLosesItsTipToStone(GameTestHelper helper) {
        Rig rig = rig(helper, 32, Blocks.WHITE_WOOL.defaultBlockState());
        BlockPos target = rig.armBase().offset(0, 0, ARM);
        ServerLevel level = helper.getLevel();
        AbstractContraptionEntity[] arm = new AbstractContraptionEntity[1];
        int[] before = {0};
        whenTicking(helper, rig.motor())
                .thenExecute(() -> level.setBlock(target, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(5)
                .thenExecute(() -> speed(level, rig.motor(), 64))
                .thenWaitUntil(() -> {
                    arm[0] = contraption(level, rig.armBase());
                    helper.assertTrue(arm[0] != null && arm[0].getContraption() != null, "the arm hasn't assembled");
                    before[0] = Math.max(before[0], arm[0].getContraption().getBlocks().size());
                })
                .thenWaitUntil(() -> helper.assertTrue(!arm[0].getContraption().getBlocks().containsKey(new BlockPos(ARM, 0, 0)),
                        "the wool tip is still on the arm (" + contacts(level, rig) + ")"))
                .thenIdle(20)
                .thenExecute(() -> {
                    KineticBlockEntity bearing = bearing(level, rig);
                    List<Contact> contacts = contacts(level, rig);
                    List<ItemEntity> wool = level.getEntitiesOfClass(ItemEntity.class, new AABB(rig.armBase()).inflate(12),
                            i -> i.getItem().is(Items.WHITE_WOOL));
                    report("wool arm into stone at 64 RPM", contacts + "; blocks " + before[0] + " -> "
                            + arm[0].getContraption().getBlocks().size() + "; wool dropped " + wool.size() + "; overstressed "
                            + bearing.isOverStressed());
                    helper.assertTrue(level.getBlockState(target).is(Blocks.STONE), "the stone broke instead");
                    helper.assertTrue(contacts.stream().anyMatch(c -> c.outcome() == Outcome.KNOCKED_OFF), "no knock-off recorded: " + contacts);
                    helper.assertTrue(arm[0].getContraption().getBlocks().size() == before[0] - 1,
                            "the arm lost " + (before[0] - arm[0].getContraption().getBlocks().size()) + " blocks, not one");
                    helper.assertTrue(!wool.isEmpty(), "the knocked-off wool didn't drop");
                    helper.assertTrue(arm[0].isAlive() && !bearing.isOverStressed() && bearing.getSpeed() != 0.0f, "the arm stopped");
                    speed(level, rig.motor(), 0);
                })
                .thenSucceed();
    }

    /** A slow arm (8 RPM: its tip about 3.4 m/s, under contraptionTerrainMinSpeed) passes through the stone as in Create. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aSlowArmPassesThroughStone(GameTestHelper helper) {
        Rig rig = rig(helper, 33, Blocks.IRON_BLOCK.defaultBlockState());
        BlockPos target = rig.armBase().offset(0, 0, ARM);
        ServerLevel level = helper.getLevel();
        whenTicking(helper, rig.motor())
                .thenExecute(() -> level.setBlock(target, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(5)
                .thenExecute(() -> speed(level, rig.motor(), 8))
                .thenIdle(200) // 8 RPM: a turn in 150 ticks
                .thenExecute(() -> {
                    KineticBlockEntity bearing = bearing(level, rig);
                    List<Contact> contacts = contacts(level, rig);
                    report("iron arm into stone at 8 RPM", contacts + "; overstressed " + bearing.isOverStressed());
                    helper.assertTrue(level.getBlockState(target).is(Blocks.STONE), "the stone broke");
                    helper.assertTrue(contacts.isEmpty(), "a slow arm made contacts: " + contacts);
                    helper.assertTrue(!bearing.isOverStressed(), "the slow arm's network is overstressed");
                    speed(level, rig.motor(), 0);
                })
                .thenSucceed();
    }

    /**
     * A gantry carriage at full speed (0.49 blocks a tick) with an iron block glued under it as a ram (the coverage
     * suite's gantry, the plank swapped for iron) ploughs through the dirt on its line (whichever way it runs).
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aGantryRamPloughsThroughDirt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 34);
        fill(level, o.offset(-9, 1, -3), o.offset(9, 6, 3), Blocks.AIR.defaultBlockState());
        fill(level, o.offset(-9, 1, -3), o.offset(9, 1, 3), Blocks.STONE.defaultBlockState());
        int top = o.getY() + 4;
        for (int x = -6; x <= 6; x++) {
            GantryShaftBlock.Part part = x == -6 ? GantryShaftBlock.Part.START : x == 6 ? GantryShaftBlock.Part.END : GantryShaftBlock.Part.MIDDLE;
            level.setBlock(new BlockPos(o.getX() + x, top, o.getZ()), AllBlocks.GANTRY_SHAFT.getDefaultState()
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
        // an iron ram under the carriage, at o.y + 2
        level.setBlock(carriage.below(), Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        glue(level, carriage.below(), carriage);
        BlockPos east = new BlockPos(o.getX() + 3, o.getY() + 2, o.getZ());
        BlockPos west = new BlockPos(o.getX() - 3, o.getY() + 2, o.getZ());
        whenTicking(helper, o)
                .thenExecute(() -> {
                    level.setBlock(east, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(west, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                })
                .thenIdle(5)
                .thenExecute(() -> speed(level, motor, 256))
                .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(east).isAir() || level.getBlockState(west).isAir(),
                        "the dirt still stands (" + describe(level, carriage, 16) + ")"))
                .thenExecute(() -> {
                    report("gantry ram into dirt", recentNear(level, carriage) + "; stats " + ContraptionTerrainImpacts.stats(level));
                    speed(level, motor, 0);
                })
                .thenSucceed();
    }

    /**
     * A minecart contraption running at 8 m/s (a rail's top speed) into bedrock across its track stops dead; the
     * bedrock stands. (A furnace cart tops out at 4 m/s, under contraptionTerrainMinSpeed: it passes through, as in
     * Create.)
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aCartContraptionStopsAtBedrock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 35);
        fill(level, o.offset(-9, 1, -3), o.offset(9, 7, 3), Blocks.AIR.defaultBlockState());
        fill(level, o.offset(-9, 1, -3), o.offset(9, 1, 3), Blocks.STONE.defaultBlockState());
        int railY = o.getY() + 2;
        for (int x = -8; x <= 8; x++) {
            level.setBlock(new BlockPos(o.getX() + x, railY, o.getZ()),
                    Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST), Block.UPDATE_ALL);
        }
        BlockPos assembler = new BlockPos(o.getX() - 7, railY, o.getZ());
        level.setBlock(assembler, AllBlocks.CART_ASSEMBLER.getDefaultState()
                .setValue(CartAssemblerBlock.RAIL_SHAPE, RailShape.EAST_WEST)
                .setValue(CartAssemblerBlock.RAIL_TYPE, CartAssembleRailType.REGULAR)
                .setValue(CartAssemblerBlock.POWERED, true), Block.UPDATE_ALL);
        level.setBlock(assembler.below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(assembler.above(), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(assembler.above(2), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        glue(level, assembler.above(), assembler.above(2));
        // bedrock across the track at the load's height, eight blocks on (the rail itself stays open)
        BlockPos wall = new BlockPos(assembler.getX() + 8, railY + 1, assembler.getZ());
        level.setBlock(wall, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wall.above(), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        AbstractMinecart[] cart = new AbstractMinecart[1];
        double[] before = {0.0};
        long[] t0 = {0};
        whenTicking(helper, o)
                .thenExecute(() -> {
                    AbstractMinecart c = EntityType.MINECART.create(level);
                    if (c == null) throw new IllegalStateException("no minecart");
                    c.moveTo(assembler.getX() + 0.5, railY + 0.0625, assembler.getZ() + 0.5, 0.0f, 0.0f);
                    level.addFreshEntity(c);
                    cart[0] = c;
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    if (level.getBlockEntity(assembler) instanceof CartAssemblerBlockEntity be) be.assembleNextTick(cart[0]);
                })
                .thenWaitUntil(() -> helper.assertTrue(!cart[0].getPassengers().isEmpty(), "the cart hasn't been assembled onto"))
                .thenIdle(5)
                .thenExecute(() -> t0[0] = level.getGameTime())
                .thenWaitUntil(() -> {
                    List<Contact> contacts = cartContacts(cart[0]);
                    if (contacts.isEmpty() && level.getGameTime() - t0[0] < 40) {
                        // driven east at a rail's top speed until it meets the wall
                        cart[0].setDeltaMovement(0.4, cart[0].getDeltaMovement().y, 0.0);
                        before[0] = 0.4 * 20.0;
                    }
                    helper.assertTrue(!contacts.isEmpty(), "the cart's load hasn't met the bedrock (cart at " + cart[0].position()
                            + ", passengers " + cart[0].getPassengers() + "; " + describe(level, cart[0].blockPosition(), 16) + ")");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    List<Contact> contacts = cartContacts(cart[0]);
                    double after = cart[0].getDeltaMovement().horizontalDistance() * 20.0;
                    report("minecart into bedrock", contacts + String.format(Locale.ROOT, "; %.2f m/s before, %.2f m/s after", before[0], after));
                    helper.assertTrue(contacts.stream().anyMatch(c -> c.outcome() == Outcome.JAMMED), "no jam: " + contacts);
                    helper.assertTrue(after < 1.0, String.format(Locale.ROOT, "the cart runs on at %.2f m/s", after));
                    helper.assertTrue(level.getBlockState(wall).is(Blocks.BEDROCK), "the bedrock broke");
                    AbstractContraptionEntity load = contraption(level, cart[0].blockPosition());
                    String kind = load == null ? null : net.camacraft.fullstop.common.compat.create.contraption.ContraptionKinds.of(level, load);
                    helper.assertTrue("minecart".equals(kind), "a minecart contraption reads as " + kind);
                })
                .thenSucceed();
    }

    /** With contraptionTerrainImpacts off (a batch of its own: the config is shared), a fast iron arm passes the stone. */
    @GameTest(template = FLAT, batch = BATCH + "_off", timeoutTicks = TIMEOUT)
    public static void withTerrainImpactsOffTheStoneStands(GameTestHelper helper) {
        Rig rig = rig(helper, 36, Blocks.IRON_BLOCK.defaultBlockState());
        BlockPos target = rig.armBase().offset(0, 0, ARM);
        ServerLevel level = helper.getLevel();
        boolean was = FullStopConfig.SERVER.contraptionTerrainImpacts.get();
        whenTicking(helper, rig.motor())
                .thenExecute(() -> {
                    FullStopConfig.SERVER.contraptionTerrainImpacts.set(false);
                    level.setBlock(target, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                })
                .thenIdle(5)
                .thenExecute(() -> speed(level, rig.motor(), 64))
                .thenIdle(80)
                .thenExecute(() -> {
                    FullStopConfig.SERVER.contraptionTerrainImpacts.set(was);
                    List<Contact> contacts = contacts(level, rig);
                    report("terrain impacts off", contacts + "; stone " + level.getBlockState(target));
                    helper.assertTrue(level.getBlockState(target).is(Blocks.STONE), "the stone broke with the switch off");
                    helper.assertTrue(contacts.isEmpty(), "contacts with the switch off: " + contacts);
                    speed(level, rig.motor(), 0);
                })
                .thenSucceed();
    }

    // ---- the rigs ---------------------------------------------------------------------------------------------

    /** A creative motor at {@code motor} driving a bearing facing up; its arm of {@code armBlock} from {@code armBase} out along +X. */
    record Rig(BlockPos motor, BlockPos bearing, BlockPos armBase) {}

    private static Rig rig(GameTestHelper helper, int laneIndex, BlockState armBlock) {
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
        fill(level, armBase, armBase.offset(ARM, 0, 0), armBlock);
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

    private static List<Contact> recentNear(ServerLevel level, BlockPos at) {
        AbstractContraptionEntity c = contraption(level, at);
        return c == null ? List.of() : ContraptionTerrainImpacts.recent(c.getId());
    }

    private static List<Contact> cartContacts(AbstractMinecart cart) {
        for (net.minecraft.world.entity.Entity passenger : cart.getPassengers()) {
            if (passenger instanceof AbstractContraptionEntity c) return ContraptionTerrainImpacts.recent(c.getId());
        }
        // the contraption may have come off its cart: whichever is nearest
        AbstractContraptionEntity near = contraption((ServerLevel) cart.level(), cart.blockPosition());
        return near == null ? List.of() : ContraptionTerrainImpacts.recent(near.getId());
    }

    /** The contraptions near {@code at}: where they are, their blocks in the world, their contacts; and the level's stats. */
    private static String describe(ServerLevel level, BlockPos at, double reach) {
        StringBuilder out = new StringBuilder();
        for (AbstractContraptionEntity c : level.getEntitiesOfClass(AbstractContraptionEntity.class, new AABB(at).inflate(reach))) {
            out.append(c.getType()).append('#').append(c.getId()).append(c.isAlive() ? "" : " dead").append(" anchor ")
                    .append(c.getAnchorVec()).append(" vehicle ").append(c.getVehicle());
            if (c.getContraption() != null) {
                out.append(" blocks ");
                for (BlockPos local : c.getContraption().getBlocks().keySet()) {
                    out.append(local.toShortString()).append("->").append(BlockPos.containing(c.applyRotation(Vec3.atCenterOf(local)
                            .subtract(0.5, 0.5, 0.5), 1.0f).add(0.5, 0.5, 0.5).add(c.getAnchorVec())).toShortString()).append(' ');
                }
            }
            out.append(" contacts ").append(ContraptionTerrainImpacts.recent(c.getId())).append("; ");
        }
        return out.append("stats ").append(ContraptionTerrainImpacts.stats(level)).toString();
    }

    private static AbstractContraptionEntity contraption(ServerLevel level, BlockPos near) {
        for (AbstractContraptionEntity c : level.getEntitiesOfClass(AbstractContraptionEntity.class, new AABB(near).inflate(8.0))) {
            if (c.isAlive()) return c;
        }
        return null;
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
        // fixed places far from every test plot (plots move about as tests are added, and the other suites' lanes follow them)
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
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }
}
