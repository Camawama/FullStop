package net.camacraft.fullstop.gametest.create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionImpacts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * A soft block strikes softly ({@code softStrikeDamage}): a bearing arm of wool and one of iron, side by side at the
 * same speed, sweep into the same kind of body (an iron golem, a pig; 1000 health so the first hit can be read). The
 * wool arm still shoves the body (it is carried along at the arm's speed) but deals a tenth of the damage, where the
 * owner saw a fast wool arm kill an iron golem. Opt-in: {@code gradlew runGameTestServer -Pcreate}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class SoftStrikeTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "create_flat_48x8x48";
    private static final String BATCH = "fullstop_create_soft";
    private static final int TIMEOUT = 500;
    private static final float HEALTH = 1000.0f;
    private static final int ARM = 4;
    private static final double BODY_RADIUS = 3.5;

    private SoftStrikeTests() {
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aWoolArmAt64RpmBarelyHurtsAnIronGolem(GameTestHelper helper) {
        compare(helper, 60, EntityType.IRON_GOLEM, 64);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aWoolArmAt256RpmBarelyHurtsAnIronGolem(GameTestHelper helper) {
        compare(helper, 62, EntityType.IRON_GOLEM, 256);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aWoolArmAt64RpmBarelyHurtsAPig(GameTestHelper helper) {
        compare(helper, 64, EntityType.PIG, 64);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aWoolArmAt256RpmBarelyHurtsAPig(GameTestHelper helper) {
        compare(helper, 66, EntityType.PIG, 256);
    }

    /** A wool arm in lane {@code lane} and an iron one in the next, both at {@code rpm}, each into a fresh {@code type}. */
    private static void compare(GameTestHelper helper, int lane, EntityType<? extends Mob> type, int rpm) {
        ServerLevel level = helper.getLevel();
        BlockPos woolMotor = lane(helper, lane);
        BlockPos ironMotor = lane(helper, lane + 1);
        BlockPos woolBearing = rig(level, woolMotor);
        BlockPos ironBearing = rig(level, ironMotor);
        arm(level, woolBearing.above(), Blocks.WHITE_WOOL.defaultBlockState());
        arm(level, ironBearing.above(), Blocks.IRON_BLOCK.defaultBlockState());
        Mob[] body = new Mob[2];
        Vec3[] start = new Vec3[2];
        String what = type.getDescription().getString() + " at " + rpm + " RPM";
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(woolMotor.above(2))
                        && level.isPositionEntityTicking(ironMotor.above(2)), "the lanes don't tick entities yet"))
                .thenExecute(() -> {
                    start[0] = spot(woolBearing.above());
                    start[1] = spot(ironBearing.above());
                    body[0] = body(level, type, start[0]);
                    body[1] = body(level, type, start[1]);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    speed(level, woolMotor, rpm);
                    speed(level, ironMotor, rpm);
                })
                .thenWaitUntil(() -> helper.assertTrue(ContraptionImpacts.lastHit(body[0]) != null
                        && ContraptionImpacts.lastHit(body[1]) != null, what + ": both arms haven't struck yet"))
                .thenIdle(3)
                .thenExecute(() -> {
                    ContraptionImpacts.Struck wool = ContraptionImpacts.lastHit(body[0]);
                    ContraptionImpacts.Struck iron = ContraptionImpacts.lastHit(body[1]);
                    double woolHurt = HEALTH - body[0].getHealth();
                    double ironHurt = HEALTH - body[1].getHealth();
                    double woolPush = wool.pushMps().length();
                    double ironPush = iron.pushMps().length();
                    String out = String.format(Locale.ROOT, "wool arm: closing %.1f m/s, damage %.1f, pushed %.1f m/s; iron arm: closing "
                                    + "%.1f m/s, damage %.1f, pushed %.1f m/s", wool.approach(), woolHurt, woolPush, iron.approach(), ironHurt,
                            ironPush);
                    LOGGER.info("[FullStop gametest] soft strike, {}: {}", what, out);
                    // the wool arm shoves (a strike, carried along) but hurts a tenth or less of what iron does
                    helper.assertTrue(woolPush > 0.5 * ironPush, what + ": the wool arm hardly shoved: " + out);
                    helper.assertTrue(woolHurt <= Math.max(1.0, 0.15 * ironHurt), what + ": the wool arm hurt too much: " + out);
                    if (rpm >= 256) helper.assertTrue(ironHurt > 10.0, what + ": the iron arm should still hurt hard: " + out);
                    speed(level, woolMotor, 0);
                    speed(level, ironMotor, 0);
                })
                .thenSucceed();
    }

    private static Vec3 spot(BlockPos armBase) {
        return new Vec3(armBase.getX() + 0.5, armBase.getY(), armBase.getZ() + 0.5 + BODY_RADIUS);
    }

    private static BlockPos rig(ServerLevel level, BlockPos o) {
        fill(level, o.offset(-7, 0, -7), o.offset(7, 5, 7), Blocks.AIR.defaultBlockState());
        fill(level, o.offset(-7, 1, -7), o.offset(7, 1, 7), Blocks.STONE.defaultBlockState());
        level.setBlock(o, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), Block.UPDATE_ALL);
        BlockPos bearing = o.above();
        level.setBlock(bearing, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP),
                Block.UPDATE_ALL);
        speed(level, o, 0);
        return bearing;
    }

    private static void arm(ServerLevel level, BlockPos base, BlockState block) {
        fill(level, base, base.offset(ARM, 0, 0), block);
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(base).minmax(new AABB(base.offset(ARM, 0, 0)))));
    }

    private static void speed(ServerLevel level, BlockPos motor, int rpm) {
        if (level.getBlockEntity(motor) instanceof CreativeMotorBlockEntity be) be.generatedSpeed.setValue(rpm);
    }

    private static Mob body(ServerLevel level, EntityType<? extends Mob> type, Vec3 at) {
        Mob mob = type.create(level);
        if (mob == null) throw new IllegalStateException("no " + type);
        mob.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        mob.goalSelector.removeAllGoals(g -> true);
        mob.setPersistenceRequired();
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTH);
        mob.setHealth(HEALTH);
        level.addFreshEntity(mob);
        return mob;
    }

    /** A fixed place per lane, far from every test plot (the Create suites' lanes). */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        ServerLevel level = helper.getLevel();
        int groundTop = level.getMinBuildHeight() + 4;
        BlockPos o = new BlockPos(-20000 - lane * 96, groundTop, 20000);
        for (int cx = (o.getX() - 16) >> 4; cx <= (o.getX() + 16) >> 4; cx++) {
            for (int cz = (o.getZ() - 16) >> 4; cz <= (o.getZ() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    private static void fill(ServerLevel level, BlockPos from, BlockPos to, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) level.setBlock(pos, state, Block.UPDATE_ALL);
    }
}
