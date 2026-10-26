package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.physics.rules.PointedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.Rig;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fillSet;

/**
 * Pointed blocks from data ({@code PointedBlocks}, {@code data/fullstop/fullstop/pointed_blocks}): the owner's
 * "Moving faster into the spikes should deal more damage, same as a ship with spikes moving faster when hitting an
 * entity" (2026-10-24).
 *
 * <ul>
 *   <li>The rules load: pointed dripstone is pointed (its {@code vertical_direction}), and the rule for Supplementaries'
 *   bamboo spikes is skipped without error where Supplementaries isn't (this dev run).</li>
 *   <li>Pigs (1000 health) dropped 6 and 16 blocks onto a 3x3 field of upward dripstone, and 16 onto stone: the
 *   higher fall onto the points hurts more than the lower one and more than the same fall onto stone.</li>
 *   <li>A floating 3x3x3 stone ship with an upward dripstone on its top pushed up at 12 and at 25 m/s into a pig
 *   hovering above it, and a plain stone ship at 25: the point hurts more than the plain hull, and more at 25 than
 *   at 12.</li>
 * </ul>
 * Opt-in with the rest of Full Stop's game tests: {@code gradlew runGameTestServer -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class PointedBlockTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_pointed";
    private static final float HEALTH = 1000.0f;

    private PointedBlockTests() {
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 100)
    public static void theDripstoneRuleLoadsAndTheSpikesRuleIsSkippedWithoutSupplementaries(GameTestHelper helper) {
        BlockState up = upwardDripstone();
        PointedBlocks.Rule rule = PointedBlocks.ruleFor(up);
        helper.assertTrue(rule != null, "pointed dripstone has no pointed rule");
        helper.assertTrue(rule.tip(up) == Direction.UP, "the upward dripstone's point reads " + rule.tip(up));
        helper.assertTrue(PointedBlocks.ruleFor(Blocks.STONE.defaultBlockState()) == null, "stone is pointed");
        helper.assertTrue(!ForgeRegistries.BLOCKS.containsKey(new ResourceLocation("supplementaries", "bamboo_spikes"))
                        || PointedBlocks.ruleFor(ForgeRegistries.BLOCKS.getValue(new ResourceLocation("supplementaries", "bamboo_spikes"))
                        .defaultBlockState()) != null,
                "Supplementaries is here but its spikes aren't pointed");
        helper.succeed();
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 600)
    public static void aFasterFallOntoUpwardDripstoneHurtsMore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos low = lane(helper, 0), high = lane(helper, 1), stone = lane(helper, 2);
        field(level, low, true);
        field(level, high, true);
        field(level, stone, false);
        LivingEntity[] pigs = new LivingEntity[3];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(level, low) && ticking(level, high) && ticking(level, stone),
                        "the lanes don't tick entities yet"))
                .thenExecute(() -> {
                    pigs[0] = pig(level, new Vec3(low.getX() + 0.5, low.getY() + 1 + 6.0, low.getZ() + 0.5), false);
                    pigs[1] = pig(level, new Vec3(high.getX() + 0.5, high.getY() + 1 + 16.0, high.getZ() + 0.5), false);
                    pigs[2] = pig(level, new Vec3(stone.getX() + 0.5, stone.getY() + 1 + 16.0, stone.getZ() + 0.5), false);
                })
                .thenWaitUntil(() -> helper.assertTrue(landed(pigs[0], low) && landed(pigs[1], high) && landed(pigs[2], stone),
                        "the pigs haven't landed"))
                .thenExecuteAfter(20, () -> {
                    double six = lost(pigs[0]), sixteen = lost(pigs[1]), plain = lost(pigs[2]);
                    String out = String.format(Locale.ROOT, "6 blocks onto dripstone %.1f, 16 onto dripstone %.1f, 16 onto stone %.1f",
                            six, sixteen, plain);
                    LOGGER.info("[FullStop gametest] falls onto upward dripstone: {}", out);
                    helper.assertTrue(sixteen > six, "a faster fall onto the points didn't hurt more: " + out);
                    helper.assertTrue(sixteen > plain, "the points hurt no more than stone: " + out);
                    for (LivingEntity p : pigs) p.discard();
                })
                .thenSucceed();
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 600)
    public static void aShipsPointHurtsMoreThanItsHullAndMoreTheFasterItStrikes(GameTestHelper helper) {
        Side slow = new Side(helper, 0, true, 12.0);
        Side fast = new Side(helper, 1, true, 25.0);
        Side plain = new Side(helper, 2, false, 25.0);
        if (slow.rig == null || fast.rig == null || plain.rig == null) return;
        Side[] sides = {slow, fast, plain};
        helper.startSequence()
                .thenWaitUntil(() -> {
                    for (Side s : sides) s.rig.demandReady();
                })
                .thenExecute(() -> {
                    for (Side s : sides) {
                        s.spawnPig();
                        s.rig.release();
                    }
                })
                .thenWaitUntil(() -> {
                    for (Side s : sides) s.rig.demandSimulated();
                })
                .thenExecute(() -> {
                    for (Side s : sides) s.rig.push(new Vec3(0.0, s.speed, 0.0));
                })
                .thenExecuteAfter(30, () -> {
                    double a = HEALTH - slow.pig.getHealth(), b = HEALTH - fast.pig.getHealth(), c = HEALTH - plain.pig.getHealth();
                    for (Side s : sides) s.rig.pin();
                    String out = String.format(Locale.ROOT, "point at 12 m/s %.1f, point at 25 m/s %.1f, plain stone at 25 m/s %.1f", a, b, c);
                    LOGGER.info("[FullStop ship test] ship points into hovering pigs: {}", out);
                    helper.assertTrue(b > a, "a faster point didn't hurt more: " + out);
                    helper.assertTrue(b > c, "the point hurt no more than the plain hull: " + out);
                    for (Side s : sides) s.pig.discard();
                    helper.succeed();
                });
    }

    /** One floating 3x3x3 stone ship, with an upward dripstone on top or without, and the pig hovering above it. */
    private static final class Side {
        final ServerLevel level;
        final BlockPos o;
        final Rig rig;
        final double speed;
        Mob pig;

        Side(GameTestHelper helper, int lane, boolean point, double speed) {
            this.level = helper.getLevel();
            this.speed = speed;
            this.o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (230 + lane) * 256);
            for (int cx = (o.getX() - 16) >> 4; cx <= (o.getX() + 32) >> 4; cx++) {
                for (int cz = (o.getZ() - 16) >> 4; cz <= (o.getZ() + 32) >> 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            }
            Set<BlockPos> blocks = new LinkedHashSet<>(fillSet(level, o.offset(0, 1, 0), o.offset(2, 3, 2), Blocks.STONE.defaultBlockState()));
            BlockPos top = o.offset(1, 4, 1);
            level.setBlock(top, point ? upwardDripstone() : Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            blocks.add(top);
            this.rig = Rig.build(helper, blocks, Blocks.STONE, true, point ? 27 : 28);
            if (rig != null) helper.onEachTick(rig::record);
        }

        void spawnPig() {
            pig = pig(level, new Vec3(o.getX() + 1.5, o.getY() + 8.0, o.getZ() + 1.5), true);
        }
    }

    // ------------------------------------------------------------------
    // Rigs
    // ------------------------------------------------------------------

    private static BlockState upwardDripstone() {
        return Blocks.POINTED_DRIPSTONE.defaultBlockState()
                .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.UP)
                .setValue(PointedDripstoneBlock.THICKNESS, DripstoneThickness.TIP);
    }

    /** A stone floor with a 3x3 field of upward dripstone on it (or bare stone). */
    private static void field(ServerLevel level, BlockPos o, boolean points) {
        for (BlockPos pos : BlockPos.betweenClosed(o.offset(-2, -1, -2), o.offset(2, -1, 2))) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        if (!points) return;
        for (BlockPos pos : BlockPos.betweenClosed(o.offset(-1, 0, -1), o.offset(1, 0, 1))) {
            level.setBlock(pos, upwardDripstone(), Block.UPDATE_CLIENTS);
        }
    }

    /** A lane of its own, clear of the other suites' (WaterLandingTests use 180-184). */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        ServerLevel level = helper.getLevel();
        BlockPos plot = helper.absolutePos(BlockPos.ZERO);
        int groundTop = level.getMinBuildHeight() + 4;
        BlockPos o = new BlockPos(plot.getX() + 8, groundTop, plot.getZ() + 4096 + 3072 + (240 + lane) * 256);
        for (int cx = (o.getX() - 8) >> 4; cx <= (o.getX() + 16) >> 4; cx++) {
            for (int cz = (o.getZ() - 8) >> 4; cz <= (o.getZ() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    private static boolean ticking(ServerLevel level, BlockPos o) {
        return level.isPositionEntityTicking(o.above(2)) && level.isPositionEntityTicking(o.above(20));
    }

    private static boolean landed(LivingEntity pig, BlockPos o) {
        return pig == null || !pig.isAlive() || (pig.onGround() && pig.getY() < o.getY() + 1.5);
    }

    private static double lost(LivingEntity pig) {
        return pig == null ? 0.0 : pig.getMaxHealth() - pig.getHealth();
    }

    private static Mob pig(ServerLevel level, Vec3 at, boolean hover) {
        Mob pig = EntityType.PIG.create(level);
        if (pig == null) throw new IllegalStateException("no pig");
        pig.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        pig.goalSelector.removeAllGoals(g -> true);
        if (hover) {
            pig.setNoGravity(true);
            pig.setNoAi(true);
        }
        pig.setPersistenceRequired();
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTH);
        pig.setHealth(HEALTH);
        level.addFreshEntity(pig);
        return pig;
    }
}
