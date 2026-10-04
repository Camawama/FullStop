package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * A teleport is a jump, not motion: a mob moved by {@code teleportTo} (no {@code EntityTeleportEvent}: another mod's
 * teleport) two blocks in one tick looks like 40 m/s to a position history, under the 60 m/s discontinuity guard, and
 * the next tick's contact was judged at that speed. Each pig has 1000 health so a phantom hit shows.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class TeleportTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_teleport";
    private static final float HEALTH = 1000.0f;

    private TeleportTests() {
    }

    /** A pig standing still, teleported two blocks so it lands flush against a stone wall: nothing hurts it. */
    @GameTest(template = FLAT, timeoutTicks = 200, batch = BATCH)
    public static void aMobTeleportedAgainstAWallIsNotHurtByTheJump(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // the template's ground is two blocks thick: its top block is at y 1
        BlockPos floor = helper.absolutePos(new BlockPos(4, 1, 4));
        for (int z = -1; z <= 1; z++) {
            for (int y = 1; y <= 3; y++) level.setBlock(floor.offset(3, y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        Mob pig = pig(level, Vec3.atBottomCenterOf(floor.above()));
        // flush against the wall's face (the pig is 0.9 wide): 2.04 blocks in one tick
        Vec3 to = new Vec3(floor.getX() + 3 - 0.46, floor.getY() + 1, floor.getZ() + 0.5);
        StringBuilder hurts = new StringBuilder();
        float[] last = {HEALTH};
        long[] start = {-1};
        helper.onEachTick(() -> {
            if (start[0] < 0) start[0] = level.getGameTime();
            if (pig.getHealth() < last[0]) {
                hurts.append(String.format(Locale.ROOT, " t%d %.1f %s", level.getGameTime() - start[0], last[0] - pig.getHealth(),
                        pig.getLastDamageSource() == null ? "?" : pig.getLastDamageSource().getMsgId()));
                last[0] = pig.getHealth();
            }
        });
        helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> pig.teleportTo(to.x, to.y, to.z))
                .thenIdle(20)
                .thenExecute(() -> {
                    double lost = HEALTH - pig.getHealth();
                    LOGGER.info("[FullStop gametest] teleport: pig moved {} blocks against a wall by teleportTo: lost {} health",
                            String.format(Locale.ROOT, "%.2f", to.x - (floor.getX() + 0.5)), String.format(Locale.ROOT, "%.1f", lost) + " (hurts:" + hurts + ")");
                    helper.assertTrue(lost < 0.5, "the pig was hurt by its own teleport: lost " + lost);
                })
                .thenSucceed();
    }

    /** A pig hovering a little above the ground, teleported two blocks down onto slime: no bounce off the jump. */
    @GameTest(template = FLAT, timeoutTicks = 200, batch = BATCH)
    public static void aMobTeleportedOntoSlimeDoesNotBounceOffTheJump(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(12, 0, 4));
        level.setBlock(floor.above(), Blocks.SLIME_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        Mob pig = pig(level, Vec3.atBottomCenterOf(floor.above(4)));
        pig.setNoGravity(true);
        double[] highest = {Double.NEGATIVE_INFINITY};
        double slimeTop = floor.getY() + 2.0;
        helper.onEachTick(() -> highest[0] = Math.max(highest[0], pig.getY()));
        helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> {
                    pig.setNoGravity(false);
                    pig.teleportTo(pig.getX(), slimeTop + 0.01, pig.getZ());
                    highest[0] = Double.NEGATIVE_INFINITY;
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    double rose = highest[0] - slimeTop;
                    LOGGER.info("[FullStop gametest] teleport: pig dropped 2 blocks onto slime by teleportTo: rose {} blocks after",
                            String.format(Locale.ROOT, "%.2f", rose));
                    helper.assertTrue(rose < 0.3, "the pig bounced off its teleport: rose " + rose + " blocks");
                    helper.assertTrue(HEALTH - pig.getHealth() < 0.5, "the pig was hurt by its teleport");
                })
                .thenSucceed();
    }

    private static Mob pig(ServerLevel level, Vec3 at) {
        Mob pig = EntityType.PIG.create(level);
        if (pig == null) throw new IllegalStateException("no pig");
        pig.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        pig.setNoAi(true);
        pig.setPersistenceRequired();
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTH);
        pig.setHealth(HEALTH);
        level.addFreshEntity(pig);
        return pig;
    }
}
