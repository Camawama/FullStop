package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/**
 * Buttons and what may press them by running into them ({@code kineticButtonPressing}). Vanilla lets
 * an arrow press a wooden button and never a stone one; FullStop keeps a projectile it flings at a
 * button to that rule (measured before: an arrow at 30 m/s pressed a stone button), while a body -
 * a pig, a player - presses a stone button by crashing into it as before.
 *
 * <p>Opt-in with the rest of FullStop's game tests: {@code gradlew runGameTestServer -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ButtonPressTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_buttons";
    private static final int TIMEOUT = 300;
    /** The wall the button is on, this far along +x from the lane's origin; the button sits on its -x face. */
    private static final int WALL_X = 6;
    /** Ticks a stone button stays pressed (20; a wooden one 30): the check must come sooner. */
    private static final int STONE_PRESS_TICKS = 20;

    private ButtonPressTests() {
    }

    /** An arrow at 30 m/s into a stone button on a wall: the button is not pressed, by FullStop or by anyone. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void anArrowFlungIntoAStoneButtonDoesNotPressIt(GameTestHelper helper) {
        BlockPos o = lane(helper, 0);
        ServerLevel level = helper.getLevel();
        BlockPos button = rig(level, o, Blocks.STONE_BUTTON);
        Entity[] arrow = new Entity[1];
        boolean[] everPressed = new boolean[1];
        whenTicking(helper, o)
                .thenExecute(() -> {
                    arrow[0] = arrow(level, air(o, 0.0, 1.5, 0.0), new Vec3(1.5, 0.0, 0.0));
                    helper.onEachTick(() -> {
                        if (powered(level, button)) everPressed[0] = true;
                    });
                })
                .thenWaitUntil(() -> helper.assertTrue(arrived(arrow[0], o), "the arrow hasn't reached the wall"))
                .thenExecuteAfter(5, () -> {
                    report("an arrow at 30 m/s into a stone button", "pressed at any point: " + everPressed[0]);
                    helper.assertFalse(everPressed[0], "a projectile pressed a stone button");
                    arrow[0].discard();
                })
                .thenSucceed();
    }

    /** The same arrow into an oak button: pressed (vanilla's own rule, which FullStop's press keeps to). */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void anArrowFlungIntoAnOakButtonPressesIt(GameTestHelper helper) {
        BlockPos o = lane(helper, 1);
        ServerLevel level = helper.getLevel();
        BlockPos button = rig(level, o, Blocks.OAK_BUTTON);
        Entity[] arrow = new Entity[1];
        whenTicking(helper, o)
                .thenExecute(() -> arrow[0] = arrow(level, air(o, 0.0, 1.5, 0.0), new Vec3(1.5, 0.0, 0.0)))
                .thenWaitUntil(() -> helper.assertTrue(powered(level, button), "the oak button isn't pressed yet"))
                .thenExecute(() -> {
                    report("an arrow at 30 m/s into an oak button", "pressed");
                    arrow[0].discard();
                })
                .thenSucceed();
    }

    /** A pig flung at 20 m/s into a stone button on a wall: a body presses it (FullStop's kinetic press). */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigFlungIntoAStoneButtonPressesIt(GameTestHelper helper) {
        BlockPos o = lane(helper, 2);
        ServerLevel level = helper.getLevel();
        BlockPos button = rig(level, o, Blocks.STONE_BUTTON);
        Entity[] pig = new Entity[1];
        whenTicking(helper, o)
                .thenExecute(() -> pig[0] = floatingPig(level, air(o, 0.0, 1.0, 0.0), 20.0))
                .thenWaitUntil(() -> helper.assertTrue(powered(level, button), "the stone button isn't pressed yet"))
                .thenExecute(() -> {
                    report("a pig at 20 m/s into a stone button", "pressed");
                    pig[0].discard();
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // Rigs
    // ------------------------------------------------------------------

    /** A lane of its own for each test, clear of the other tests' lanes. */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        return ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (150 + lane) * 256);
    }

    private static Vec3 air(BlockPos o, double dx, double dy, double dz) {
        return new Vec3(o.getX() + 0.5 + dx, o.getY() + dy, o.getZ() + 0.5 + dz);
    }

    /** A sequence that starts once the lane ticks entities (the test sees nothing move before that). */
    private static GameTestSequence whenTicking(GameTestHelper helper, BlockPos o) {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                helper.getLevel().isPositionEntityTicking(o.above(2)) && helper.getLevel().isPositionEntityTicking(o.offset(WALL_X, 2, 0)),
                "the lane doesn't tick entities yet"));
    }

    /**
     * A stone wall three high at {@link #WALL_X}, with {@code button} on its -x face one block up, facing the
     * lane's origin (a wall button, WEST). Returns the button's position.
     */
    private static BlockPos rig(ServerLevel level, BlockPos o, Block button) {
        BlockPos wall = o.offset(WALL_X, 0, 0);
        ShipCollisionTests.fill(level, wall.offset(0, 0, -1), wall.offset(1, 3, 1), Blocks.STONE.defaultBlockState());
        BlockPos at = o.offset(WALL_X - 1, 1, 0);
        BlockState state = button.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.POWERED, false);
        level.setBlock(at, state, Block.UPDATE_ALL);
        return at;
    }

    private static boolean powered(ServerLevel level, BlockPos button) {
        BlockState state = level.getBlockState(button);
        return state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
    }

    /** The arrow is at the wall (stuck, stopped or past the button's block). */
    private static boolean arrived(Entity arrow, BlockPos o) {
        return arrow == null || !arrow.isAlive() || arrow.getX() >= o.getX() + WALL_X - 0.6
                || arrow.getDeltaMovement().lengthSqr() < 1.0e-4;
    }

    /** An arrow with no gravity (so it meets the wall at the button's height), flying at {@code velocityBpt}. */
    private static Arrow arrow(ServerLevel level, Vec3 at, Vec3 velocityBpt) {
        Arrow arrow = new Arrow(level, at.x, at.y, at.z);
        arrow.setNoGravity(true);
        arrow.setDeltaMovement(velocityBpt);
        level.addFreshEntity(arrow);
        return arrow;
    }

    /** A pig with no AI goals and no gravity, flung along +x at {@code vx} m/s. */
    private static Mob floatingPig(ServerLevel level, Vec3 at, double vx) {
        Mob pig = EntityType.PIG.create(level);
        if (pig == null) throw new IllegalStateException("no pig");
        pig.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        pig.goalSelector.removeAllGoals(g -> true);
        pig.setNoGravity(true);
        pig.setPersistenceRequired();
        level.addFreshEntity(pig);
        pig.setDeltaMovement(vx / 20.0, 0.0, 0.0);
        pig.hasImpulse = true;
        return pig;
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }
}
