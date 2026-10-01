package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.camacraft.camafy.compat.vs.ShipForces;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.world.ServerShipWorld;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;

import java.util.Set;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.Rig;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fill;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fillSet;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fmt;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.report;

/**
 * Ships scaled down ({@code /vs scale}) meeting the ground. The owner's report: a one-block ship at 0.25 dropped
 * on the ground "started rolling, then picked up speed exponentially until it launched away" (with a sonic boom),
 * and a bigger one took the player 60 million blocks away. VS scales a ship's lengths but builds its inertia from
 * its blocks in shipyard units, so a push sized by the unscaled moments was sixteen times what a quarter-size ship
 * answers to, and every contact spun it up more ({@code ShipInertia}, {@code ShipImpulses}). These drop scaled
 * ships and demand that they settle: bounded speeds, no launch, at rest near where they fell.
 *
 * <p>Compiled only with {@code -PshipTests} (real VS ships); the scale goes through VS core's own
 * {@code scaleShip}, by reflection (its interface isn't on the test classpath).</p>
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipScaleTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final int TIMEOUT = ShipCollisionTests.TIMEOUT;
    /** Ticks a dropped ship is watched. */
    private static final int WATCH = 300;
    /** The fastest a ship dropped six blocks may ever go (m/s): the fall itself is 11; a bounce never adds to it. */
    private static final double MAX_SPEED = 25.0;
    /** How far (blocks) from where it fell a settled ship may lie. */
    private static final double MAX_DRIFT = 4.0;
    /** How far (blocks) a ship set rolling at 4 m/s may get before it stops. */
    private static final double MAX_ROLL = 24.0;
    /** At rest: slower than this (m/s) at the end. */
    private static final double REST_SPEED = 1.5;

    private ShipScaleTests() {
    }

    /** A single oak plank at a quarter size, dropped six blocks onto stone: it lands and stays. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void aQuarterScaleBlockDroppedOnStoneSettles(GameTestHelper helper) {
        drop(helper, 0, Blocks.STONE, 1, 0.0, "a quarter-scale block on stone");
    }

    /**
     * The same block onto slime: Full Stop's slime bounce (0.9 of the speed it met), which sized its push by the
     * blocks' mass (64 times the body's at a quarter size) and launched the ship at 173 m/s. The bounce it gives now
     * is the one it asks for: the ship leaves at about 0.9 of the speed it landed at, never more than 1.3 of it.
     * (What the ship does after that is Valkyrien Skies': its own contacts and free flight gain energy for a body this
     * small - a quarter-scale ship's spin grows in the air with nothing touching it - so this test judges the bounce,
     * not the flight.)
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void aQuarterScaleBlockOnSlimeGetsTheBounceItAsksFor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 81 * 256);
        fill(level, o.offset(-6, 0, -6), o.offset(6, 0, 6), Blocks.SLIME_BLOCK.defaultBlockState());
        Set<BlockPos> blocks = fillSet(level, o.offset(0, 7, 0), o.offset(0, 7, 0), Blocks.OAK_PLANKS.defaultBlockState());
        Rig rig = Rig.build(helper, blocks, Blocks.OAK_PLANKS, false);
        if (rig == null) return;
        double restitution = net.camacraft.fullstop.FullStopConfig.SERVER.slimeRestitution.get();
        double[] landed = {0.0};
        double[] left = {Double.NaN};
        int[] afterTurn = {-1};
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> scale(level, rig.ship, 0.25))
                .thenExecuteAfter(5, rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenWaitUntil(() -> {
                    // the fastest it fell, then the fastest it rose in the six ticks after it turned round
                    Vec3 v = rig.ref.velocity();
                    if (v != null) {
                        if (afterTurn[0] < 0) {
                            landed[0] = Math.max(landed[0], -v.y);
                            if (v.y > 0.5 && landed[0] > 5.0) afterTurn[0] = 0;
                        } else {
                            afterTurn[0]++;
                            left[0] = Double.isNaN(left[0]) ? v.y : Math.max(left[0], v.y);
                        }
                    }
                    helper.assertTrue(afterTurn[0] >= 6, "waiting for the bounce");
                })
                .thenExecute(() -> {
                    rig.pin();
                    double share = left[0] / landed[0];
                    report("a quarter-scale block on slime", "landed at " + fmt(landed[0]) + " m/s, left at " + fmt(left[0]) + " m/s ("
                            + fmt(share) + " of it; Full Stop asks " + fmt(restitution) + ") | " + rig.summary());
                    helper.assertTrue(share <= 1.3, "the bounce sent it off at " + fmt(left[0]) + " m/s, " + fmt(share)
                            + " times the speed it landed at (a push sized for the blocks' mass, not the body's)");
                    helper.assertTrue(share >= 0.5, "the ship hardly bounced: " + fmt(share) + " of its landing speed");
                    helper.succeed();
                });
    }

    /** A 3x1x3 plank raft at a quarter size, dropped onto stone: it lands and stays. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void aQuarterScaleRaftDroppedOnStoneSettles(GameTestHelper helper) {
        drop(helper, 2, Blocks.STONE, 3, 0.0, "a quarter-scale raft on stone");
    }

    /**
     * Each scenario in its own lane of fresh chunks (see {@link ShipCollisionTests#origin}); {@code roll} (m/s) sets
     * the ship rolling along x once it has landed (by its physical mass: a push sized by its blocks' mass would be
     * 64 times too strong at a quarter size).
     */
    private static void drop(GameTestHelper helper, int lane, Block floorBlock, int size, double roll, String what) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (80 + lane) * 256);
        fill(level, o.offset(-6, 0, -6), o.offset(6, 0, 6), floorBlock == Blocks.GRASS_BLOCK ? Blocks.DIRT.defaultBlockState()
                : floorBlock.defaultBlockState());
        if (floorBlock == Blocks.GRASS_BLOCK) {
            // a grass field, room to roll: grass over dirt over stone
            fill(level, o.offset(-6, -1, -6), o.offset(30, -1, 6), Blocks.STONE.defaultBlockState());
            fill(level, o.offset(-6, 0, -6), o.offset(30, 0, 6), Blocks.GRASS_BLOCK.defaultBlockState());
        }
        Set<BlockPos> blocks = fillSet(level, o.offset(0, 7, 0), o.offset(size - 1, 7, size - 1), Blocks.OAK_PLANKS.defaultBlockState());
        Rig rig = Rig.build(helper, blocks, Blocks.OAK_PLANKS, false);
        if (rig == null) return;
        double floorTop = o.getY() + 1.0;
        double[] fastest = {0.0};
        double[] fastestSpin = {0.0};
        double[] apex = {Double.NEGATIVE_INFINITY};
        boolean[] landed = {false};
        Vec3[] start = {null};
        Runnable watch = () -> {
            Vec3 v = rig.ref.velocity();
            Vec3 w = rig.ref.angularVelocity();
            Vec3 at = rig.ref.position();
            if (v != null) fastest[0] = Math.max(fastest[0], v.length());
            if (w != null) fastestSpin[0] = Math.max(fastestSpin[0], w.length());
            // the highest it rose after first touching the floor: a bounce never higher than the fall
            if (at != null && at.y < floorTop + 1.0) landed[0] = true;
            if (landed[0] && at != null) apex[0] = Math.max(apex[0], at.y - floorTop);
        };
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> {
                    scale(level, rig.ship, 0.25);
                    LOGGER.info("[FullStop ship test] ship {} scaled to {} (reads {})", rig.ship.getId(), 0.25, rig.ref.scale());
                })
                .thenExecuteAfter(5, () -> {
                    helper.assertTrue(Math.abs(rig.ref.scale() - 0.25) < 0.01, "the ship didn't take the scale: " + rig.ref.scale());
                    start[0] = rig.ref.position();
                    rig.release();
                })
                .thenWaitUntil(rig::demandSimulated)
                .thenExecuteFor(40, watch)
                .thenExecute(() -> {
                    if (roll <= 0.0) return;
                    Vec3 v = rig.ref.velocity();
                    double mass = rig.ref.physicalMass();
                    if (v != null && mass > 0.0) {
                        ShipForces.applyImpulse(level, rig.ship.getId(), new Vec3(roll - v.x, 0.0, 0.0).scale(mass), null);
                    }
                    LOGGER.info("[FullStop ship test] ship {} ({} kg physical, {} kg of blocks) set rolling at {} m/s", rig.ship.getId(),
                            fmt(mass), fmt(rig.ref.mass()), fmt(roll));
                })
                .thenExecuteFor(WATCH, watch)
                .thenExecute(() -> {
                    Vec3 end = rig.ref.position();
                    Vec3 v = rig.ref.velocity();
                    double speed = v == null ? Double.NaN : v.length();
                    double drift = end == null || start[0] == null ? Double.NaN
                            : Math.hypot(end.x - start[0].x, end.z - start[0].z);
                    double height = end == null ? Double.NaN : end.y - floorTop;
                    rig.pin();
                    report(what, "fastest " + fmt(fastest[0]) + " m/s, fastest spin " + fmt(fastestSpin[0]) + " rad/s, rose at most "
                            + fmt(apex[0]) + " blocks after landing (fell " + fmt(start[0] == null ? Double.NaN : start[0].y - floorTop)
                            + "), drifted " + fmt(drift) + " blocks, " + fmt(height) + " above the floor, " + fmt(speed)
                            + " m/s at the end | " + rig.summary());
                    double fell = start[0] == null ? 6.0 : start[0].y - floorTop;
                    helper.assertTrue(fastest[0] < MAX_SPEED, "the ship reached " + fmt(fastest[0]) + " m/s (a launch)");
                    helper.assertTrue(apex[0] <= fell + 0.5, "the ship rose " + fmt(apex[0]) + " blocks after landing, higher than its "
                            + fmt(fell) + "-block fall");
                    helper.assertTrue(drift < (roll > 0.0 ? MAX_ROLL : MAX_DRIFT), "the ship ended " + fmt(drift) + " blocks from where it fell");
                    helper.assertTrue(height > -1.0 && height < fell + 0.5, "the ship ended " + fmt(height) + " above the floor");
                    if (floorBlock != Blocks.SLIME_BLOCK) {
                        // slime keeps a light body bouncing (0.9 a bounce) for longer than the watch
                        helper.assertTrue(speed < REST_SPEED, "the ship is still moving at " + fmt(speed) + " m/s");
                    }
                    helper.succeed();
                });
    }

    /** VS core's own scaling ({@code /vs scale}), by reflection: {@code VsiCore} isn't on the test classpath. */
    static void scale(ServerLevel level, ServerShip ship, double scale) {
        try {
            Object core = ValkyrienSkiesMod.getVsCore();
            ServerShipWorld world = ValkyrienSkies.api().getServerShipWorld(level.getServer());
            core.getClass().getMethod("scaleShip", ServerShipWorld.class, ServerShip.class, double.class).invoke(core, world, ship, scale);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't scale the ship through VS core", e);
        }
    }
}
