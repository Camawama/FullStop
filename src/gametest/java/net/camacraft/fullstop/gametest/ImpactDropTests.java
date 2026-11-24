package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * A block broken by a body drops what mining it with no tool would ({@code common.physics.rules.ImpactDrops},
 * {@code impactDropsFollowTools}): a block whose drops need the right tool drops nothing. Bodies are dropped 30 blocks
 * onto a pad of netherrack (hardness 0.4, needs a pickaxe for its drop: a fist leaves nothing) and of dirt (0.5, drops
 * with no tool): a pig (its collision rays' own breaking, {@code Level.destroyBlock}) and a giant (the crater's mass
 * breaking, {@code BreakEffects.destroy}). Before, the netherrack dropped netherrack. Opt-in with the rest of FullStop's
 * game tests: {@code gradlew runGameTestServer -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ImpactDropTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_impact_drops";
    /** The switch-off test has a batch of its own: it flips the server config, and a batch's tests run at once. */
    private static final String BATCH_OFF = "fullstop_impact_drops_off";
    private static final int TIMEOUT = 600;
    private static final int PAD = 5;
    private static final double DROP = 30.0;

    private ImpactDropTests() {
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigThatBreaksNetherrackWithItsFallDropsNothing(GameTestHelper helper) {
        drop(helper, 0, EntityType.PIG, Blocks.NETHERRACK, (broken, items) -> {
            helper.assertTrue(broken > 0, "the pig's 30-block fall broke no netherrack: nothing to test");
            helper.assertTrue(items == 0, items + " items dropped from " + broken + " netherrack broken by a fall (a fist drops none)");
        });
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aGiantsCraterInNetherrackDropsNothing(GameTestHelper helper) {
        drop(helper, 1, EntityType.GIANT, Blocks.NETHERRACK, (broken, items) -> {
            helper.assertTrue(broken > 0, "the giant's 30-block fall broke no netherrack: nothing to test");
            helper.assertTrue(items == 0, items + " items dropped from " + broken + " netherrack of a giant's crater");
        });
    }

    /** The control: dirt needs no tool, so what a fall breaks drops as before. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigThatBreaksDirtWithItsFallDropsTheDirt(GameTestHelper helper) {
        drop(helper, 2, EntityType.PIG, Blocks.DIRT, (broken, items) -> {
            helper.assertTrue(broken > 0, "the pig's 30-block fall broke no dirt");
            helper.assertTrue(items >= broken, "only " + items + " dirt dropped from " + broken + " dirt broken by a fall");
        });
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aGiantsCraterInDirtDropsTheDirt(GameTestHelper helper) {
        drop(helper, 3, EntityType.GIANT, Blocks.DIRT, (broken, items) -> {
            helper.assertTrue(broken > 0, "the giant's 30-block fall broke no dirt");
            helper.assertTrue(items >= broken, "only " + items + " dirt dropped from " + broken + " dirt of a giant's crater");
        });
    }

    /** With impactDropsFollowTools off the old drops are back: the switch is what keeps the netherrack. */
    @GameTest(template = FLAT, batch = BATCH_OFF, timeoutTicks = TIMEOUT)
    public static void withTheSwitchOffNetherrackBrokenByAFallDropsAgain(GameTestHelper helper) {
        FullStopConfig.SERVER.impactDropsFollowTools.set(false);
        drop(helper, 4, EntityType.PIG, Blocks.NETHERRACK, (broken, items) -> {
            FullStopConfig.SERVER.impactDropsFollowTools.set(true);
            helper.assertTrue(broken > 0, "the pig's 30-block fall broke no netherrack");
            helper.assertTrue(items > 0, "with the switch off " + broken + " netherrack broken by a fall dropped nothing");
        });
    }

    // ------------------------------------------------------------------
    // Rigs
    // ------------------------------------------------------------------

    private interface Check {
        void accept(int broken, int items);
    }

    /**
     * A pad of {@code block} (11x11, three deep) in lane {@code lane}, a body of {@code type} dropped {@link #DROP} blocks
     * onto its middle; once it has landed, the blocks gone from the pad and the items lying round it go to {@code check}.
     */
    private static void drop(GameTestHelper helper, int lane, EntityType<? extends Mob> type, Block block, Check check) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, lane);
        BlockPos min = o.offset(-PAD, -3, -PAD), max = o.offset(PAD, -1, PAD);
        fill(level, min, max, block);
        fill(level, o.offset(-PAD, 0, -PAD), o.offset(PAD, 4, PAD), Blocks.AIR);
        int before = count(level, min, max, block);
        LivingEntity[] body = new LivingEntity[1];
        AABB around = new AABB(min, max.offset(1, 8, 1)).inflate(4.0);
        whenTicking(helper, o)
                .thenExecute(() -> {
                    for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, around)) item.discard();
                    body[0] = body(level, type, new Vec3(o.getX() + 0.5, o.getY() + DROP, o.getZ() + 0.5));
                })
                .thenWaitUntil(() -> helper.assertTrue(body[0] == null || !body[0].isAlive() || body[0].onGround(),
                        "the body is still falling"))
                .thenExecuteAfter(20, () -> {
                    int broken = before - count(level, min, max, block);
                    int items = 0;
                    for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, around)) items += item.getItem().getCount();
                    String name = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block).getPath();
                    LOGGER.info("[FullStop gametest] a {} dropped {} blocks onto {} (drops follow tools: {}): {} broken, {} items dropped, {} health lost",
                            type.getDescriptionId(), (int) DROP, name, FullStopConfig.SERVER.impactDropsFollowTools.get(),
                            broken, items, String.format(Locale.ROOT, "%.1f", body[0].getMaxHealth() - body[0].getHealth()));
                    check.accept(broken, items);
                    for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, around)) item.discard();
                    body[0].discard();
                })
                .thenSucceed();
    }

    private static int count(ServerLevel level, BlockPos min, BlockPos max, Block block) {
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.getBlockState(pos).is(block)) n++;
        }
        return n;
    }

    /** A lane of its own for each rig, clear of the other suites' lanes (theirs: 4096 + 3072 + n * 256, n up to 270). */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        ServerLevel level = helper.getLevel();
        BlockPos plot = helper.absolutePos(BlockPos.ZERO);
        int groundTop = level.getMinBuildHeight() + 4;
        BlockPos o = new BlockPos(plot.getX() + 8, groundTop, plot.getZ() + 4096 + 3072 + (290 + lane) * 256);
        for (int cx = (o.getX() - 16) >> 4; cx <= (o.getX() + 16) >> 4; cx++) {
            for (int cz = (o.getZ() - 16) >> 4; cz <= (o.getZ() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    private static void fill(ServerLevel level, BlockPos from, BlockPos to, Block block) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static GameTestSequence whenTicking(GameTestHelper helper, BlockPos o) {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                helper.getLevel().isPositionEntityTicking(o.above(10)) && helper.getLevel().isPositionEntityTicking(o.above(40)),
                "the lane doesn't tick entities yet"));
    }

    private static LivingEntity body(ServerLevel level, EntityType<? extends Mob> type, Vec3 at) {
        Mob mob = type.create(level);
        if (mob == null) throw new IllegalStateException("no " + type);
        mob.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        mob.goalSelector.removeAllGoals(g -> true);
        mob.setPersistenceRequired();
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10000.0);
        mob.setHealth(10000.0f);
        level.addFreshEntity(mob);
        return mob;
    }
}
