package net.camacraft.fullstop.client.physics;

import com.mojang.logging.LogUtils;
import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.vs.ShipContact;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.physics.PhysicsOwnership;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.network.ShipContactPacket;
import net.camacraft.fullstop.common.physics.ShipSweptContact;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.camacraft.fullstop.server.physics.ship.ShipContactPhysics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Locale;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * A Valkyrien Skies hull running into the LOCAL player, noticed and answered where the player is
 * simulated: on its own client.
 *
 * <p>A player moves on its client, and that is where VS resolves a hull moving into it - by
 * shoving the player's position out of the hull, never touching its velocity. The server can't
 * judge this contact: its ship poses run ahead of what the client drew (the round trip plus VS's
 * interpolation; at 64 RPM a rotor arm three blocks out is about two blocks further round on the
 * server), and the player's motion it measures from the client's move reports already contains
 * the shoves. So a rotor sweeping into a player used to drag it along - shoved a little every tick,
 * with a hit on the server now and then that closed at almost nothing (the measured "own" velocity
 * already moved with the hull) - and fling it only when one of those hits happened to land.
 *
 * <p>Here, on the move in which VS shoves the player ({@code ShipHullSweep} sees a result the move
 * didn't ask for), the hull it now touches is found in the client's own ship pose; the hull's
 * velocity there is what the client drew it doing over the last tick. If the hull brings the
 * closing speed ({@code pushMinSpeed} or more), the player is given the same kick the server's
 * law gives any body - {@code (1 + entityRestitution)} times the closing speed along the contact
 * normal, Coulomb friction along the hull ({@code hullFriction}), lift off the ground
 * ({@code hitLift}) - at the end of this very tick, so the next move already carries it clear. The
 * ship's mass isn't known here: it is taken as far heavier than the player, and the server, which
 * bills the damage and pushes the ship back ({@link ShipContactPacket}), corrects the kick when the
 * ship turns out light. Never for the ship VS carries the player with, a hull rising under it (a
 * deck: standing on it), creative or spectating players, or while another mod owns the player's
 * motion (MOTION) or impacts (IMPACT: no report).
 */
public final class ClientShipContacts {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One reported hit per ship per this many ticks; within it the hull only pushes on (the server's cooldown too). */
    private static final int CONTACT_COOLDOWN_TICKS = 10;
    /** How far (blocks) around the player a ship's bounds are looked at for a strike this tick. */
    private static final double PREDICT_REACH = 4.0;
    private static final double TICK_SECONDS = 0.05;

    private ClientShipContacts() {
    }

    private record Touch(ShipRef ship, ShipContact contact, Vec3 point, Vec3 hull, double closing) {}

    /** {@link net.camacraft.fullstop.common.physics.ShipHullSweep.ShoveListener} for the local player. */
    public static void onShove(Entity self, Vec3 movement, Vec3 result, AABB before, long carriedBefore) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (self != player || player == null || !FullStopConfig.SERVER_SPEC.isLoaded() || !ShipCompat.collisionsActive()) return;
        boolean log = SERVER.debugShipContactLog.get();
        if (player.isSpectator() || player.getAbilities().instabuild || player.isPassenger()) {
            if (log) LOGGER.info("[FullStop] client: a hull shoved you by {} but you are {}: left to VS", fmt(result.subtract(movement)),
                    player.isPassenger() ? "riding" : "in creative/spectator");
            return;
        }
        FullStopCapability cap = FullStopCapability.grabCapability(player);
        if (cap == null) return;
        boolean motionFree = !PhysicsClaims.handsOff(player, PhysicsAspect.MOTION);
        boolean impactFree = !PhysicsClaims.handsOff(player, PhysicsAspect.IMPACT);
        if (!motionFree && !impactFree) return;

        GravityFrame frame = cap.getFrame();
        AABB after = before.move(result);
        // What the player was doing before the hull met it (m/s): the move it asked for.
        Vec3 own = movement.scale(20.0);
        Touch best = touch(player, after, own, carriedBefore);
        if (best == null) {
            if (log) LOGGER.info("[FullStop] client: shoved by {} b/t but no hull of a ship (other than the one carrying you) "
                    + "touches you: left to VS", fmt(result.subtract(movement)));
            return;
        }
        deliver(player, cap, frame, best, best.contact().normal(), own, motionFree, impactFree, false, "shoved you", log);
    }

    /**
     * A hull about to strike the local player during this tick, caught BEFORE its move: called at
     * the start of the player's tick. A fast hull (a rotor arm) crosses more than a player's width
     * in one tick, and VS, resolving only the overlap it ends up with, pushed the player out of
     * whichever face was nearest - often the back of the arm, which moves away from the player - so
     * the hit read as no hit and the hull went through ({@link #onShove} saw it "not strike"). Here
     * the player's box is swept against every nearby ship over the coming tick, in the ship's own
     * frame with the two bodies' relative motion ({@link ShipSweptContact}), and a hull that will
     * meet it with the closing speed of a hit strikes now: the kick goes straight into the
     * player's velocity, so this very move already carries it clear, and VS lets go of the player
     * ({@code letGo}) so it flies straight rather than turning with what struck it.
     */
    public static void predict(LocalPlayer player) {
        if (!FullStopConfig.SERVER_SPEC.isLoaded() || !ShipCompat.collisionsActive()) return;
        if (player.isSpectator() || player.getAbilities().instabuild || player.isPassenger()) return;
        FullStopCapability cap = FullStopCapability.grabCapability(player);
        if (cap == null) return;
        GravityFrame frame = cap.getFrame();
        // Gravity Unbound's capsule collides with ships itself
        if (!frame.isDefault()) return;
        boolean motionFree = !PhysicsClaims.handsOff(player, PhysicsAspect.MOTION);
        boolean impactFree = !PhysicsClaims.handsOff(player, PhysicsAspect.IMPACT);
        if (!motionFree && !impactFree) return;
        boolean log = SERVER.debugShipContactLog.get();

        // The player's motion in the world this tick (m/s): its own, plus the deck VS carries it with.
        Vec3 own = Gravity.getWorldVelocity(player, frame).scale(20.0).add(ShipCompat.shipDragVelocityMps(player));
        ShipRef deck = ShipCompat.carryingShip(player);
        if (deck != null && !(player.onGround() && ShipEntities.ticksSinceShip(player) <= 1)) deck = null;
        AABB box = player.getBoundingBox();
        Touch best = null;
        Vec3 bestNormal = null;
        double bestT = 0.0;
        for (ShipRef ship : Ships.intersecting(player.level(), box.inflate(PREDICT_REACH))) {
            // the deck it stands on moves with it: that one is VS's to carry. A ship whose hit is
            // cooling down is swept too: a rotor arm that flung the player catches up with it again
            // as air drag slows it (a 60 RPM arm within 3 ticks), and left to VS it pushed the player
            // out through its back, and vanilla stopped the player dead - it dropped straight down.
            // It pushes on instead (deliver: no second hit reported).
            if (ship.equals(deck)) continue;
            ShipSweptContact.Relative relative = ShipSweptContact.relativeTo(ship, own);
            AABB hullBounds = ship.worldAabb();
            if (relative == null || hullBounds == null) continue;
            ShipSweptContact.Meeting meeting = ShipSweptContact.sweep(ship, hullBounds, relative, box, 0.0, TICK_SECONDS);
            if (meeting == null) continue;
            double t = meeting.t();
            for (ShipContact contact : meeting.contacts()) {
                // (the sweep works in the world as the ship is posed now: the ship's frame now)
                Vec3 at = ShipSweptContact.facePoint(ship, contact, meeting.box().getCenter());
                if (at == null) continue;
                // the hull's velocity there and its face normal at the moment they meet, in the world
                Vec3 hull = relative.turn(relative.hullVelocity(at, t), t);
                Vec3 normal = relative.turn(contact.normal(), t);
                double closing = hull.subtract(own).dot(normal);
                if (best == null || closing > best.closing()) {
                    best = new Touch(ship, contact, at, hull, closing);
                    bestNormal = normal;
                    bestT = t;
                }
            }
        }
        if (best == null) return;
        boolean struck = deliver(player, cap, frame, best, bestNormal, own, motionFree, impactFree, true,
                String.format(Locale.ROOT, "is about to strike you (in %.0f ms)", bestT * 1000.0), log);
        if (struck && motionFree) {
            // VS moves the hull to where it ends this tick before it resolves the player's move, so the
            // spot the player starts from is already inside the hull by the ground the hull covers after
            // meeting it: resolved from there the move is stopped dead, kick and all. The hull has pushed
            // the player that far by then: start it at the hull's face as it ends the tick.
            double hullIn = best.hull().dot(bestNormal);
            double depth = hullIn * TICK_SECONDS - best.closing() * bestT + ShipSweptContact.SKIN;
            if (depth > 0.0) {
                player.setPos(player.position().add(bestNormal.scale(depth)));
                PhysicsOwnership.markDiscontinuity(player);
            }
        }
    }

    /**
     * Gives the player the server's two-body kick for {@code touch} (the ship taken as far heavier
     * than the player: the server corrects it for a light ship), reports the hit, and has VS let go
     * of the player if that ship carries it. {@code now}: straight into the velocity (before the
     * move), else queued for the end of the tick (after the move VS's collision cut). False when the
     * hull doesn't strike (not closing fast enough, or a deck coming up under the player).
     */
    private static boolean deliver(LocalPlayer player, FullStopCapability cap, GravityFrame frame, Touch touch, Vec3 normal,
                                   Vec3 own, boolean motionFree, boolean impactFree, boolean now, String how, boolean log) {
        double hullIn = touch.hull().dot(normal);
        // The server's two-body law with the ship taken as far heavier than the player. In zero
        // gravity a hull from below hits too (nothing stands on a deck) and there is no lift.
        boolean weightless = Gravity.isWeightless(player);
        ShipContactPhysics.HullKick hit = ShipContactPhysics.hullKick(own, touch.hull(), normal, player.onGround(), frame,
                SERVER.pushMinSpeed.get(), ShipContactPhysics.restitution(normal, frame, weightless), SERVER.hullFriction.get(),
                SERVER.hitLift.get() * ShipContactPhysics.liftShare(player), weightless);
        if (hit == null) {
            if (log) LOGGER.info("[FullStop] client: ship {} touches you but doesn't strike you (hull {} m/s into you, closing {} m/s, "
                            + "you {} m/s into it, normal ({})): left to VS", touch.ship().id(), fmt(hullIn), fmt(touch.closing()),
                    fmt(-own.dot(normal)), fmt(normal));
            return false;
        }
        Vec3 change = hit.change();
        long tick = player.level().getGameTime();
        boolean cooling = cap.isClientContactCooling(touch.ship().id(), tick);
        if (motionFree) {
            if (now) {
                Gravity.setWorldVelocity(player, Gravity.getWorldVelocity(player, frame).add(change.scale(0.05)), frame);
                player.hasImpulse = true;
                liftOff(player, frame, change);
            } else {
                cap.queueShipKick(change.scale(0.05));
            }
            // struck, it flies: VS carrying it on with the hull (round with a rotor) would bend its path
            ShipEntities.letGo(player, touch.ship().id(), false);
            ClientShipRelease.struckBy(touch.ship().id());
        }
        if (impactFree) {
            Vec3 local = touch.ship().toShip(touch.point());
            if (local != null) {
                PacketHandler.sendToServer(new ShipContactPacket(touch.ship().id(), touch.contact().pos(), touch.contact().face(),
                        local, normal, own, touch.hull(), touch.closing(), motionFree ? change : Vec3.ZERO, !cooling));
            }
        }
        if (!cooling) cap.markClientContact(touch.ship().id(), tick, CONTACT_COOLDOWN_TICKS);
        if (log) {
            LOGGER.info("[FullStop] client: ship {} {} ({}): hull {} m/s at the contact ({} m/s into you), you {} m/s, closing {} m/s; "
                            + "kick {} m/s + friction {} + lift {} = ({}) m/s, {}; normal ({})", touch.ship().id(),
                    cooling ? "pushes on" : "hits", how, fmt(touch.hull()), fmt(hullIn), fmt(own), fmt(touch.closing()),
                    fmt(hit.kick()), fmt(hit.friction()), fmt(hit.lift()), fmt(change),
                    motionFree ? "applied now" : "NOT applied (another mod owns your motion)", fmt(normal));
        }
        return true;
    }

    /**
     * A kick that lifts the player takes it off its feet at once. Vanilla picks a tick's friction
     * before the move, from what the player stands on, and applies it after: a player struck where
     * it stood lost 45% of the kick to ground friction on its first tick, though the kick's lift
     * had already carried it off the ground (a 60 RPM arm flung it at 11 m/s instead of 23).
     */
    static void liftOff(LocalPlayer player, GravityFrame frame, Vec3 change) {
        if (frame.vertical(change) > 0.0) player.setOnGround(false);
    }

    /**
     * The hull of any ship but the one carrying the player that touches the box it ended the move
     * in, with the fastest closing speed.
     */
    @Nullable
    private static Touch touch(LocalPlayer player, AABB after, Vec3 own, long carriedBefore) {
        Touch best = null;
        AABB probe = after.inflate(ShipSweptContact.SKIN);
        for (ShipRef ship : Ships.intersecting(player.level(), after.inflate(0.5))) {
            if (ship.id() == carriedBefore) continue;
            for (ShipContact contact : ship.contacts(probe)) {
                Vec3 point = ShipSweptContact.facePoint(ship, contact, after.getCenter());
                if (point == null) continue;
                Vec3 hull = hullVelocity(ship, point);
                double closing = hull.subtract(own).dot(contact.normal());
                if (best == null || closing > best.closing()) best = new Touch(ship, contact, point, hull, closing);
            }
        }
        return best;
    }

    /**
     * The velocity (m/s) of the ship's material at a world point, as this client drew the ship
     * moving over the last tick - the motion that actually shoved the player - or VS's synced
     * velocity when the pose didn't change.
     */
    static Vec3 hullVelocity(ShipRef ship, Vec3 point) {
        Vec3 before = ship.currentToPrevTick(point);
        if (before != null) {
            Vec3 moved = point.subtract(before).scale(20.0);
            if (moved.lengthSqr() > 1.0e-10) return moved;
        }
        Vec3 synced = ship.velocityAt(point);
        return synced != null ? synced : Vec3.ZERO;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }
}
