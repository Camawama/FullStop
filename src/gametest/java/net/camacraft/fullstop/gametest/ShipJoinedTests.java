package net.camacraft.fullstop.gametest;

import net.camacraft.camafy.compat.vs.ShipJoint;
import net.camacraft.camafy.compat.vs.ShipJoints;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.FullStop;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.Rig;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fillSet;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.report;

/**
 * Ships held together by Valkyrien Skies' joints are one machine (the owner's rotor on a Clockwork phys bearing inside
 * an obsidian ship, which the two then broke up between them as crashes): Camafy's {@code Ships.joined} says which,
 * down a chain, and FullStop leaves such pairs out of its crash, clash, slime and deck-crash rules. Three floating
 * rafts glued in a row with Camafy's joints (the same joint API Clockwork's bearings use), and a fourth on its own.
 * Compiled only with {@code -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipJoinedTests {

    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final int TIMEOUT = ShipCollisionTests.TIMEOUT;
    private static final String BATCH = "fullstop_joined";

    private ShipJoinedTests() {
    }

    /** A-B and B-C glued: A, B and C are all joined, D (alone) to none; with A-B removed, A is joined to nothing. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void shipsHeldByJointsAreJoinedDownTheChain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 183 * 256);
        Rig a = Rig.build(helper, fillSet(level, o.offset(0, 6, 0), o.offset(1, 6, 1), Blocks.OAK_PLANKS.defaultBlockState()), Blocks.OAK_PLANKS, true);
        Rig b = Rig.build(helper, fillSet(level, o.offset(4, 6, 0), o.offset(5, 6, 1), Blocks.OAK_PLANKS.defaultBlockState()), Blocks.OAK_PLANKS, true);
        Rig c = Rig.build(helper, fillSet(level, o.offset(8, 6, 0), o.offset(9, 6, 1), Blocks.OAK_PLANKS.defaultBlockState()), Blocks.OAK_PLANKS, true);
        Rig d = Rig.build(helper, fillSet(level, o.offset(12, 6, 0), o.offset(13, 6, 1), Blocks.OAK_PLANKS.defaultBlockState()), Blocks.OAK_PLANKS, true);
        if (a == null || b == null || c == null || d == null) return;
        ShipJoint[] joints = new ShipJoint[2];
        boolean[] before = new boolean[1];
        for (Rig rig : new Rig[]{a, b, c, d}) helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(a::demandReady)
                .thenWaitUntil(b::demandReady)
                .thenWaitUntil(c::demandReady)
                .thenWaitUntil(d::demandReady)
                .thenExecute(() -> {
                    for (Rig rig : new Rig[]{a, b, c, d}) rig.release();
                })
                .thenWaitUntil(a::demandSimulated)
                .thenWaitUntil(b::demandSimulated)
                .thenWaitUntil(c::demandSimulated)
                .thenWaitUntil(d::demandSimulated)
                .thenExecute(() -> {
                    for (Rig rig : new Rig[]{a, b, c, d}) rig.push(Vec3.ZERO); // each floats where it is
                    before[0] = Ships.joined(level, a.ref.id(), b.ref.id());
                    joints[0] = ShipJoints.glue(level, a.ref.id(), b.ref.id(), Vec3.atCenterOf(o.offset(3, 6, 1)), ShipJoints.Settings.unbreakable());
                    joints[1] = ShipJoints.glue(level, b.ref.id(), c.ref.id(), Vec3.atCenterOf(o.offset(7, 6, 1)), ShipJoints.Settings.unbreakable());
                })
                .thenWaitUntil(() -> helper.assertTrue(joints[0] != null && joints[1] != null
                                && joints[0].state() == ShipJoint.State.ACTIVE && joints[1].state() == ShipJoint.State.ACTIVE,
                        "waiting for VS to make the joints (" + (joints[0] == null ? "none" : joints[0].state()) + ", "
                                + (joints[1] == null ? "none" : joints[1].state()) + ")"))
                .thenIdle(2)
                .thenExecute(() -> {
                    long ia = a.ref.id(), ib = b.ref.id(), ic = c.ref.id(), id = d.ref.id();
                    String out = "before the glue A-B joined " + before[0] + "; glued: A-B " + Ships.joined(level, ia, ib) + ", B-C "
                            + Ships.joined(level, ib, ic) + ", A-C " + Ships.joined(level, ia, ic) + ", C-A " + Ships.joined(level, ic, ia)
                            + ", A-D " + Ships.joined(level, ia, id) + ", A's group " + a.ref.connectedShips();
                    report("ships held by joints", out);
                    helper.assertTrue(!before[0], "two rafts counted as joined before any joint: " + out);
                    helper.assertTrue(Ships.joined(level, ia, ib) && Ships.joined(level, ib, ic), "glued rafts not joined: " + out);
                    helper.assertTrue(Ships.joined(level, ia, ic) && Ships.joined(level, ic, ia), "the chain A-B-C isn't joined end to end: " + out);
                    helper.assertTrue(!Ships.joined(level, ia, id) && !Ships.joined(level, id, ic), "a raft with no joint counted as joined: " + out);
                    helper.assertTrue(!Ships.joined(level, ia, ia), "a ship joined to itself: " + out);
                    joints[0].remove();
                })
                .thenWaitUntil(() -> helper.assertTrue(!Ships.joined(level, a.ref.id(), b.ref.id()), "waiting for the A-B joint to go"))
                .thenExecute(() -> {
                    long ia = a.ref.id(), ib = b.ref.id(), ic = c.ref.id();
                    String out = "after removing A-B: A-B " + Ships.joined(level, ia, ib) + ", A-C " + Ships.joined(level, ia, ic)
                            + ", B-C " + Ships.joined(level, ib, ic);
                    report("a joint removed", out);
                    helper.assertTrue(!Ships.joined(level, ia, ic), "A still joined to C: " + out);
                    helper.assertTrue(Ships.joined(level, ib, ic), "B lost C: " + out);
                    joints[1].remove();
                    for (Rig rig : new Rig[]{a, b, c, d}) rig.pin();
                    helper.succeed();
                });
    }
}
