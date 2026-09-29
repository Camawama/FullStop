package net.camacraft.fullstop.gametest;

import com.mojang.authlib.GameProfile;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.ShipSweptContact;
import net.camacraft.fullstop.server.physics.ship.ShipContactPhysics;
import net.camacraft.fullstop.server.physics.ship.ShipTracker;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

import java.util.Locale;
import java.util.UUID;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.fmt;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.report;

/**
 * The PLAYER's side of ships, which a headless server can't run for real (a player moves on its
 * own client): the decisions, fed the inputs a real client and server would give them.
 *
 * <ul>
 *   <li>How the server measures a player riding a rotor: positions come from the client, drawn
 *   with the client's ship poses, while the server's own poses run ahead. Subtracting the
 *   server's carry left a velocity turning with the rotor; the client's own carry (and hull
 *   shove) leaves the player's own motion.</li>
 *   <li>A ray of the entity's own motion hitting a moving hull is only an impact if the two really
 *   meet (a hull moving away, a rotor arm sweeping by, is none).</li>
 *   <li>A deck jolt throws a rider only as far as it still stands a frame later (a rotor's bearing
 *   jitters for a frame and comes back).</li>
 *   <li>A hull striking a player is judged with the player's own velocity before the hull shoved
 *   it; with the shove counted as the player's velocity (the server's view) it closed at
 *   nothing, and the player was dragged along.</li>
 *   <li>What a rider seated on a ship feels: its seat's acceleration, centripetal pull included.</li>
 * </ul>
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipPlayerPathTests {

    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_player_path";
    private static final double RPM = Math.PI / 30.0;
    private static final double FRAME = 1.0 / 60.0;

    private ShipPlayerPathTests() {
    }

    // ------------------------------------------------------------------
    // A player riding a rotor, measured on the server
    // ------------------------------------------------------------------

    /**
     * A player standing still 3 blocks out on a rotor at 64 RPM (20 m/s there), its client's move
     * reports arriving bunched as the two clocks drift (1, 1, 2, 0 client ticks per server tick),
     * and once shoved 0.4 blocks outward by a hull. Measured with the carry and shove its client
     * reports: its own velocity stays near zero. Measured with the carry of the server's own
     * ship pose, 100 ms ahead of the client's (what the server did before): a velocity of its own
     * that turns with the rotor.
     */
    @GameTest(template = FLAT, batch = BATCH)
    public static void aPlayerRidingARotorIsMeasuredWithItsClientsCarry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(
                "fullstop-path-rotor".getBytes()), "[FS path test]"));
        double omega = 64.0 * RPM;
        double[] client = rider(player, omega, 3.0, 0.0, true);
        double[] server = rider(player, omega, 3.0, 0.1, false);
        String out = String.format(Locale.ROOT, "rotor at 64 RPM, rider 3 blocks out (%.1f m/s), bunched move reports, one "
                        + "0.4-block hull shove: own velocity measured with the client's carry and shove at most %.2f m/s (%.2f "
                        + "on the shove tick); with the server's pose 100 ms ahead and no shove report %.2f m/s (%.2f on the "
                        + "shove tick)", omega * 3.0, client[0], client[1], server[0], server[1]);
        report("player measured on a rotor", out);
        helper.assertTrue(client[0] < 0.5 && client[1] < 0.5, "a still rider read as moving: " + out);
        helper.assertTrue(server[0] > 3.0, "the server-pose measurement shows no residual (the test is off): " + out);
        helper.succeed();
    }

    /** {max own speed away from the shove, own speed on the shove tick} (m/s). */
    private static double[] rider(FakePlayer player, double omega, double radius, double lagSeconds, boolean clientReports) {
        FullStopCapability cap = new FullStopCapability(player);
        Vec3 axis = new Vec3(0.5, 120.0, 0.5);
        int[] pattern = {1, 1, 2, 0};
        int clientTick = 0;
        double r = radius;
        double max = 0.0;
        double onShove = 0.0;
        Vec3 at = circle(axis, 0.0, r);
        player.setPos(at);
        for (int tick = 0; tick < 64; tick++) {
            boolean shovedNow = false;
            for (int i = 0; i < pattern[tick % pattern.length]; i++) {
                clientTick++;
                double theta = omega * 0.05 * clientTick;
                double before = omega * 0.05 * (clientTick - 1);
                double lag = omega * lagSeconds;
                // the deck's drag of the point the rider stood on, per the pose used
                Vec3 carry = circle(axis, theta + lag, r).subtract(circle(axis, before + lag, r));
                Vec3 shove = Vec3.ZERO;
                if (clientTick == 30) {
                    shove = circle(axis, theta, r + 0.4).subtract(circle(axis, theta, r));
                    r += 0.4;
                    shovedNow = true;
                }
                at = circle(axis, theta, r);
                cap.recordClientDelta(Vec3.ZERO, false, false, carry.scale(20.0), clientReports ? shove.scale(20.0) : null);
                cap.recordMoveSample(at, true, 0.0f);
            }
            player.setPos(at);
            cap.tick(player);
            double own = cap.getCurrentScaledVelocity().length();
            if (tick < 4) continue;
            if (shovedNow) {
                onShove = Math.max(onShove, own);
            } else {
                max = Math.max(max, own);
            }
        }
        return new double[]{max, onShove};
    }

    private static Vec3 circle(Vec3 axis, double theta, double radius) {
        return axis.add(Math.cos(theta) * radius, 0.0, Math.sin(theta) * radius);
    }

    // ------------------------------------------------------------------
    // Ray hits on moving hulls
    // ------------------------------------------------------------------

    /**
     * An entity's ray reaches a hull block, but does the entity meet the hull within the tick?
     * A hull 0.45 ahead moving away at 20 m/s from an entity at 10 m/s: no. The same hull
     * standing still: yes. A rotor at 256 RPM whose arm reaches 3.5 blocks, an entity 3 blocks
     * outside that coming at 20 m/s (its rays a block long): no. One inside the arm's circle, in
     * its way: yes.
     */
    @GameTest(template = FLAT, batch = BATCH)
    public static void aRayHitOnAMovingHullIsOnlyAnImpactIfTheyMeet(GameTestHelper helper) {
        AABB wall = new AABB(10.0, 0.0, -1.0, 11.0, 2.0, 1.0);
        AABB entity = new AABB(9.1, 0.0, -0.3, 9.55, 1.8, 0.3);
        Vec3 wallCom = new Vec3(10.5, 1.0, 0.0);
        double away = meets(wall, new ShipSweptContact.Relative(wallCom, new Vec3(20, 0, 0), Vec3.ZERO, new Vec3(10, 0, 0)), entity);
        double still = meets(wall, new ShipSweptContact.Relative(wallCom, Vec3.ZERO, Vec3.ZERO, new Vec3(10, 0, 0)), entity);
        // the rotor: an arm along +x from its axis, turning about +y (so toward -z)
        Vec3 axis = new Vec3(0.0, 1.0, 0.0);
        AABB arm = new AABB(0.0, 0.5, -0.5, 3.5, 1.5, 0.5);
        Vec3 w = new Vec3(0.0, 256.0 * RPM, 0.0);
        AABB outside = new AABB(-0.3, 0.1, 6.54, 0.3, 2.05, 7.14);
        double far = meets(arm, new ShipSweptContact.Relative(axis, Vec3.ZERO, w, new Vec3(0, 0, -20)), outside);
        AABB inside = new AABB(-0.3, 0.1, -2.5, 0.3, 2.05, -1.9);
        double near = meets(arm, new ShipSweptContact.Relative(axis, Vec3.ZERO, w, Vec3.ZERO), inside);
        String out = String.format(Locale.ROOT, "hull 0.45 ahead moving away at 20 m/s from an entity at 10 m/s: %s; the same "
                        + "hull standing still: %s; rotor at 256 RPM, entity 3 blocks outside its arm's circle coming at 20 m/s: "
                        + "%s; entity inside the circle in the arm's way: %s", when(away), when(still), when(far), when(near));
        report("ray hits on moving hulls", out);
        helper.assertTrue(Double.isNaN(away), "a hull moving away counted as met: " + out);
        helper.assertTrue(!Double.isNaN(still), "a standing hull ahead wasn't met: " + out);
        helper.assertTrue(Double.isNaN(far), "a rotor 3 blocks away counted as met: " + out);
        helper.assertTrue(!Double.isNaN(near), "the rotor's arm didn't meet what is in its way: " + out);
        helper.succeed();
    }

    /** When (s) within the next tick the entity box meets {@code hull} (a box in the ship's frame now), or NaN. */
    private static double meets(AABB hull, ShipSweptContact.Relative motion, AABB entity) {
        return ShipSweptContact.firstTouch(box -> box.intersects(hull), hull, motion, entity, 0.0, 0.05);
    }

    private static String when(double t) {
        return Double.isNaN(t) ? "not met (no impact)" : String.format(Locale.ROOT, "met after %.3f s", t);
    }

    // ------------------------------------------------------------------
    // Deck jolts
    // ------------------------------------------------------------------

    /**
     * A rider 3 blocks out on a rotor at 64 RPM, frames 1/60 s apart. Steady: no change at all
     * (the old rule, which didn't turn the deck's velocity with the ship, read omega^2 r dt).
     * The spin jumping 25% for one frame and coming back: a big change that doesn't stand. A ship
     * at 23 m/s stopped dead: all of it stands. The spin speeding up frame after frame: stands.
     */
    @GameTest(template = FLAT, batch = BATCH)
    public static void aDeckJoltMustStandToThrow(GameTestHelper helper) {
        double w0 = 64.0 * RPM;
        Vec3 rider = new Vec3(3.0, 1.0, 0.0);
        // steady spin
        ShipTracker.Frame f0 = spin(w0, 0.0);
        ShipTracker.Frame f1 = spin(w0, w0 * FRAME);
        ShipTracker.Frame f2 = spin(w0, 2.0 * w0 * FRAME);
        ShipTracker.Jolt steady = ShipTracker.joltBetween(f0, f1, f2, at(f2, rider));
        double oldRule = w0 * w0 * 3.0 * FRAME;
        // one-frame spike of +25% and back
        ShipTracker.Frame spike = spin(w0 * 1.25, w0 * FRAME * 1.25);
        ShipTracker.Frame back = spin(w0, w0 * FRAME * 2.25);
        ShipTracker.Jolt spiked = ShipTracker.joltBetween(f0, spike, back, at(back, rider));
        // dead stop from 23 m/s
        ShipTracker.Frame moving = new ShipTracker.Frame(Vec3.ZERO, new Vec3(23, 0, 0), Vec3.ZERO, new Quaterniond());
        ShipTracker.Frame stopped = new ShipTracker.Frame(new Vec3(0.38, 0, 0), Vec3.ZERO, Vec3.ZERO, new Quaterniond());
        ShipTracker.Jolt stop = ShipTracker.joltBetween(moving, stopped, stopped, new Vec3(1.0, 1.0, 0.0));
        // spinning up by 6 rad/s^2 over three frames
        double dw = 6.0 * FRAME;
        ShipTracker.Frame u0 = spin(w0, 0.0);
        ShipTracker.Frame u1 = spin(w0 + dw, (w0 + dw) * FRAME);
        ShipTracker.Frame u2 = spin(w0 + 2 * dw, (w0 + dw) * FRAME + (w0 + 2 * dw) * FRAME);
        ShipTracker.Jolt spinUp = ShipTracker.joltBetween(u0, u1, u2, at(u2, rider));
        String out = String.format(Locale.ROOT, "steady 64 RPM, rider 3 out: change %.3f m/s (the old rule read %.2f m/s a frame, "
                        + "grip 0.16 m/s a 60 Hz frame); one-frame 25%% spin spike: change %.2f m/s, %.0f%% still standing a frame "
                        + "later; dead stop from 23 m/s: change %.2f m/s, %.0f%% standing; spin-up at 6 rad/s^2: change %.3f m/s, "
                        + "%.0f%% standing", steady.change().length(), oldRule, spiked.change().length(), spiked.persisted() * 100,
                stop.change().length(), stop.persisted() * 100, spinUp.change().length(), spinUp.persisted() * 100);
        report("deck jolts", out);
        helper.assertTrue(steady.change().length() < 0.01, "a steady spin changes the deck: " + out);
        helper.assertTrue(spiked.change().length() > 4.0 && spiked.persisted() < 0.2, "the spike wasn't told apart: " + out);
        helper.assertTrue(Math.abs(stop.change().length() - 23.0) < 0.01 && stop.persisted() > 0.99, "the dead stop: " + out);
        helper.assertTrue(spinUp.persisted() > 1.5, "a sustained spin-up doesn't stand: " + out);
        helper.succeed();
    }

    /** A rotor spinning about +y at {@code w}, turned {@code angle} about it, centre of mass at the origin. */
    private static ShipTracker.Frame spin(double w, double angle) {
        return new ShipTracker.Frame(Vec3.ZERO, Vec3.ZERO, new Vec3(0.0, w, 0.0), new Quaterniond().rotateY(angle));
    }

    /** Where the deck point that sits at {@code local} (ship axes) is in frame {@code f}. */
    private static Vec3 at(ShipTracker.Frame f, Vec3 local) {
        org.joml.Vector3d v = new org.joml.Vector3d(local.x, local.y, local.z);
        f.rotation().transform(v);
        return f.position().add(v.x, v.y, v.z);
    }

    // ------------------------------------------------------------------
    // A hull striking a player
    // ------------------------------------------------------------------

    /**
     * A rotor arm at 7 m/s sweeping into a player standing on the ground, judged where the player
     * is simulated (its velocity before the hull shoved it: zero): kicked away at 1.3 x 7 m/s along
     * the arm's face and lifted. Judged with the shove counted as the player's own velocity (what
     * the server measured from the client's positions): it closes at nothing - no kick, and the
     * player was dragged along. An arm grazing past at 7 m/s while pressing in at 2: dragged along
     * by friction. A deck rising under the player: no strike (it stands on it). A player running
     * at 5 m/s into an arm creeping at 1.5: the player's own impact, not the hull's.
     */
    @GameTest(template = FLAT, batch = BATCH)
    public static void aHullStrikeOnAPlayerIsJudgedWithItsOwnVelocity(GameTestHelper helper) {
        GravityFrame frame = GravityFrame.DEFAULT;
        double e = 0.3, mu = 0.4, lift = 0.3, min = 1.0;
        Vec3 n = new Vec3(0.0, 0.0, -1.0);
        Vec3 hull = new Vec3(0.0, 0.0, -7.0);
        ShipContactPhysics.HullKick standing = ShipContactPhysics.hullKick(Vec3.ZERO, hull, n, true, frame, min, e, mu, lift);
        ShipContactPhysics.HullKick shoved = ShipContactPhysics.hullKick(hull, hull, n, true, frame, min, e, mu, lift);
        ShipContactPhysics.HullKick graze = ShipContactPhysics.hullKick(Vec3.ZERO, new Vec3(7.0, 0.0, -2.0), n, false, frame,
                min, e, mu, lift);
        ShipContactPhysics.HullKick rising = ShipContactPhysics.hullKick(Vec3.ZERO, new Vec3(0, 6, 0), new Vec3(0, 1, 0), true,
                frame, min, e, mu, lift);
        ShipContactPhysics.HullKick running = ShipContactPhysics.hullKick(new Vec3(0, 0, 5), new Vec3(0, 0, -1.5), n, true, frame,
                min, e, mu, lift);
        String out = String.format(Locale.ROOT, "standing player, arm at 7 m/s: %s; the same with the shove counted as its "
                        + "velocity: %s; arm grazing at 7 m/s pressing in at 2: %s; deck rising under it: %s; running at 5 m/s into "
                        + "an arm creeping at 1.5: %s", describe(standing), describe(shoved), describe(graze), describe(rising),
                describe(running));
        report("hull strikes on a player", out);
        helper.assertTrue(standing != null && Math.abs(standing.kick() - 9.1) < 0.01 && standing.lift() > 2.5,
                "the standing player wasn't flung: " + out);
        helper.assertTrue(shoved == null, "the shoved view still struck: " + out);
        helper.assertTrue(graze != null && graze.friction() > 1.0 && graze.change().x > 1.0, "no friction on the graze: " + out);
        helper.assertTrue(rising == null && running == null, "a deck under it or its own run counted as a hull strike: " + out);
        helper.succeed();
    }

    private static String describe(ShipContactPhysics.HullKick hit) {
        if (hit == null) return "no strike";
        return String.format(Locale.ROOT, "closing %.2f m/s, kick %.2f + friction %.2f + lift %.2f = (%.2f, %.2f, %.2f) m/s",
                hit.closing(), hit.kick(), hit.friction(), hit.lift(), hit.change().x, hit.change().y, hit.change().z);
    }

    // ------------------------------------------------------------------
    // What a seated rider feels
    // ------------------------------------------------------------------

    /**
     * The acceleration at a seat: a ship turning steadily at w about its centre gives w^2 r toward
     * the axis (3 blocks out at 64 RPM: 135 m/s^2); a ship braking from 23 m/s to 20 in a 1/60 s
     * frame, 180 m/s^2 backwards; a spin speeding up by 6 rad/s^2, alpha r along the turn.
     */
    @GameTest(template = FLAT, batch = BATCH)
    public static void aSeatFeelsTheTurn(GameTestHelper helper) {
        double w = 64.0 * RPM;
        Vec3 seat = new Vec3(3.0, 0.0, 0.0);
        Vec3 turning = ShipTracker.accelerationAt(spin(w, 0.0), spin(w, w * FRAME), FRAME, seat);
        ShipTracker.Frame fast = new ShipTracker.Frame(Vec3.ZERO, new Vec3(23, 0, 0), Vec3.ZERO, new Quaterniond());
        ShipTracker.Frame slower = new ShipTracker.Frame(new Vec3(0.36, 0, 0), new Vec3(20, 0, 0), Vec3.ZERO, new Quaterniond());
        Vec3 braking = ShipTracker.accelerationAt(fast, slower, FRAME, new Vec3(1.36, 0, 0));
        Vec3 spinUp = ShipTracker.accelerationAt(spin(0.0, 0.0), spin(6.0 * FRAME, 0.0), FRAME, seat);
        String out = String.format(Locale.ROOT, "steady turn at 64 RPM, seat 3 out: (%.1f, %.1f, %.1f) m/s^2 (w^2 r = %.1f toward the "
                        + "axis); braking 23 -> 20 m/s in a frame: (%.1f, %.1f, %.1f); spinning up at 6 rad/s^2: (%.2f, %.2f, %.2f) "
                        + "(alpha r = 18 along the turn)", turning.x, turning.y, turning.z, w * w * 3.0, braking.x, braking.y, braking.z,
                spinUp.x, spinUp.y, spinUp.z);
        report("seat acceleration", out);
        helper.assertTrue(Math.abs(turning.x + w * w * 3.0) < 0.01 && Math.abs(turning.z) < 1.0e-6, "the turn: " + out);
        helper.assertTrue(Math.abs(braking.x + 180.0) < 0.01, "the braking: " + out);
        helper.assertTrue(Math.abs(Math.abs(spinUp.z) - 18.0) < 0.01, "the spin-up: " + out);
        helper.succeed();
    }

    @SuppressWarnings("unused")
    private static String vec(Vec3 v) {
        return fmt(v.x) + ", " + fmt(v.y) + ", " + fmt(v.z);
    }
}
