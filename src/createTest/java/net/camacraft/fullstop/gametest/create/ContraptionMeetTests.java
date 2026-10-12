package net.camacraft.fullstop.gametest.create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.mounted.CartAssembleRailType;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlock;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Create contraptions meeting each other ({@code contraptionsMeet}; the owner: "if I have two create contraptions spinning
 * next to each other and they collide, they don't actually collide with one another and break each other"), with the
 * real Create 6.0.8. Bearing A (a creative motor under a mechanical bearing facing up) turns an iron arm four blocks out
 * along +X; what it meets stands where its tip's circle passes, 3 blocks out along X and 3 along Z (4.2 from its axis):
 * <ul>
 *   <li>the wool tip of a second arm on bearing B (its axis 7 blocks along Z, its arm pointing back at A), turning at 1
 *   RPM while A turns at 64: the wool is knocked off B, A turns on;</li>
 *   <li>a parked minecart contraption's wool on either side (whichever way A turns), A at 64 RPM: knocked off the cart;</li>
 *   <li>a parked cart's iron on either side, A at 2 RPM (pressing, under contraptionTerrainMinSpeed): A jams against it,
 *   overstressed, its tip's face against the iron, until its motor stops.</li>
 * </ul>
 * Opt-in: {@code gradlew runGameTestServer -Pcreate}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ContraptionMeetTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "create_flat_48x8x48";
    private static final String BATCH = "fullstop_create_meet";
    private static final int ARM = 4;

    private ContraptionMeetTests() {
    }

    /** A at 64 RPM, iron, into the wool tip of B's arm turning at 1 RPM: the wool comes off B, A turns on, nobody jams. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 400)
    public static void aFastIronArmKnocksTheWoolTipOffAnotherArm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 90);
        Bearing a = bearing(level, o, Direction.EAST, Blocks.IRON_BLOCK.defaultBlockState());
        Bearing b = bearing(level, o.offset(3, 0, 7), Direction.NORTH, Blocks.WHITE_WOOL.defaultBlockState());
        AbstractContraptionEntity[] arms = new AbstractContraptionEntity[2];
        int[] before = new int[2];
        boolean[] overstressed = {false};
        whenTicking(helper, o)
                .thenExecute(() -> speed(level, b.motor(), 1))
                .thenWaitUntil(() -> {
                    arms[1] = contraption(level, b.armBase());
                    helper.assertTrue(arms[1] != null, "B's arm hasn't assembled");
                    before[0] = arms[1].getContraption().getBlocks().size();
                })
                .thenExecute(() -> speed(level, a.motor(), 64))
                .thenWaitUntil(() -> {
                    arms[0] = contraption(level, a.armBase());
                    if (arms[0] != null && before[1] == 0) before[1] = arms[0].getContraption().getBlocks().size();
                    overstressed[0] |= kinetic(level, a.bearing()).isOverStressed();
                    helper.assertTrue(arms[0] != null && arms[1].getContraption().getBlocks().size() < before[0],
                            "B's wool tip is still on (" + all(arms) + ")");
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    List<Contact> contacts = all(arms);
                    report("iron arm at 64 RPM into another arm's wool tip", contacts + "; B blocks " + before[0] + " -> "
                            + arms[1].getContraption().getBlocks().size() + "; A overstressed meanwhile " + overstressed[0]);
                    helper.assertTrue(contacts.stream().anyMatch(c -> c.outcome() == Outcome.KNOCKED_OFF || c.outcome() == Outcome.BROKE),
                            "no knock-off recorded: " + contacts);
                    helper.assertTrue(!arms[1].getContraption().getBlocks().containsKey(new BlockPos(0, 0, -ARM)), "B kept its tip");
                    helper.assertTrue(arms[0].getContraption().getBlocks().size() == before[1], "A lost blocks: " + before[1] + " -> "
                            + arms[0].getContraption().getBlocks().size());
                    helper.assertTrue(contacts.stream().noneMatch(c -> c.outcome() == Outcome.JAMMED), "they jammed: " + contacts);
                    helper.assertTrue(kinetic(level, a.bearing()).getSpeed() != 0.0f, "A stopped");
                    speed(level, a.motor(), 0);
                    speed(level, b.motor(), 0);
                })
                .thenSucceed();
    }

    /** A at 64 RPM, iron, into a parked minecart contraption's wool (one on each side): the wool comes off the cart. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 400)
    public static void aFastIronArmKnocksWoolOffAParkedCart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 91);
        Bearing a = bearing(level, o, Direction.EAST, Blocks.IRON_BLOCK.defaultBlockState());
        Cart[] carts = {cart(level, o, 3, Blocks.WHITE_WOOL.defaultBlockState()), cart(level, o, -3, Blocks.WHITE_WOOL.defaultBlockState())};
        AbstractMinecart[] minecarts = new AbstractMinecart[2];
        AbstractContraptionEntity[] arms = new AbstractContraptionEntity[3];
        whenTicking(helper, o)
                .thenExecute(() -> {
                    for (int i = 0; i < 2; i++) minecarts[i] = park(level, carts[i]);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    for (int i = 0; i < 2; i++) assemble(level, carts[i], minecarts[i]);
                })
                .thenWaitUntil(() -> helper.assertTrue(!minecarts[0].getPassengers().isEmpty() && !minecarts[1].getPassengers().isEmpty(),
                        "the carts haven't been assembled onto"))
                .thenExecute(() -> {
                    for (int i = 0; i < 2; i++) arms[i + 1] = (AbstractContraptionEntity) minecarts[i].getPassengers().get(0);
                    speed(level, a.motor(), 64);
                })
                .thenWaitUntil(() -> {
                    arms[0] = contraption(level, a.armBase());
                    helper.assertTrue(arms[0] != null && (!carries(arms[1], Blocks.WHITE_WOOL) || !carries(arms[2], Blocks.WHITE_WOOL)),
                            "both carts still carry their wool (" + all(arms) + ")");
                })
                .thenExecute(() -> {
                    List<Contact> contacts = all(arms);
                    report("iron arm at 64 RPM into a parked cart's wool", contacts + "; carts carry wool " + carries(arms[1], Blocks.WHITE_WOOL)
                            + " / " + carries(arms[2], Blocks.WHITE_WOOL));
                    helper.assertTrue(contacts.stream().anyMatch(c -> c.outcome() == Outcome.KNOCKED_OFF || c.outcome() == Outcome.BROKE),
                            "no knock-off recorded: " + contacts);
                    helper.assertTrue(arms[0].getContraption().getBlocks().size() == ARM + 1, "A lost blocks");
                    speed(level, a.motor(), 0);
                })
                .thenSucceed();
    }

    /**
     * A at 2 RPM (its tip 0.8 m/s: pressing), iron, into a parked cart's iron (one on each side): A jams against it, its
     * network overstressed, its tip's face against the iron (not into it); stopping its motor lets go.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 900)
    public static void aSlowArmJamsAgainstAParkedCartsIron(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 92);
        Bearing a = bearing(level, o, Direction.EAST, Blocks.IRON_BLOCK.defaultBlockState());
        Cart[] carts = {cart(level, o, 3, Blocks.IRON_BLOCK.defaultBlockState()), cart(level, o, -3, Blocks.IRON_BLOCK.defaultBlockState())};
        AbstractMinecart[] minecarts = new AbstractMinecart[2];
        AbstractContraptionEntity[] arms = new AbstractContraptionEntity[3];
        float[] held = new float[1];
        whenTicking(helper, o)
                .thenExecute(() -> {
                    for (int i = 0; i < 2; i++) minecarts[i] = park(level, carts[i]);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    for (int i = 0; i < 2; i++) assemble(level, carts[i], minecarts[i]);
                })
                .thenWaitUntil(() -> helper.assertTrue(!minecarts[0].getPassengers().isEmpty() && !minecarts[1].getPassengers().isEmpty(),
                        "the carts haven't been assembled onto"))
                .thenExecute(() -> {
                    for (int i = 0; i < 2; i++) arms[i + 1] = (AbstractContraptionEntity) minecarts[i].getPassengers().get(0);
                    speed(level, a.motor(), 2);
                })
                .thenWaitUntil(() -> {
                    arms[0] = contraption(level, a.armBase());
                    helper.assertTrue(arms[0] != null && kinetic(level, a.bearing()).isOverStressed(), "A isn't jammed yet (" + all(arms) + ")");
                })
                .thenExecute(() -> held[0] = ((ControlledContraptionEntity) arms[0]).getAngle(1.0f))
                .thenIdle(40)
                .thenExecute(() -> {
                    List<Contact> contacts = all(arms);
                    float angle = ((ControlledContraptionEntity) arms[0]).getAngle(1.0f);
                    Vec3 tip = arms[0].applyRotation(Vec3.atCenterOf(new BlockPos(ARM, 0, 0)).subtract(0.5, 0.5, 0.5), 1.0f)
                            .add(0.5, 0.5, 0.5).add(arms[0].getAnchorVec());
                    double depth = 0.0;
                    for (int i = 1; i < 3; i++) {
                        for (BlockPos local : arms[i].getContraption().getBlocks().keySet()) {
                            Vec3 c = arms[i].applyRotation(Vec3.atCenterOf(local).subtract(0.5, 0.5, 0.5), 1.0f).add(0.5, 0.5, 0.5)
                                    .add(arms[i].getAnchorVec());
                            double dx = 1.0 - Math.abs(tip.x - c.x), dy = 1.0 - Math.abs(tip.y - c.y), dz = 1.0 - Math.abs(tip.z - c.z);
                            if (dx > 0 && dy > 0 && dz > 0) depth = Math.max(depth, Math.min(dx, dz));
                        }
                    }
                    report("iron arm at 2 RPM into a parked cart's iron", contacts + "; held at " + fmt(held[0]) + " -> " + fmt(angle)
                            + " deg, tip " + String.format(Locale.ROOT, "%.3f", depth) + " into the iron");
                    helper.assertTrue(contacts.stream().anyMatch(c -> c.outcome() == Outcome.JAMMED), "no jam recorded: " + contacts);
                    helper.assertTrue(Math.abs(Mth.wrapDegrees(angle - held[0])) < 0.5f, "the jammed arm moved on");
                    helper.assertTrue(depth < 0.3, "A's tip went " + depth + " into the cart's iron");
                    speed(level, a.motor(), 0);
                })
                .thenWaitUntil(() -> helper.assertTrue(!ContraptionStress.jammed(kinetic(level, a.bearing())), "the jam didn't let go"))
                .thenSucceed();
    }

    /**
     * A hit that got through never stops the network by itself (the owner: "the entire contraption would visually freeze its
     * motion for a second after the block on the contraption is broken"): a wool-tipped arm at 64 RPM into stone, every hit
     * made a million times heavier on the network (contraptionStressPerImpact 1e6: far past even a creative motor), loses
     * its tip and turns on, its network never overstressed, its stressometer at the most the network can carry.
     */
    @GameTest(template = FLAT, batch = BATCH + "_hitload", timeoutTicks = 400)
    public static void aKnockOffNeverStopsTheNetworkByItself(GameTestHelper helper) {
        hitLoad(helper, 93, false);
    }

    /** The same with contraptionHitsOverstress on (the old behaviour): the knock-off overstresses the network for a while. */
    @GameTest(template = FLAT, batch = BATCH + "_hitload_old", timeoutTicks = 400)
    public static void withHitsOverstressingAKnockOffStopsTheNetworkForAWhile(GameTestHelper helper) {
        hitLoad(helper, 94, true);
    }

    private static void hitLoad(GameTestHelper helper, int laneIndex, boolean hitsOverstress) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, laneIndex);
        Bearing a = bearing(level, o, Direction.EAST, Blocks.OAK_PLANKS.defaultBlockState());
        level.setBlock(a.armBase().relative(Direction.EAST, ARM), Blocks.WHITE_WOOL.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos[] stones = {a.armBase().offset(0, 0, ARM), a.armBase().offset(0, 0, -ARM)};
        double perImpact = FullStopConfig.SERVER.contraptionStressPerImpact.get();
        boolean before = FullStopConfig.SERVER.contraptionHitsOverstress.get();
        int[] overstressedTicks = {0};
        long[] knocked = {0};
        AbstractContraptionEntity[] arm = new AbstractContraptionEntity[1];
        whenTicking(helper, o)
                .thenExecute(() -> {
                    for (BlockPos stone : stones) level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    FullStopConfig.SERVER.contraptionStressPerImpact.set(1.0E6);
                    FullStopConfig.SERVER.contraptionHitsOverstress.set(hitsOverstress);
                    speed(level, a.motor(), 64);
                })
                .thenWaitUntil(() -> {
                    arm[0] = contraption(level, a.armBase());
                    if (kinetic(level, a.bearing()).isOverStressed()) overstressedTicks[0]++;
                    helper.assertTrue(arm[0] != null && !arm[0].getContraption().getBlocks().containsKey(new BlockPos(ARM, 0, 0)),
                            "the wool tip is still on");
                })
                .thenExecute(() -> knocked[0] = level.getGameTime())
                .thenExecuteFor(40, () -> {
                    if (kinetic(level, a.bearing()).isOverStressed()) overstressedTicks[0]++;
                })
                .thenExecute(() -> {
                    KineticBlockEntity bearing = kinetic(level, a.bearing());
                    double peak = ContraptionStress.takePeak(bearing);
                    report("wool tip knocked off at 64 RPM, hits 1e6 SU a unit, contraptionHitsOverstress " + hitsOverstress,
                            all(arm) + "; overstressed " + overstressedTicks[0] + " ticks; peak load " + fmt(peak) + " SU");
                    FullStopConfig.SERVER.contraptionStressPerImpact.set(perImpact);
                    FullStopConfig.SERVER.contraptionHitsOverstress.set(before);
                    speed(level, a.motor(), 0);
                    if (hitsOverstress) {
                        helper.assertTrue(overstressedTicks[0] > 0, "the heavy hit didn't overstress the network with hits overstressing");
                    } else {
                        helper.assertTrue(overstressedTicks[0] == 0, "a knock-off overstressed the network for " + overstressedTicks[0] + " ticks");
                    }
                })
                .thenSucceed();
    }

    // ---- rigs ----------------------------------------------------------------------------------------------------

    /** A creative motor at {@code motor}, a mechanical bearing facing up on it, its arm of {@code block} out along {@code toward}. */
    record Bearing(BlockPos motor, BlockPos bearing, BlockPos armBase) {}

    private static Bearing bearing(ServerLevel level, BlockPos motor, Direction toward, BlockState block) {
        level.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), Block.UPDATE_ALL);
        BlockPos bearing = motor.above();
        level.setBlock(bearing, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP),
                Block.UPDATE_ALL);
        speed(level, motor, 0);
        BlockPos armBase = bearing.above();
        for (int i = 0; i <= ARM; i++) level.setBlock(armBase.relative(toward, i), block, Block.UPDATE_ALL);
        glue(level, armBase, armBase.relative(toward, ARM));
        return new Bearing(motor, bearing, armBase);
    }

    /**
     * A cart assembler (powered) on a short east-west track sunk into the floor; its load is a plank (the contraption's
     * anchor, which never comes off) with {@code block} glued on top, at A's arm height.
     */
    record Cart(BlockPos assembler) {}

    private static Cart cart(ServerLevel level, BlockPos o, int dz, BlockState block) {
        int railY = o.getY();
        for (int x = 1; x <= 5; x++) {
            BlockPos at = new BlockPos(o.getX() + x, railY, o.getZ() + dz);
            level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(at, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST), Block.UPDATE_ALL);
        }
        BlockPos assembler = new BlockPos(o.getX() + 3, railY, o.getZ() + dz);
        level.setBlock(assembler, AllBlocks.CART_ASSEMBLER.getDefaultState()
                .setValue(CartAssemblerBlock.RAIL_SHAPE, RailShape.EAST_WEST)
                .setValue(CartAssemblerBlock.RAIL_TYPE, CartAssembleRailType.REGULAR)
                .setValue(CartAssemblerBlock.POWERED, true), Block.UPDATE_ALL);
        level.setBlock(assembler.below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(assembler.above(), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(assembler.above(2), block, Block.UPDATE_ALL);
        glue(level, assembler.above(), assembler.above(2));
        return new Cart(assembler);
    }

    private static AbstractMinecart park(ServerLevel level, Cart cart) {
        AbstractMinecart c = EntityType.MINECART.create(level);
        if (c == null) throw new IllegalStateException("no minecart");
        c.moveTo(cart.assembler().getX() + 0.5, cart.assembler().getY() + 0.0625, cart.assembler().getZ() + 0.5, 0.0f, 0.0f);
        level.addFreshEntity(c);
        return c;
    }

    private static void assemble(ServerLevel level, Cart cart, AbstractMinecart minecart) {
        if (level.getBlockEntity(cart.assembler()) instanceof CartAssemblerBlockEntity be) be.assembleNextTick(minecart);
    }

    /** Whether the contraption still has a block of {@code block}. */
    private static boolean carries(AbstractContraptionEntity c, Block block) {
        return c.getContraption().getBlocks().values().stream().anyMatch(info -> info.state().is(block));
    }

    private static KineticBlockEntity kinetic(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof KineticBlockEntity k) return k;
        throw new IllegalStateException("no kinetic block at " + pos);
    }

    /** Every contact the contraptions made lately (an empty list for a missing one). */
    private static List<Contact> all(AbstractContraptionEntity[] cs) {
        List<Contact> out = new ArrayList<>();
        for (AbstractContraptionEntity c : cs) if (c != null) out.addAll(ContraptionTerrainImpacts.recent(c.getId()));
        return out;
    }

    /** The bearing contraption anchored at {@code anchor} (a bearing's box spans its whole circle, so it is told by its anchor). */
    private static AbstractContraptionEntity contraption(ServerLevel level, BlockPos anchor) {
        for (AbstractContraptionEntity c : level.getEntitiesOfClass(ControlledContraptionEntity.class, new AABB(anchor).inflate(8.0))) {
            if (c.isAlive() && BlockPos.containing(c.getAnchorVec().add(0.5, 0.5, 0.5)).equals(anchor)) return c;
        }
        return null;
    }

    private static void glue(ServerLevel level, BlockPos from, BlockPos to) {
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(from).minmax(new AABB(to))));
    }

    private static void speed(ServerLevel level, BlockPos motor, int rpm) {
        if (level.getBlockEntity(motor) instanceof CreativeMotorBlockEntity be) be.generatedSpeed.setValue(rpm);
    }

    /** A lane of its own, at a fixed place far from the other suites (as ContraptionTerrainTests' lanes), cleared, a stone floor. */
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
        for (BlockPos pos : BlockPos.betweenClosed(o.offset(-9, 0, -9), o.offset(9, 5, 9))) {
            level.setBlock(pos, pos.getY() == o.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        return o;
    }

    private static GameTestSequence whenTicking(GameTestHelper helper, BlockPos o) {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                helper.getLevel().isPositionEntityTicking(o.above(2)), "the lane doesn't tick entities yet"));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }
}
