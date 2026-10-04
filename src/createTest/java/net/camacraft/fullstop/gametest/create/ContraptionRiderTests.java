package net.camacraft.fullstop.gametest.create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionRiders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Bodies standing on a contraption turning about the vertical turn with it ({@code contraptionRidersTurn}): a pig on
 * a 5x5 plate a bearing turns at 16 RPM for 60 ticks turns its facing by the plate's turn (the opposite sign: Create's
 * +angle turns a facing by -angle). Opt-in: {@code gradlew runGameTestServer -Pcreate}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ContraptionRiderTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "create_flat_48x8x48";

    private ContraptionRiderTests() {
    }

    @GameTest(template = FLAT, batch = "fullstop_create_riders", timeoutTicks = 400)
    public static void aPigOnATurningPlateTurnsWithIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int groundTop = level.getMinBuildHeight() + 4;
        BlockPos o = new BlockPos(-20000 - 70 * 96, groundTop, 20000);
        for (int cx = (o.getX() - 16) >> 4; cx <= (o.getX() + 16) >> 4; cx++) {
            for (int cz = (o.getZ() - 16) >> 4; cz <= (o.getZ() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        fill(level, o.offset(-7, 0, -7), o.offset(7, 5, 7), Blocks.AIR.defaultBlockState());
        fill(level, o.offset(-7, 1, -7), o.offset(7, 1, 7), Blocks.STONE.defaultBlockState());
        level.setBlock(o, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), Block.UPDATE_ALL);
        BlockPos bearing = o.above();
        level.setBlock(bearing, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP),
                Block.UPDATE_ALL);
        speed(level, o, 0);
        BlockPos base = bearing.above();
        fill(level, base.offset(-2, 0, -2), base.offset(2, 0, 2), Blocks.OAK_PLANKS.defaultBlockState());
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(base.offset(-2, 0, -2)).minmax(new AABB(base.offset(2, 0, 2)))));
        Mob[] pig = new Mob[1];
        double[] pigTurn = {0.0};
        double[] plateTurn = {0.0};
        float[] last = {Float.NaN, Float.NaN};
        int[] seen = new int[3]; // ticks: colliding, standing, both
        StringBuilder odd = new StringBuilder();
        boolean[] watching = {false};
        helper.onEachTick(() -> {
            if (!watching[0] || pig[0] == null) return;
            ControlledContraptionEntity c = plate(level, base);
            if (c == null) return;
            float yaw = pig[0].getYRot();
            float angle = c.getAngle(1.0f);
            if (!Float.isNaN(last[0])) {
                float dy = Mth.wrapDegrees(yaw - last[0]);
                float da = Mth.wrapDegrees(angle - last[1]);
                pigTurn[0] += dy;
                plateTurn[0] += da;
                if (Math.abs(dy + da) > 0.5f && odd.length() < 600) {
                    odd.append(String.format(Locale.ROOT, " t%d pig %.2f plate %.2f at %s;", level.getGameTime(), dy, da, pig[0].position()));
                }
            }
            last[0] = yaw;
            last[1] = angle;
            boolean colliding = c.collidingEntities.containsKey(pig[0]);
            boolean standing = ContraptionRiders.standsOn(c, pig[0], 1.0f);
            if (colliding) seen[0]++;
            if (standing) seen[1]++;
            if (colliding && standing) seen[2]++;
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(base), "the lane doesn't tick entities yet"))
                .thenExecute(() -> {
                    Mob p = EntityType.PIG.create(level);
                    if (p == null) throw new IllegalStateException("no pig");
                    p.moveTo(base.getX() - 0.5, base.getY() + 1.0, base.getZ() + 0.5, 0.0f, 0.0f);
                    p.setNoAi(true);
                    p.setPersistenceRequired();
                    level.addFreshEntity(p);
                    pig[0] = p;
                })
                // a fresh test lane's entity sections take a while to answer world queries (Create's collider and FullStop
                // ask them); in play the ground round a player is long loaded
                .thenWaitUntil(() -> helper.assertTrue(level.getEntitiesOfClass(Mob.class, new AABB(base).inflate(3.0)).contains(pig[0]),
                        "the pig isn't visible to world queries yet"))
                .thenIdle(5)
                .thenExecute(() -> speed(level, o, 16))
                .thenIdle(10)
                .thenExecute(() -> watching[0] = true)
                .thenIdle(60)
                .thenExecute(() -> {
                    watching[0] = false;
                    String out = String.format(Locale.ROOT, "pig turned %.1f degrees, the plate %.1f; over 60 ticks the pig was in Create's "
                            + "colliding list %d, standing on the plate %d, both %d; ticks off:%s", pigTurn[0], plateTurn[0], seen[0], seen[1], seen[2], odd);
                    LOGGER.info("[FullStop gametest] riders: {}", out);
                    speed(level, o, 0);
                    helper.assertTrue(Math.abs(plateTurn[0]) > 100.0, "the plate hardly turned: " + out);
                    helper.assertTrue(Math.abs(pigTurn[0] + plateTurn[0]) < 0.1 * Math.abs(plateTurn[0]),
                            "the pig didn't turn with the plate: " + out);
                })
                .thenSucceed();
    }

    private static ControlledContraptionEntity plate(ServerLevel level, BlockPos base) {
        for (AbstractContraptionEntity c : level.getEntitiesOfClass(AbstractContraptionEntity.class, new AABB(base).inflate(4.0))) {
            if (c instanceof ControlledContraptionEntity controlled && c.isAlive()) return controlled;
        }
        return null;
    }

    private static void speed(ServerLevel level, BlockPos motor, int rpm) {
        if (level.getBlockEntity(motor) instanceof CreativeMotorBlockEntity be) be.generatedSpeed.setValue(rpm);
    }

    private static void fill(ServerLevel level, BlockPos from, BlockPos to, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) level.setBlock(pos, state, Block.UPDATE_ALL);
    }
}
