package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.camafy.compat.vs.ShipForces;
import net.camacraft.camafy.compat.vs.ShipJoint;
import net.camacraft.camafy.compat.vs.ShipJoints;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.server.physics.ship.ShipGlue;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.Rig;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fill;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fillSet;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fmt;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.report;

/**
 * Slime and honey on real Valkyrien Skies ships (the {@link Rig} of {@link ShipCollisionTests};
 * same opt-in: {@code gradlew runGameTestServer -PshipTests [-PvsBackend=classic|physx|jolt]}).
 *
 * <p><b>Slime.</b> A slime raft dropped on stone, a plank raft dropped on a slime floor, and a
 * slime cube rammed into a slime wall leave it at slimeRestitution of the speed they met it with
 * (0.9; the cube, slime on both sides, at its square root); a plank raft on stone doesn't bounce
 * beyond what VS itself gives back. (How high a raft rises again is reported, not judged: VS's air
 * drag on a flat raft keeps it well under restitution squared of the drop.)
 *
 * <p><b>Honey.</b> A honey raft dropped on stone doesn't bounce and is glued there: a gentle push
 * (a quarter of honeyGlueStrength) doesn't move it, a strong one (four times it) tears it free.
 * Two floating rafts that meet with honey between them are glued and tow each other. A glue whose
 * joint goes (as when its ship unloads) is made again. And Camafy's glue joint itself: it holds a raft
 * hanging from it against 400 kN of pull, and one set to break at 100 kN lets go once the pull has
 * grown by about that much, on every backend (each knows the pull its own way).
 *
 * <p>Everything that depends on how long something takes is timed in real time: VS's physics
 * keeps it, and this server runs far ahead of it.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipSlimeHoneyTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_slime_honey";
    private static final int TIMEOUT = 40_000;
    private static final ResourceLocation PUSH = new ResourceLocation(FullStop.MODID, "gametest_push");

    private ShipSlimeHoneyTests() {
    }

    // ------------------------------------------------------------------
    // Slime
    // ------------------------------------------------------------------

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aSlimeRaftDroppedOnStoneBouncesBack(GameTestHelper helper) {
        drop(helper, "slime raft onto stone", Blocks.SLIME_BLOCK, Blocks.STONE, slimeLow(false), 1.05, false);
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aPlankRaftDroppedOnASlimeFloorBouncesBack(GameTestHelper helper) {
        drop(helper, "plank raft onto a slime floor", Blocks.OAK_PLANKS, Blocks.SLIME_BLOCK, slimeLow(false), 1.05, false);
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aPlankRaftDroppedOnStoneDoesNotBounce(GameTestHelper helper) {
        drop(helper, "plank raft onto stone (control)", Blocks.OAK_PLANKS, Blocks.STONE, -1.0, 0.5, false);
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aHoneyRaftDroppedOnStoneSticks(GameTestHelper helper) {
        drop(helper, "honey raft onto stone", Blocks.HONEY_BLOCK, Blocks.STONE, -1.0, 0.03, true);
    }

    /** The least a slime bounce may give back of the speed it met the slime with: 85% of the restitution. */
    private static double slimeLow(boolean bothSides) {
        double e = FullStopConfig.SERVER.slimeRestitution.get();
        return 0.85 * (bothSides ? Math.sqrt(e) : e);
    }

    /**
     * A 3x1x3 raft of {@code hull} dropped 6 blocks onto a floor of {@code floor}: the speed it
     * leaves the floor at, over the speed it hit it with, must lie in [{@code min}, {@code max}]
     * (the height it bounces back to is reported too; VS's air drag on a flat raft keeps that under
     * restitution squared of the drop). With {@code glued}, the raft must also end up glued to the
     * ground, stay put under a gentle push, and tear free under a strong one.
     */
    private static void drop(GameTestHelper helper, String what, Block hull, Block floor, double min, double max,
                             boolean glued) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY);
        fill(level, o.offset(-2, 0, -2), o.offset(4, 0, 4), floor.defaultBlockState());
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 7, 0), o.offset(2, 7, 2), hull.defaultBlockState()), hull, false);
        if (rig == null) return;
        double restY = o.getY() + 1.5; // the raft's middle, lying on the floor
        double[] apex = {Double.NEGATIVE_INFINITY};
        double[] lowest = {Double.POSITIVE_INFINITY};
        double[] upSpeed = {0.0};
        long[] landedAt = {-1};
        double[] landedSpeed = {0.0};
        Vec3[] before = {null};
        helper.onEachTick(() -> {
            rig.record();
            Vec3 v = rig.ref.velocity();
            if (rig.start == null || v == null) return;
            double y = rig.y();
            if (landedAt[0] < 0) {
                if (rig.firstContact >= 0) {
                    landedAt[0] = System.nanoTime();
                    landedSpeed[0] = before[0] == null ? 0.0 : -before[0].y;
                }
                before[0] = v;
                return;
            }
            // the first 1.6 s after the landing: the rebound's top (a 0.81 x 6 block bounce peaks at 1 s)
            if (System.nanoTime() - landedAt[0] < 1_600_000_000L) {
                apex[0] = Math.max(apex[0], y);
                lowest[0] = Math.min(lowest[0], y);
            }
            // the speed it leaves the floor at: the fastest upward in the first half second
            if (System.nanoTime() - landedAt[0] < 500_000_000L) upSpeed[0] = Math.max(upSpeed[0], v.y);
        });
        double[] pushedFrom = new double[3];
        long[] pushStart = {-1};
        var sequence = helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(() -> helper.assertTrue(landedAt[0] >= 0 && System.nanoTime() - landedAt[0] >= 2_000_000_000L,
                        "waiting for the raft to land and bounce (at " + rig.ref.position() + ", v " + rig.ref.velocity() + ")"))
                .thenExecute(() -> {
                    double drop = rig.start.y - restY;
                    double rise = apex[0] - restY;
                    double heights = rise / drop;
                    double ratio = upSpeed[0] / landedSpeed[0];
                    int glues = ShipGlue.glueCount(level, rig.ship.getId());
                    report(what, String.format(Locale.ROOT, "dropped %.2f blocks, landed at %.2f m/s, left the floor at %.2f "
                                    + "m/s = %.3f of it (slimeRestitution %.2f), bounced back up %.2f blocks = %.3f of the drop "
                                    + "(restitution squared %.3f; less with VS's air drag), %d glue(s) | %s", drop, landedSpeed[0],
                            upSpeed[0], ratio, FullStopConfig.SERVER.slimeRestitution.get(), rise, heights,
                            Math.pow(FullStopConfig.SERVER.slimeRestitution.get(), 2), glues, rig.summary()));
                    if (glued) {
                        // honey: whatever VS gave back at first is taken out at once, so judge the height
                        helper.assertTrue(heights <= max, what + ": bounced back up " + fmt(heights) + " of its drop");
                    } else {
                        helper.assertTrue(ratio >= min && ratio <= max, what + ": left the floor at " + fmt(ratio)
                                + " of the speed it hit it with, outside [" + fmt(min) + ", " + fmt(max) + "]");
                    }
                    if (!glued) helper.assertTrue(glues == 0, what + ": glued with no honey in the crash");
                });
        if (!glued) {
            sequence.thenExecute(() -> {
                rig.pin();
                helper.succeed();
            });
            return;
        }
        sequence
                .thenWaitUntil(() -> {
                    helper.assertTrue(!ShipGlue.joints(level, rig.ship.getId()).isEmpty()
                            && ShipGlue.joints(level, rig.ship.getId()).get(0).state() == ShipJoint.State.ACTIVE,
                            "waiting for the honey raft to be glued");
                })
                .thenExecute(() -> {
                    // gentle: a quarter of the glue's strength, sideways, for 1.5 s
                    Vec3 p = rig.ref.position();
                    pushedFrom[0] = p.x;
                    pushedFrom[1] = p.y;
                    pushedFrom[2] = p.z;
                    pushStart[0] = System.nanoTime();
                })
                .thenWaitUntil(() -> {
                    double gentle = 0.25 * FullStopConfig.SERVER.honeyGlueStrength.get();
                    ShipForces.setContinuous(level, rig.ship.getId(), PUSH, new Vec3(gentle, 0, 0), null, 2);
                    helper.assertTrue(System.nanoTime() - pushStart[0] >= 1_500_000_000L, "pushing gently");
                })
                .thenExecute(() -> {
                    ShipForces.clearContinuous(level, rig.ship.getId(), PUSH);
                    List<ShipJoint> joints = ShipGlue.joints(level, rig.ship.getId());
                    double moved = rig.ref.position().subtract(pushedFrom[0], pushedFrom[1], pushedFrom[2]).length();
                    ShipJoint joint = joints.isEmpty() ? null : joints.get(0);
                    report(what + ", gentle push", String.format(Locale.ROOT, "%.0f N for 1.5 s: moved %.4f blocks, glue %s "
                                    + "(stretch %.6f m, load %.0f N, read by %s)",
                            0.25 * FullStopConfig.SERVER.honeyGlueStrength.get(), moved,
                            joint == null ? "gone" : joint.state(), joint == null ? Double.NaN : joint.stretch(),
                            joint == null ? Double.NaN : joint.load(), joint == null ? "-" : joint.loadMode()));
                    helper.assertTrue(joint != null && joint.isValid(), "a gentle push tore the honey glue");
                    helper.assertTrue(moved < 0.05, "the glued raft moved " + fmt(moved) + " blocks under a gentle push");
                    pushStart[0] = System.nanoTime();
                })
                .thenWaitUntil(() -> {
                    double strong = 4.0 * FullStopConfig.SERVER.honeyGlueStrength.get();
                    ShipForces.setContinuous(level, rig.ship.getId(), PUSH, new Vec3(strong, 0, 0), null, 2);
                    double moved = rig.ref.position().subtract(pushedFrom[0], pushedFrom[1], pushedFrom[2]).length();
                    helper.assertTrue(ShipGlue.joints(level, rig.ship.getId()).isEmpty() && moved > 1.0,
                            "pushing hard (moved " + fmt(moved) + ")");
                })
                .thenExecute(() -> {
                    ShipForces.clearContinuous(level, rig.ship.getId(), PUSH);
                    double moved = rig.ref.position().subtract(pushedFrom[0], pushedFrom[1], pushedFrom[2]).length();
                    report(what + ", strong push", String.format(Locale.ROOT, "%.0f N: tore free after %.2f s, moved %.2f "
                                    + "blocks, %d glue(s) left", 4.0 * FullStopConfig.SERVER.honeyGlueStrength.get(),
                            (System.nanoTime() - pushStart[0]) / 1.0e9, moved, ShipGlue.glueCount(level, rig.ship.getId())));
                    helper.assertTrue(ShipGlue.glueCount(level, rig.ship.getId()) == 0, "the torn glue is still remembered");
                    rig.pin();
                    helper.succeed();
                });
    }

    /**
     * A floating 3x3x3 slime cube pushed at 8 m/s into a slime wall springs back at about
     * sqrt(slimeRestitution) of that (slime on both sides).
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aSlimeShipRammedIntoASlimeWallBouncesBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY);
        fill(level, o.offset(8, 0, -2), o.offset(9, 8, 6), Blocks.SLIME_BLOCK.defaultBlockState());
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 2, 1), o.offset(2, 4, 3), Blocks.SLIME_BLOCK.defaultBlockState()),
                Blocks.SLIME_BLOCK, true);
        if (rig == null) return;
        double speed = 8.0;
        double[] metAt = {0.0};
        double[] backAt = {0.0};
        long[] hitAt = {-1};
        helper.onEachTick(() -> {
            rig.record();
            Vec3 v = rig.ref.velocity();
            if (v == null || rig.start == null) return;
            if (hitAt[0] < 0) {
                metAt[0] = Math.max(metAt[0], v.x);
                if (rig.firstContact >= 0) hitAt[0] = System.nanoTime();
                return;
            }
            if (System.nanoTime() - hitAt[0] < 600_000_000L) backAt[0] = Math.min(backAt[0], v.x);
        });
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> rig.push(new Vec3(speed, 0.0, 0.0)))
                .thenWaitUntil(() -> helper.assertTrue(hitAt[0] >= 0 && System.nanoTime() - hitAt[0] >= 800_000_000L,
                        "waiting for the cube to hit the wall and spring back (at " + rig.ref.position() + ")"))
                .thenExecute(() -> {
                    double e = Math.sqrt(FullStopConfig.SERVER.slimeRestitution.get());
                    double ratio = -backAt[0] / metAt[0];
                    report("slime cube into slime wall", String.format(Locale.ROOT, "met the wall at %.2f m/s, sprang back at "
                            + "%.2f m/s = %.3f of it (sqrt(slimeRestitution) %.3f) | %s", metAt[0], -backAt[0], ratio, e,
                            rig.summary()));
                    rig.pin();
                    helper.assertTrue(ratio >= slimeLow(true) && ratio <= 1.05, "the slime cube sprang back at "
                            + fmt(ratio) + " of its speed");
                    helper.succeed();
                });
    }

    // ------------------------------------------------------------------
    // Honey between two ships
    // ------------------------------------------------------------------

    /**
     * Two floating plank rafts; the first has a honey face and is pushed into the second at 3 m/s.
     * They glue; then the first is driven back the other way at 2 m/s and the second must follow at
     * the same distance.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void twoShipsWithHoneyFacesGlueAndTowEachOther(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY);
        var hullA = fillSet(level, o.offset(0, 4, 0), o.offset(2, 5, 2), Blocks.OAK_PLANKS.defaultBlockState());
        var honey = fillSet(level, o.offset(3, 4, 0), o.offset(3, 5, 2), Blocks.HONEY_BLOCK.defaultBlockState());
        hullA.addAll(honey);
        Rig a = Rig.build(helper, hullA, Blocks.OAK_PLANKS, true, 18);
        Rig b = Rig.build(helper, fillSet(level, o.offset(7, 4, 0), o.offset(9, 5, 2), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (a == null || b == null) return;
        long[] gluedAt = {-1};
        double[] gap = {Double.NaN, Double.NaN};
        double[] bSpeed = {0.0};
        helper.onEachTick(() -> {
            a.record();
            b.record();
            if (gluedAt[0] >= 0) {
                a.cruise(new Vec3(-2.0, 0.0, 0.0), 2.0);
                double d = b.ref.position().x - a.ref.position().x;
                if (Double.isNaN(gap[0])) gap[0] = d;
                gap[1] = d;
                Vec3 vb = b.ref.velocity();
                if (vb != null) bSpeed[0] = vb.x;
            }
        });
        helper.startSequence()
                .thenWaitUntil(a::demandReady)
                .thenWaitUntil(b::demandReady)
                .thenExecute(() -> {
                    a.release();
                    b.release();
                })
                .thenWaitUntil(a::demandSimulated)
                .thenWaitUntil(b::demandSimulated)
                .thenExecute(() -> {
                    b.hover();
                    b.push(Vec3.ZERO);
                    a.push(new Vec3(3.0, 0.0, 0.0));
                })
                .thenWaitUntil(() -> helper.assertTrue(!ShipGlue.joints(level, a.ship.getId()).isEmpty()
                                && ShipGlue.joints(level, a.ship.getId()).get(0).state() == ShipJoint.State.ACTIVE,
                        "waiting for the rafts to meet and glue (a at " + a.ref.position() + ", b at " + b.ref.position() + ")"))
                .thenExecute(() -> gluedAt[0] = System.nanoTime())
                .thenWaitUntil(() -> helper.assertTrue(System.nanoTime() - gluedAt[0] >= 2_500_000_000L, "towing"))
                .thenExecute(() -> {
                    List<ShipJoint> joints = ShipGlue.joints(level, a.ship.getId());
                    ShipJoint joint = joints.isEmpty() ? null : joints.get(0);
                    Vec3 va = a.ref.velocity();
                    report("honey between two ships", String.format(Locale.ROOT, "glued (%s, read by %s, peak load %.0f N); "
                                    + "towed back: gap %.3f -> %.3f blocks, towed raft at %.2f m/s, tug at %.2f m/s | %s",
                            joint == null ? "gone" : joint.state(), joint == null ? "-" : joint.loadMode(),
                            joint == null ? Double.NaN : joint.peakLoad(), gap[0], gap[1], bSpeed[0],
                            va == null ? Double.NaN : va.x, a.summary()));
                    a.stopThrust();
                    a.pin();
                    b.pin();
                    helper.assertTrue(joint != null && joint.isValid(), "the honey glue between the rafts didn't hold a tow");
                    helper.assertTrue(Math.abs(gap[1] - gap[0]) < 0.2, "the towed raft drifted " + fmt(gap[1] - gap[0])
                            + " blocks from the tug");
                    helper.assertTrue(bSpeed[0] < -1.0, "the towed raft isn't moving with the tug (" + fmt(bSpeed[0]) + " m/s)");
                    helper.succeed();
                });
    }

    // ------------------------------------------------------------------
    // Glue made again
    // ------------------------------------------------------------------

    /**
     * A honey raft glued to stone loses its joint as when Valkyrien Skies unloads its ship (the
     * glue stays remembered); the glue must be made again where it was. (VS on this test server
     * never unloads a ship, even with no player near and nothing forcing its chunks, so the
     * joint is taken out the way VS does it instead.)
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aHoneyGlueIsMadeAgainAfterItsJointGoes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY);
        fill(level, o.offset(-2, 0, -2), o.offset(4, 0, 4), Blocks.STONE.defaultBlockState());
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 3, 0), o.offset(2, 3, 2), Blocks.HONEY_BLOCK.defaultBlockState()),
                Blocks.HONEY_BLOCK, false);
        if (rig == null) return;
        long id = rig.ship.getId();
        Vec3[] gluedAt = {null};
        int[] dropped = {0};
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(() -> helper.assertTrue(!ShipGlue.joints(level, id).isEmpty()
                        && ShipGlue.joints(level, id).get(0).state() == ShipJoint.State.ACTIVE, "waiting for the glue"))
                .thenExecute(() -> {
                    gluedAt[0] = rig.ref.position();
                    dropped[0] = ShipGlue.dropJoints(level, id);
                    helper.assertTrue(dropped[0] == 1 && ShipGlue.joints(level, id).isEmpty()
                            && ShipGlue.glueCount(level, id) == 1, "the glue wasn't kept when its joint went");
                })
                .thenWaitUntil(() -> helper.assertTrue(!ShipGlue.joints(level, id).isEmpty()
                        && ShipGlue.joints(level, id).get(0).state() == ShipJoint.State.ACTIVE, "waiting for the glue to be made again"))
                .thenExecute(() -> {
                    Vec3 now = rig.ref.position();
                    report("honey glue made again", String.format(Locale.ROOT, "glued at %s, joint taken out (glue kept), "
                                    + "glued again at %s (%.4f blocks away), %s", gluedAt[0], now, now.distanceTo(gluedAt[0]),
                            ShipGlue.joints(level, id).get(0)));
                    rig.pin();
                    helper.assertTrue(now.distanceTo(gluedAt[0]) < 0.05, "the raft moved while its glue was away");
                    helper.succeed();
                });
    }

    // ------------------------------------------------------------------
    // Camafy's glue joint on this backend
    // ------------------------------------------------------------------

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aGlueJointHoldsAHangingRaftAgainst400kN(GameTestHelper helper) {
        joint(helper, "unbreakable glue", ShipJoints.Settings.unbreakable());
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aGlueJointSetTo100kNLetsGoNearThere(GameTestHelper helper) {
        joint(helper, "glue breaking at 100 kN", ShipJoints.Settings.breakingAt(100_000.0));
    }

    /**
     * A 3x1x3 plank raft floating 6 blocks up (held up by a force equal to its weight), glued to the
     * ground at its underside's middle while it floats - so the glue sets holding nothing - then let
     * hang from it: it must not sag. Then it is pulled straight up by a force ramping from 0 to
     * 400 kN over 4 s. An unbreakable joint must not give a centimetre; one breaking at 100 kN must
     * let go once the pull has changed by that much from when it set, i.e. at a pull of the raft's
     * weight plus 100 kN (seen between 120 and 200 kN: the server sees the tear a little after).
     */
    private static void joint(GameTestHelper helper, String what, ShipJoints.Settings settings) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY);
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 6, 0), o.offset(2, 6, 2), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        ShipJoint[] joint = new ShipJoint[1];
        long[] t0 = {-1};
        long[] gluedAt = {-1};
        double[] y0 = {Double.NaN};
        double[] hangDrop = {0.0};
        double[] maxRise = {0.0};
        double[] endedAtPull = {Double.NaN};
        double[] pullNow = {0.0};
        helper.onEachTick(() -> {
            rig.record();
            if (joint[0] == null || t0[0] < 0) return;
            double t = (System.nanoTime() - t0[0]) / 1.0e9;
            double pull = t < 1.0 ? 0.0 : Math.min(400_000.0, (t - 1.0) * 100_000.0);
            pullNow[0] = pull;
            if (pull > 0) ShipForces.setContinuous(level, rig.ship.getId(), PUSH, new Vec3(0, pull, 0), null, 2);
            double dy = rig.y() - y0[0];
            if (t < 1.0) hangDrop[0] = Math.min(hangDrop[0], dy);
            if (joint[0].isValid()) maxRise[0] = Math.max(maxRise[0], dy);
            if (Double.isNaN(endedAtPull[0]) && !joint[0].isValid()) endedAtPull[0] = pull;
        });
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> rig.push(Vec3.ZERO)) // floats where it is
                .thenIdle(10)
                .thenExecute(() -> {
                    Vec3 p = rig.ref.position();
                    Vec3 point = new Vec3(p.x, p.y - 0.5, p.z);
                    joint[0] = ShipJoints.glue(level, rig.ship.getId(), ShipJoints.GROUND, point, settings);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(joint[0] != null && joint[0].state() == ShipJoint.State.ACTIVE,
                            "waiting for VS to make the joint (" + (joint[0] == null ? "none" : joint[0].state()) + ")");
                    if (gluedAt[0] < 0) gluedAt[0] = System.nanoTime();
                    // settled, and looked at once (the first look is what it holds when it set)
                    helper.assertTrue(System.nanoTime() - gluedAt[0] >= 1_000_000_000L, "letting the joint set");
                })
                .thenExecute(() -> {
                    ShipForces.clearContinuous(level, rig.ship.getId(), ShipCollisionTests.FLOAT); // hangs from the glue
                    y0[0] = rig.y();
                    t0[0] = System.nanoTime();
                })
                .thenWaitUntil(() -> helper.assertTrue(System.nanoTime() - t0[0] >= 5_500_000_000L
                        || (!joint[0].isValid() && rig.y() - y0[0] > 2.0), "pulling"))
                .thenExecute(() -> {
                    ShipForces.clearContinuous(level, rig.ship.getId(), PUSH);
                    double weight = rig.ref.mass() * 10.0;
                    report(what + " (" + ShipCollisionTests.BACKEND + ")", String.format(Locale.ROOT,
                            "%s, read by %s: glued floating, then sagged %.4f blocks hanging from it (weight %.0f N), rose at "
                                    + "most %.4f blocks while held, seen ended at a pull of %.0f N (%.0f N net of the weight; "
                                    + "pull at the end %.0f N), largest pull read %.0f N%s", joint[0].state(),
                            joint[0].loadMode(), -hangDrop[0], weight, maxRise[0], endedAtPull[0], endedAtPull[0] - weight,
                            pullNow[0], joint[0].peakLoad(), joint[0].isValid() ? "" : " - " + joint[0].endReason()));
                    boolean breaks = Double.isFinite(settings.breakForce());
                    if (joint[0].isValid()) joint[0].remove();
                    rig.pin();
                    helper.assertTrue(hangDrop[0] > -0.01, "the raft sagged " + fmt(-hangDrop[0]) + " blocks hanging from the joint");
                    if (!breaks) {
                        helper.assertTrue(joint[0].state() == ShipJoint.State.REMOVED, "the unbreakable joint ended: "
                                + joint[0].endReason());
                        helper.assertTrue(maxRise[0] < 0.01, "the unbreakable joint gave " + fmt(maxRise[0]) + " blocks");
                    } else {
                        helper.assertTrue(joint[0].state() == ShipJoint.State.TORN, "the 100 kN joint didn't tear: "
                                + joint[0].state());
                        helper.assertTrue(endedAtPull[0] > 120_000.0 && endedAtPull[0] < 200_000.0, "the 100 kN joint let go at "
                                + Math.round(endedAtPull[0]) + " N of pull (the raft weighs " + Math.round(weight) + " N)");
                    }
                    helper.succeed();
                });
    }
}
