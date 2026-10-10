package net.camacraft.fullstop.gametest.create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TNT and Create contraptions ({@code tntImpacts}; the owner, 2026-10-04: "TNT ... should explode on impact with the
 * terrain or another ship or create contraption"): the bearing arm of {@link ContraptionTerrainTests} at 64 RPM (its
 * tip at about 27 m/s) into TNT standing in its sweep, and the same arm with TNT for a tip into stone. Either way the
 * TNT goes off at once, where it was hit, and nothing drops. Opt-in: {@code gradlew runGameTestServer -Pcreate}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ContraptionTntTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "create_flat_48x8x48";
    private static final String BATCH = "fullstop_create_tnt";
    private static final int TIMEOUT = 600;
    private static final int ARM = 4;

    private ContraptionTntTests() {
    }

    private record Bang(Vec3 at, long tick) {}

    private static final List<Bang> BANGS = new CopyOnWriteArrayList<>();
    private static boolean listening;

    private static synchronized void listen() {
        if (listening) return;
        MinecraftForge.EVENT_BUS.addListener((ExplosionEvent.Detonate e) -> {
            if (e.getLevel() instanceof ServerLevel level) BANGS.add(new Bang(e.getExplosion().getPosition(), level.getGameTime()));
        });
        listening = true;
    }

    private static int bangs(Vec3 near, double within) {
        int n = 0;
        for (Bang bang : BANGS) {
            if (bang.at().distanceTo(near) <= within) n++;
        }
        return n;
    }

    /** TNT standing in an iron arm's sweep goes off when the arm reaches it. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void tntInAnArmsSweepGoesOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos motor = rig(helper, 70, Blocks.IRON_BLOCK.defaultBlockState(), Blocks.IRON_BLOCK.defaultBlockState());
        BlockPos armBase = motor.above(2);
        BlockPos target = armBase.offset(0, 0, ARM);
        whenTicking(helper, motor)
                .thenExecute(() -> level.setBlock(target, Blocks.TNT.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(5)
                .thenExecute(() -> speed(level, motor, 64))
                .thenWaitUntil(() -> helper.assertTrue(!level.getBlockState(target).is(Blocks.TNT), "the TNT still stands ("
                        + contacts(level, armBase) + ")"))
                .thenIdle(3)
                .thenExecute(() -> {
                    int bangs = bangs(Vec3.atCenterOf(target), 3.0);
                    List<Contact> contacts = contacts(level, armBase);
                    report("iron arm at 64 RPM into TNT", "bangs " + bangs + "; " + contacts);
                    helper.assertTrue(bangs >= 1, "the TNT went without an explosion: " + contacts);
                    helper.assertTrue(tntItems(level, target).isEmpty(), "the TNT dropped as an item");
                    speed(level, motor, 0);
                })
                .thenSucceed();
    }

    /** An arm with TNT for its tip, swung into stone: the tip goes off against the stone and is gone from the arm. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void tntOnAnArmsTipGoesOffAgainstStone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos motor = rig(helper, 71, Blocks.IRON_BLOCK.defaultBlockState(), Blocks.TNT.defaultBlockState());
        BlockPos armBase = motor.above(2);
        BlockPos target = armBase.offset(0, 0, ARM);
        AbstractContraptionEntity[] arm = new AbstractContraptionEntity[1];
        whenTicking(helper, motor)
                .thenExecute(() -> level.setBlock(target, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(5)
                .thenExecute(() -> speed(level, motor, 64))
                .thenWaitUntil(() -> {
                    arm[0] = contraption(level, armBase);
                    helper.assertTrue(arm[0] != null && arm[0].getContraption() != null, "the arm hasn't assembled");
                })
                .thenWaitUntil(() -> helper.assertTrue(!arm[0].getContraption().getBlocks().containsKey(new BlockPos(ARM, 0, 0)),
                        "the TNT tip is still on the arm (" + contacts(level, armBase) + ")"))
                .thenIdle(3)
                .thenExecute(() -> {
                    int bangs = bangs(Vec3.atCenterOf(target), 4.0);
                    List<Contact> contacts = ContraptionTerrainImpacts.recent(arm[0].getId());
                    report("arm with a TNT tip at 64 RPM into stone", "bangs " + bangs + "; " + contacts);
                    helper.assertTrue(bangs >= 1, "the TNT tip went without an explosion: " + contacts);
                    helper.assertTrue(contacts.stream().anyMatch(c -> c.outcome() == Outcome.KNOCKED_OFF), "no contact took the tip off: " + contacts);
                    helper.assertTrue(tntItems(level, target).isEmpty(), "the TNT dropped as an item");
                    speed(level, motor, 0);
                })
                .thenSucceed();
    }

    private static List<ItemEntity> tntItems(ServerLevel level, BlockPos near) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(near).inflate(12.0), i -> i.getItem().is(Items.TNT));
    }

    /** {@link ContraptionTerrainTests}' rig, with the arm's tip of its own block; returns the creative motor's position. */
    private static BlockPos rig(GameTestHelper helper, int laneIndex, BlockState armBlock, BlockState tipBlock) {
        listen();
        ServerLevel level = helper.getLevel();
        FullStopConfig.SERVER.debugContraptionLog.set(true);
        int groundTop = level.getMinBuildHeight() + 4;
        BlockPos o = new BlockPos(-20000 - laneIndex * 96, groundTop, 20000);
        for (int cx = (o.getX() - 24) >> 4; cx <= (o.getX() + 24) >> 4; cx++) {
            for (int cz = (o.getZ() - 24) >> 4; cz <= (o.getZ() + 24) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        fill(level, o.offset(-7, 0, -7), o.offset(7, 4, 7), Blocks.AIR.defaultBlockState());
        fill(level, o.offset(-7, 1, -7), o.offset(7, 1, 7), Blocks.STONE.defaultBlockState());
        level.setBlock(o, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), Block.UPDATE_ALL);
        BlockPos bearing = o.above();
        level.setBlock(bearing, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP),
                Block.UPDATE_ALL);
        speed(level, o, 0);
        BlockPos armBase = bearing.above();
        fill(level, armBase, armBase.offset(ARM - 1, 0, 0), armBlock);
        level.setBlock(armBase.offset(ARM, 0, 0), tipBlock, Block.UPDATE_ALL);
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(armBase).minmax(new AABB(armBase.offset(ARM, 0, 0)))));
        return o;
    }

    private static List<Contact> contacts(ServerLevel level, BlockPos near) {
        AbstractContraptionEntity c = contraption(level, near);
        return c == null ? List.of() : ContraptionTerrainImpacts.recent(c.getId());
    }

    private static AbstractContraptionEntity contraption(ServerLevel level, BlockPos near) {
        for (AbstractContraptionEntity c : level.getEntitiesOfClass(AbstractContraptionEntity.class, new AABB(near).inflate(8.0))) {
            if (c.isAlive()) return c;
        }
        return null;
    }

    private static void speed(ServerLevel level, BlockPos motor, int rpm) {
        if (level.getBlockEntity(motor) instanceof CreativeMotorBlockEntity be) be.generatedSpeed.setValue(rpm);
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
