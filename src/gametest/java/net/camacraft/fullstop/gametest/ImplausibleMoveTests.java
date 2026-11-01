package net.camacraft.fullstop.gametest;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.physics.ShipHullSweep;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;

/**
 * 2026-10-30, the owner's client froze for good on joining a save with the player on a ship's seat in VS Genesis's Great
 * Unknown ("I restarted the game and attempted to boot into that world again but the game instantly froze"): the render
 * thread was in {@code ShipHullSweep.resolve}, colliding a box that never ended. A move longer than
 * {@link ShipHullSweep#MAX_MOVE} blocks in one call is no motion at all: it is refused at once, the body stays where it
 * is and loses its velocity, and a sane move still goes through.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ImplausibleMoveTests {
    private static final String FLAT = "flat_48x8x48";

    private ImplausibleMoveTests() {
    }

    @GameTest(template = FLAT, timeoutTicks = 60)
    public static void aMoveOfAMillionBlocksIsRefusedAtOnceAndASaneOneGoesThrough(GameTestHelper helper) {
        Cow cow = helper.spawn(EntityType.COW, new Vec3(8.5, 2.0, 8.5));
        cow.setNoAi(true);
        cow.setNoGravity(true);
        Vec3 start = cow.position();
        long refusedBefore = ShipHullSweep.refusedMoves();

        cow.setDeltaMovement(new Vec3(50_000.0, 0.0, 0.0));
        long t0 = System.nanoTime();
        cow.move(MoverType.SELF, new Vec3(1.0e6, 0.0, -2.8e7));
        double ms = (System.nanoTime() - t0) / 1.0e6;
        helper.assertTrue(ms < 200.0, String.format(Locale.ROOT, "a million-block move took %.1f ms", ms));
        helper.assertTrue(cow.position().distanceTo(start) < 1.0e-6, "the cow moved to " + cow.position());
        helper.assertTrue(cow.getDeltaMovement().lengthSqr() == 0.0, "the cow kept its velocity " + cow.getDeltaMovement());

        cow.move(MoverType.SELF, new Vec3(Double.NaN, 0.0, 0.0));
        helper.assertTrue(cow.position().distanceTo(start) < 1.0e-6, "a NaN move moved the cow to " + cow.position());
        helper.assertTrue(ShipHullSweep.refusedMoves() == refusedBefore + 2,
                "refused " + (ShipHullSweep.refusedMoves() - refusedBefore) + " moves, not 2");

        // a sane move still moves (6 blocks along the empty air of the plot)
        cow.move(MoverType.SELF, new Vec3(6.0, 0.0, 0.0));
        double moved = cow.position().x - start.x;
        helper.assertTrue(Math.abs(moved - 6.0) < 1.0e-6, String.format(Locale.ROOT, "a 6-block move went %.3f", moved));
        helper.succeed();
    }

    /**
     * The owner's save: a Create seat in a ship's shipyard that had never ticked on the client handed its rider
     * "pos - xOld" = its whole position as dismount momentum (xOld still 0), 31 million blocks a tick. A vehicle that has
     * never ticked, or whose observed motion no body can have, hands on none; a sane one still does.
     */
    @GameTest(template = FLAT, timeoutTicks = 20)
    public static void aVehicleThatNeverTickedHandsOnNoMotion(GameTestHelper helper) {
        net.minecraft.world.entity.vehicle.Boat boat = EntityType.BOAT.create(helper.getLevel());
        helper.assertTrue(boat != null, "no boat");
        boat.setPosRaw(-28665854.5, 129.0, 12290046.5); // its old position stays (0, 0, 0)
        Vec3 fresh = net.camacraft.fullstop.server.physics.PhysicsDispatchServer.observedVehicleMotion(boat);
        helper.assertTrue(fresh.lengthSqr() == 0.0, "a never-ticked seat in the shipyard hands on " + fresh);

        boat.tickCount = 5;
        boat.xOld = boat.getX() - 0.5;
        boat.yOld = boat.getY();
        boat.zOld = boat.getZ();
        Vec3 sane = net.camacraft.fullstop.server.physics.PhysicsDispatchServer.observedVehicleMotion(boat);
        helper.assertTrue(Math.abs(sane.x - 0.5) < 1.0e-9, "a boat moving half a block a tick hands on " + sane);

        boat.xOld = 0.0; // a stale old position after it ticked: still no body's motion
        Vec3 stale = net.camacraft.fullstop.server.physics.PhysicsDispatchServer.observedVehicleMotion(boat);
        helper.assertTrue(stale.lengthSqr() == 0.0, "a 28-million-block 'motion' was handed on: " + stale);
        helper.succeed();
    }
}
