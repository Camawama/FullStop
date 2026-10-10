package net.camacraft.fullstop.gametest;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.server.physics.interaction.KineticBlockInteractions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;

/**
 * The owner's reports of 2026-10-04 on bodies: speed blows a fire out (a burning pig carried through the air loses its
 * fire time faster, one at rest only vanilla's tick each tick, one in fire not at all: {@code WindOnFire}), and Full
 * Stop's cracks go with their block (a cracked block broken and set back in the same place is whole: {@code
 * KineticBlockInteractions.onBlockChanged}). Opt-in with the rest: {@code gradlew runGameTestServer -PshipTests
 * -PtestOnly=BodyFireCrackTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class BodyFireCrackTests {

    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_bodies";
    private static final int FIRE = 160;

    private BodyFireCrackTests() {
    }

    /** A pig alight, moved {@code mps} to and fro along x every tick (no AI, no gravity); its fire time after 20 ticks. */
    private static void burningPig(GameTestHelper helper, double mps, boolean inFire, java.util.function.IntConsumer check) {
        ServerLevel level = helper.getLevel();
        BlockPos base = new BlockPos(24, 2, 24);
        if (inFire) {
            for (int dx = -1; dx <= 1; dx++) {
                helper.setBlock(base.offset(dx, -1, 0), Blocks.NETHERRACK);
                helper.setBlock(base.offset(dx, 0, 0), Blocks.FIRE);
            }
        }
        Pig pig = helper.spawn(EntityType.PIG, base);
        pig.setNoAi(true);
        pig.setNoGravity(true);
        pig.setInvulnerable(true);
        pig.setRemainingFireTicks(FIRE);
        Vec3 start = pig.position();
        double step = mps / 20.0;
        double[] dir = {1.0};
        int[] ticks = {0};
        helper.onEachTick(() -> {
            if (ticks[0]++ >= 20) return;
            double x = pig.getX() + dir[0] * step;
            if (Math.abs(x - start.x) > (inFire ? 0.8 : 6.0)) {
                dir[0] = -dir[0];
                x = pig.getX() + dir[0] * step;
            }
            pig.setPos(x, start.y, start.z);
            if (inFire && pig.getRemainingFireTicks() < FIRE) pig.setRemainingFireTicks(Math.max(pig.getRemainingFireTicks(), 1));
        });
        helper.runAfterDelay(21, () -> {
            check.accept(pig.getRemainingFireTicks());
            helper.succeed();
        });
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 60)
    public static void aBurningPigAtRestBurnsOnlyByTheTick(GameTestHelper helper) {
        burningPig(helper, 0.0, false, left -> helper.assertTrue(left >= FIRE - 22,
                String.format(Locale.ROOT, "a pig at rest lost %d fire ticks in 20 ticks", FIRE - left)));
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 60)
    public static void aBurningPigAtWalkingPaceBurnsOnlyByTheTick(GameTestHelper helper) {
        burningPig(helper, 4.0, false, left -> helper.assertTrue(left >= FIRE - 22,
                String.format(Locale.ROOT, "a pig at 4 m/s lost %d fire ticks in 20 ticks", FIRE - left)));
    }

    /** At 14 m/s (a gallop) the air takes (14 - 4.5) x 0.5 = 4.75 more ticks each tick: about 115 in 20 ticks, not all 160. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 60)
    public static void aBurningPigAtFourteenMetresASecondBurnsDownFaster(GameTestHelper helper) {
        burningPig(helper, 14.0, false, left -> helper.assertTrue(left > 15 && left < FIRE - 80,
                String.format(Locale.ROOT, "a pig at 14 m/s has %d of %d fire ticks left after 20 ticks (expected about 45)", left, FIRE)));
    }

    /** However fast, the fire dies down: at 78 m/s (a long fall) the most the air takes is 10 a tick, 11 with the tick. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 60)
    public static void aBurningPigAtFallingSpeedStillBurnsForAWhile(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(24, 2, 24));
        pig.setNoAi(true);
        pig.setNoGravity(true);
        pig.setInvulnerable(true);
        pig.setRemainingFireTicks(FIRE);
        Vec3 start = pig.position();
        int[] ticks = {0};
        int[] outAt = {-1};
        helper.onEachTick(() -> {
            int t = ticks[0]++;
            if (outAt[0] >= 0 || t > 40) return;
            // 3.9 blocks a tick, to and fro across 16 blocks
            double phase = (t * 3.9) % 32.0;
            double x = start.x + (phase < 16.0 ? phase : 32.0 - phase) - 8.0;
            pig.setPos(x, start.y, start.z);
            if (pig.getRemainingFireTicks() <= 0) outAt[0] = t;
        });
        helper.runAfterDelay(42, () -> {
            helper.assertTrue(outAt[0] >= 12 && outAt[0] <= 18,
                    String.format(Locale.ROOT, "a pig at 78 m/s went out after %d ticks (expected about 15)", outAt[0]));
            helper.succeed();
        });
    }

    /** In fire the wind takes nothing: vanilla sets the body alight again there anyway. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 60)
    public static void aBurningPigShakenInFireIsNotBlownOut(GameTestHelper helper) {
        burningPig(helper, 16.0, true, left -> helper.assertTrue(left >= FIRE - 22,
                String.format(Locale.ROOT, "a pig shaken at 16 m/s in fire lost %d fire ticks in 20 ticks", FIRE - left)));
    }

    /**
     * Packed ice cracked by half the blow that breaks it, broken and set back: the new block is whole (a second half
     * blow only cracks it again). With the old crack kept, the second blow broke the new block.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void aCrackedBlockBrokenAndSetBackIsWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(24, 2, 24);
        BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, Blocks.PACKED_ICE);
        BlockState ice = level.getBlockState(pos);
        KineticBlockInteractions.SmashOutcome first = KineticBlockInteractions.smashBlock(level, pos, ice, 100.0, 60.0, false, null);
        helper.assertTrue(first.cracked() && !first.broken(), "the first half blow didn't crack the ice: " + first);
        helper.runAfterDelay(10, () -> {
            level.destroyBlock(pos, false);
            level.setBlock(pos, Blocks.PACKED_ICE.defaultBlockState(), 3);
        });
        helper.runAfterDelay(20, () -> {
            KineticBlockInteractions.SmashOutcome second = KineticBlockInteractions.smashBlock(level, pos, level.getBlockState(pos), 100.0, 60.0, false, null);
            helper.assertTrue(!second.broken(), "the ice set back in the cracked block's place broke at the first half blow: the crack stayed");
            helper.assertTrue(second.cracked(), "the second half blow didn't crack the new ice: " + second);
            helper.succeed();
        });
    }

    /** The same crack kept when nothing changes the block: a second half blow breaks it (the crack is real). */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void aCrackedBlockLeftAloneBreaksAtTheNextHalfBlow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(24, 2, 24);
        BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, Blocks.PACKED_ICE);
        KineticBlockInteractions.smashBlock(level, pos, level.getBlockState(pos), 100.0, 60.0, false, null);
        helper.runAfterDelay(20, () -> {
            KineticBlockInteractions.SmashOutcome second = KineticBlockInteractions.smashBlock(level, pos, level.getBlockState(pos), 100.0, 60.0, false, null);
            helper.assertTrue(second.broken(), "a cracked block left alone held a second half blow: " + second);
            helper.succeed();
        });
    }
}
