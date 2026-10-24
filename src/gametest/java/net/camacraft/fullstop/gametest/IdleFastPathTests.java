package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * The cheaper per-body checks of 2026-10-23 (pack harness perf_pack: 400 mobs walking about) still do what the
 * checks they replaced did: a block state's cached {@code fullstop:phaseable} answer (PhaseableBlockMixin, read again
 * after tags reload) lets a fast body through leaves and not through stone, and the bed check that reads the sleeping
 * data only when the pose count moved (EntityPoseFlipsMixin) follows a body into and out of a bed, which then takes no
 * phantom hit from the bed's jumps. The mobs have their AI: Full Stop doesn't measure a mob without it.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class IdleFastPathTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_idle_fast_paths";
    private static final float HEALTH = 1000.0f;

    private IdleFastPathTests() {
    }

    /**
     * Three cows flung at 32 m/s, weightless, at a 3x3 wall five blocks off: leaves, stone, and leaves again after the
     * tags were "reloaded" (the cache's epoch moved). The leaves let both through, the obsidian (too hard to break) stops its cow.
     */
    @GameTest(template = FLAT, timeoutTicks = 200, batch = BATCH)
    public static void aFastBodyPhasesThroughLeavesButNotStoneWithTheCachedTag(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        // the template's ground is two blocks thick: its top block is at y 1
        BlockPos leavesStart = helper.absolutePos(new BlockPos(4, 4, 6));
        BlockPos stoneStart = helper.absolutePos(new BlockPos(4, 4, 18));
        BlockPos reloadStart = helper.absolutePos(new BlockPos(4, 4, 30));
        int wallDx = 5;
        wall(level, leavesStart.offset(wallDx, 0, 0), leaves);
        wall(level, stoneStart.offset(wallDx, 0, 0), Blocks.OBSIDIAN.defaultBlockState());
        wall(level, reloadStart.offset(wallDx, 0, 0), leaves);
        Mob[] cows = new Mob[3];
        helper.startSequence()
                .thenExecute(() -> {
                    cows[0] = flungCow(level, leavesStart);
                    cows[1] = flungCow(level, stoneStart);
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    // tags (re)loaded: every cached answer is read again
                    FullStopTags.onTagsUpdated(null);
                    cows[2] = flungCow(level, reloadStart);
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    double leavesFace = leavesStart.getX() + wallDx;
                    double throughLeaves = cows[0].getX() - leavesFace;
                    double throughStone = cows[1].getX() - (stoneStart.getX() + wallDx);
                    double throughReloaded = cows[2].getX() - (reloadStart.getX() + wallDx);
                    LOGGER.info("[FullStop gametest] phasing: past the wall's near face by {} (leaves), {} (obsidian), {} (leaves after a tag reload)",
                            String.format(Locale.ROOT, "%.2f", throughLeaves), String.format(Locale.ROOT, "%.2f", throughStone),
                            String.format(Locale.ROOT, "%.2f", throughReloaded));
                    helper.assertTrue(throughLeaves > 1.5, "the cow didn't phase through the leaves: " + throughLeaves);
                    helper.assertTrue(throughStone < 0.0, "the cow went through the obsidian: " + throughStone);
                    helper.assertTrue(throughReloaded > 1.5, "after a tag reload the cow didn't phase through the leaves: " + throughReloaded);
                    for (Mob cow : cows) cow.discard();
                })
                .thenSucceed();
    }

    /**
     * A cow put to bed and woken again: Full Stop's capability reads it asleep while it lies there and awake after,
     * and the jumps onto the bed and off it beside the bed hurt nothing.
     */
    @GameTest(template = FLAT, timeoutTicks = 200, batch = BATCH)
    public static void aBodyPutToBedAndWokenIsFollowedAndTakesNoPhantomHit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos foot = helper.absolutePos(new BlockPos(20, 2, 20));
        BlockPos head = foot.relative(Direction.NORTH);
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
        level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
        Mob cow = cow(level, Vec3.atBottomCenterOf(foot.east(3)));
        boolean[] asleep = new boolean[2];
        float[] health = {HEALTH};
        helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> cow.startSleeping(head))
                .thenIdle(10)
                .thenExecute(() -> {
                    asleep[0] = wasSleeping(cow);
                    cow.stopSleeping();
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    asleep[1] = wasSleeping(cow);
                    health[0] = cow.getHealth();
                    LOGGER.info("[FullStop gametest] bed: Full Stop read the cow asleep in bed {}, after waking {}; health lost {}",
                            asleep[0], asleep[1], String.format(Locale.ROOT, "%.1f", HEALTH - health[0]));
                    helper.assertTrue(asleep[0], "Full Stop didn't see the cow asleep in its bed");
                    helper.assertFalse(asleep[1], "Full Stop still reads the cow asleep after it woke");
                    helper.assertTrue(HEALTH - health[0] < 0.5, "the cow was hurt getting into or out of bed: lost " + (HEALTH - health[0]));
                    cow.discard();
                })
                .thenSucceed();
    }

    private static void wall(ServerLevel level, BlockPos center, BlockState state) {
        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) level.setBlock(center.offset(0, y, z), state, Block.UPDATE_ALL);
        }
    }

    /** A weightless cow at {@code start} (its box centred on the wall's middle row), flung east at 32 m/s. */
    private static Mob flungCow(ServerLevel level, BlockPos start) {
        Mob cow = cow(level, new Vec3(start.getX() + 0.5, start.getY() - 0.2, start.getZ() + 0.5));
        cow.setNoGravity(true);
        cow.setDeltaMovement(1.6, 0.0, 0.0);
        cow.hurtMarked = true;
        return cow;
    }

    private static Mob cow(ServerLevel level, Vec3 at) {
        Mob cow = EntityType.COW.create(level);
        if (cow == null) throw new IllegalStateException("no cow");
        cow.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        cow.setPersistenceRequired();
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTH);
        cow.setHealth(HEALTH);
        level.addFreshEntity(cow);
        return cow;
    }

    /** Full Stop's own idea of whether the body is asleep (its bed check's state), by reflection. */
    private static boolean wasSleeping(Mob mob) {
        FullStopCapability cap = FullStopCapability.grabCapability(mob);
        if (cap == null) throw new IllegalStateException("no Full Stop capability on the cow");
        try {
            java.lang.reflect.Field field = FullStopCapability.class.getDeclaredField("wasSleeping");
            field.setAccessible(true);
            return field.getBoolean(cap);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't read wasSleeping", e);
        }
    }
}
