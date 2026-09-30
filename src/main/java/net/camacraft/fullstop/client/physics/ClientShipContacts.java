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
 * ({@code hitLift}), no faster than {@code maxHitLaunchSpeed} - at the end of this very tick, so the
 * next move already carries it clear. The ship's mass isn't known here: it is taken as far heavier
 * than the player, and the server, which bills the damage and pushes the ship back
 * ({@link ShipContactPacket}), corrects the kick when the ship turns out light. Never for the ship VS
 * carries the player with, a hull rising under it (a deck: standing on it), spectators, riders, or
 * while another mod owns the player's motion (MOTION) or impacts (IMPACT: no report). Creative
 * players are knocked about like anyone else; the server hurts them no more than any creative player.
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
        if (player.isSpectator() || player.isPassenger()) {
            if (log) LOGGER.info("[FullStop] client: a hull shoved you by {} but you are {}: left to VS", fmt(result.subtract(movement)),
                    player.isPassenger() ? "riding" : "spectating");
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
        Vec3 own = grounded(player, frame, movement.scale(20.0));
        Touch best = touch(player, after, own, carriedBefore);
        if (best == null) {
            if (log) LOGGER.info("[FullStop] client: shoved by {} b/t but no hull of a ship (other than the one carrying you) "
                    + "touches you: left to VS", fmt(result.subtract(movement)));
            return;
        }
        deliver(player, cap, frame, best, best.contact().normal(), own, motionFree, impactFree, false, "shoved you", log);
    }

    /**
     * A hull striking the local player, caught BEFORE its move: called at the start of the player's
     * tick. A fast hull (a rotor arm) crosses more than a player's width in one tick, and VS,
     * resolving only the overlap it ends up with, pushed the player out of whichever face was
     * nearest - often the back of the arm, which moves away from the player - so the hit read as no
     * hit and the hull went through ({@link #onShove} saw it "not strike").
     *
     * <p>Here the player's box is swept against every nearby ship as it moved from last tick's pose
     * to this tick's ({@link ShipSweptContact.Posed}: the poses this client just received, which it
     * draws over the frames of this tick - VS moves its ships before the player ticks). A hull that
     * met the player during that move with the closing speed of a hit strikes: the player is put
     * where the hull carried the spot they met at, against its face as it is now (the same place
     * the frames show the hull push it to), the kick goes straight into its velocity so this very
     * move carries it clear, and VS lets go of it ({@code letGo}) so it flies straight rather than
     * turning with what struck it. It used to sweep the coming tick with the ship's velocity and spin
     * extrapolated: that struck a tick before the drawn hull got there (a 120 RPM arm launched a
     * player two blocks before reaching it on screen), and a spin a little off met players the
     * hull never came near.</p>
     */
    public static void predict(LocalPlayer player) {
        if (!FullStopConfig.SERVER_SPEC.isLoaded() || !ShipCompat.collisionsActive()) return;
        if (player.isSpectator() || player.isPassenger()) return;
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
        // (a player Pehkui shrank moves by its motion scale's share of its deltaMovement: its real velocity)
        Vec3 own = grounded(player, frame, net.camacraft.camafy.compat.scale.EntityScale.realVelocity(player, Gravity.getWorldVelocity(player, frame)).scale(20.0))
                .add(ShipCompat.shipDragVelocityMps(player));
        ShipRef deck = ShipCompat.carryingShip(player);
        if (deck != null && !(player.onGround() && ShipEntities.ticksSinceShip(player) <= 1)) deck = null;
        AABB box = player.getBoundingBox();
        Touch best = null;
        Vec3 bestNormal = null;
        double bestT = 0.0;
        ShipSweptContact.Motion bestMotion = null;
        for (ShipRef ship : Ships.intersecting(player.level(), box.inflate(PREDICT_REACH))) {
            // the deck it stands on moves with it: that one is VS's to carry. A ship whose hit is
            // cooling down is swept too: a rotor arm that flung the player catches up with it again
            // as air drag slows it (a 60 RPM arm within 3 ticks), and left to VS it pushed the player
            // out through its back, and vanilla stopped the player dead - it dropped straight down.
            // It pushes on instead (deliver: no second hit reported).
            if (ship.equals(deck)) continue;
            // (its own velocity stands in for its motion over the tick: a position jump - a teleport, a respawn - is no motion)
            ShipSweptContact.Posed motion = ShipSweptContact.lastTick(ship, own);
            AABB hullBounds = ship.worldAabb();
            if (motion == null || hullBounds == null) continue;
            ShipSweptContact.Meeting meeting = ShipSweptContact.sweep(ship, hullBounds, motion, box, -TICK_SECONDS, 0.0);
            if (meeting == null) continue;
            double t = meeting.t();
            for (ShipContact contact : meeting.contacts()) {
                // (the sweep works in the world as the ship is posed now: the ship's frame now)
                Vec3 at = ShipSweptContact.facePoint(ship, contact, meeting.box().getCenter());
                if (at == null) continue;
                // the hull's velocity there (as it moved over the tick) and its face normal, as they are now
                Vec3 hull = hullVelocity(ship, at);
                Vec3 normal = ship.faceNormal(contact.face());
                if (normal == null) continue;
                double closing = hull.subtract(own).dot(normal);
                if (best == null || closing > best.closing()) {
                    best = new Touch(ship, contact, at, hull, closing);
                    bestNormal = normal;
                    bestT = t;
                    bestMotion = motion;
                }
            }
        }
        // a slow hull pushes rather than strikes, whether it is about to reach the player or already has: never a
        // jump back out through its face, only the speed that has the player end the tick against it
        if (best == null || ShipContactPhysics.gentle(player, best.closing())) {
            pushAhead(player, cap, frame, box, own, deck, motionFree, impactFree, log);
            return;
        }
        boolean struck = deliver(player, cap, frame, best, bestNormal, own, motionFree, impactFree, true,
                String.format(Locale.ROOT, "struck you (%.0f ms ago, as drawn this tick)", -bestT * 1000.0), log);
        if (struck && motionFree) {
            // The hull met the player part-way through its move and has gone on since, carrying the spot where they
            // met with it: put the player there, against the face as the hull is now - where the drawn hull pushes
            // it - so this move starts clear of the hull. (Resolved from inside the hull, the move was stopped dead,
            // kick and all; left behind a thin arm that had passed, the player ran into the arm's back.)
            Vec3 there = bestMotion.image(player.position(), bestT).add(bestNormal.scale(ShipSweptContact.SKIN));
            if (there.distanceToSqr(player.position()) > 1.0e-8) {
                player.setPos(there);
                PhysicsOwnership.markDiscontinuity(player);
            }
        }
    }

    /**
     * The player's velocity (m/s, world) with what the ground takes back left out: standing, vanilla adds a tick's gravity
     * that the ground then stops, so a grounded player reads 1.57 m/s downward while going nowhere. Counted as motion,
     * a hull's friction "matched" it and lifted a player it pushed off its feet on every push (a hop, on which the player
     * slid on ahead of the hull and fell behind it again).
     */
    private static Vec3 grounded(LocalPlayer player, GravityFrame frame, Vec3 own) {
        if (!player.onGround()) return own;
        double down = frame.vertical(own);
        return down < 0.0 ? own.subtract(frame.up().scale(down)) : own;
    }

    /**
     * A slow hull (under {@code gentlePushSpeed} at the player's size) reaching the player within the coming tick: the
     * player gets just the speed along the face's normal that has it end the tick against the face - where the face will
     * be then - and no more, and the hull's friction for that share. A push is a contact, not a collision: kicked to the
     * hull's speed only once it had sunk in, a pushed player slid ahead, slowed on the ground (to half its speed a tick),
     * was caught again two or three ticks later and snapped back out of the hull and kicked again - the owner's stuttery
     * push. Now it moves with the face, by as much each tick as the face would have pushed it in. The needed speed follows
     * the face as this client moves it (from last tick's pose to this one's), for a face that goes on as it came; nothing
     * moves before the face gets there (the needed speed is nil while the face is further off than it comes in a tick),
     * so it never throws a player early, as the old look-ahead for hits did. A face the player is already sunk into is
     * left over the tick the same way, never by setting the player back out through it (such a jump, against a face
     * that had turned since, left a pushed player stuck in the arm for three ticks). Fast hulls strike: {@link #predict}'s sweep.
     *
     * @return whether it pushed the player
     */
    private static boolean pushAhead(LocalPlayer player, FullStopCapability cap, GravityFrame frame, AABB box, Vec3 own,
                                     @Nullable ShipRef deck, boolean motionFree, boolean impactFree, boolean log) {
        if (!motionFree) return false;
        double motionScale = net.camacraft.camafy.compat.scale.EntityScale.motion(player);
        // no slow face comes further than this in a tick
        double margin = SERVER.gentlePushSpeed.get() * motionScale * TICK_SECONDS;
        boolean weightless = Gravity.isWeightless(player);
        Touch best = null;
        Vec3 bestChange = null;
        double bestKick = 0.0;
        for (ShipRef ship : Ships.intersecting(player.level(), box.inflate(margin + 0.5))) {
            if (ship.equals(deck)) continue;
            double skin = ShipSweptContact.skin(ShipSweptContact.feature(box, ship.scale()));
            double reach = margin + skin;
            for (ShipContact contact : ship.contacts(box.inflate(reach))) {
                Vec3 normal = contact.normal();
                if (ShipContactPhysics.fromBelow(normal, frame, weightless)) continue; // a deck rising under it: VS's
                Vec3 at = ShipSweptContact.facePoint(ship, contact, box.getCenter());
                if (at == null) continue;
                Vec3 hull = steadyHullVelocity(ship, at);
                double hullIn = hull.dot(normal);
                double ownOut = own.dot(normal);
                double closing = hullIn - ownOut;
                if (!(hullIn > 0.0) || !(closing > 0.0)) continue;
                if (!ShipContactPhysics.gentle(player, closing)) continue; // a hit: the sweep's, when it lands
                // how far the player's box is from the face's plane (negative: sunk into it), and the speed along its
                // normal that ends the tick against it
                double half = 0.5 * (Math.abs(normal.x) * box.getXsize() + Math.abs(normal.y) * box.getYsize()
                        + Math.abs(normal.z) * box.getZsize());
                double gap = box.getCenter().subtract(at).dot(normal) - half;
                if (gap > reach) continue;
                // the face's speed, and a share of the gap still to close or open (all of it where the player has sunk in)
                double need = hullIn - (gap < 0.0 ? 1.0 : GAP_SHARE) * (gap - skin) / TICK_SECONDS;
                double kick = need - ownOut;
                if (!(kick > 1.0e-4) || kick <= bestKick) continue;
                // the hull's friction for that share of the push: it drags the player along its face, no further than
                // until the two move together there
                Vec3 relative = hull.subtract(own);
                Vec3 slip = relative.subtract(normal.scale(relative.dot(normal)));
                double slipSpeed = slip.length();
                double friction = slipSpeed > 1.0e-6 ? Math.min(SERVER.hullFriction.get() * kick, slipSpeed) : 0.0;
                Vec3 change = normal.scale(kick).add(friction > 0.0 ? slip.scale(friction / slipSpeed) : Vec3.ZERO);
                best = new Touch(ship, contact, at, hull, closing);
                bestChange = change;
                bestKick = kick;
            }
        }
        if (best == null) return false;
        // real motion in the world, into the player's own velocity (a shrunk player moves by its share of it: BodyPush)
        Vec3 changeBpt = net.camacraft.camafy.compat.scale.EntityScale.velocityFor(player, bestChange.scale(0.05));
        Gravity.setWorldVelocity(player, Gravity.getWorldVelocity(player, frame).add(changeBpt), frame);
        player.hasImpulse = true;
        if (impactFree) {
            // the push back on the ship (no hit: nothing to hurt, no hit sound)
            Vec3 local = best.ship().toShip(best.point());
            if (local != null) {
                PacketHandler.sendToServer(new ShipContactPacket(best.ship().id(), best.contact().pos(), best.contact().face(),
                        local, best.contact().normal(), own, best.hull(), best.closing(), bestChange, false));
            }
        }
        if (log) {
            LOGGER.info("[FullStop] client: ship {} pushes you along (closing {} m/s, its face {} m/s into you): +{} m/s along "
                            + "its normal, change ({}) m/s", best.ship().id(), fmt(best.closing()), fmt(best.hull().dot(best.contact().normal())),
                    fmt(bestKick), fmt(bestChange));
        }
        return true;
    }

    /**
     * The share of the gap to the face a push closes in one tick (see {@link #pushAhead}). Closing all of it at once made
     * the pushed player move in step with the tick-to-tick unevenness of the ship's own motion, half a step behind it:
     * Valkyrien Skies moves a ship by uneven steps on this client (a steady 10 RPM rotor turned 2.5 and 3.5 degrees on
     * alternate ticks), and a player given each last step's speed moved 5 and 3 m/s on alternate ticks.
     */
    private static final double GAP_SHARE = 0.3;

    /** Per ship: the tick last asked about, that tick's PREV_TICK pose, and the pose a tick before that. */
    private static final class PoseHistory {
        long tick = Long.MIN_VALUE;
        org.joml.Matrix4d prev;
        org.joml.Matrix4d twoBack;
    }

    private static final java.util.Map<Long, PoseHistory> HISTORY = new java.util.HashMap<>();

    /** The ship's pose two ticks back (last tick's PREV_TICK), or null until it is known. */
    @Nullable
    private static org.joml.Matrix4d twoTicksBack(ShipRef ship, long tick) {
        PoseHistory h = HISTORY.computeIfAbsent(ship.id(), id -> new PoseHistory());
        if (h.tick != tick) {
            h.twoBack = h.tick == tick - 1 ? h.prev : null;
            h.prev = ship.shipToWorld(ShipRef.Pose.PREV_TICK);
            h.tick = tick;
            if (HISTORY.size() > 64) HISTORY.values().removeIf(old -> old.tick < tick - 20);
        }
        return h.twoBack;
    }

    /**
     * The hull's velocity at {@code point} (m/s, world) over the last two ticks: its motion from its pose two ticks back to
     * this one's, halved. Valkyrien Skies moves a ship by uneven steps on this client, long and short on alternate ticks;
     * over two ticks the speed is its own. Falls back to {@link #hullVelocity} until two ticks are known.
     */
    private static Vec3 steadyHullVelocity(ShipRef ship, Vec3 point) {
        long tick = Minecraft.getInstance().level == null ? 0L : Minecraft.getInstance().level.getGameTime();
        org.joml.Matrix4d twoBack = twoTicksBack(ship, tick);
        Vec3 local = twoBack == null ? null : ship.toShip(point);
        if (local == null) return hullVelocity(ship, point);
        org.joml.Vector3d then = twoBack.transformPosition(new org.joml.Vector3d(local.x, local.y, local.z));
        Vec3 moved = point.subtract(then.x, then.y, then.z).scale(10.0);
        return moved.lengthSqr() > 1.0e-10 ? moved : hullVelocity(ship, point);
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
        // a slow hull only pushes: no bounce, no lift (gentlePushSpeed); the closing speed at the player's own scale
        boolean gentle = ShipContactPhysics.gentle(player, touch.closing());
        // (a slime hull springs the player back at slimeRestitution; the server hurts it not at all)
        net.minecraft.world.level.block.state.BlockState hullBlock = player.level().getBlockState(touch.contact().pos());
        ShipContactPhysics.HullKick hit = ShipContactPhysics.hullKick(own, touch.hull(), normal, player.onGround(), frame,
                SERVER.pushMinSpeed.get() * net.camacraft.camafy.compat.scale.EntityScale.motion(player),
                gentle ? 0.0 : ShipContactPhysics.restitution(normal, frame, weightless, hullBlock), SERVER.hullFriction.get(),
                gentle ? 0.0 : SERVER.hitLift.get() * ShipContactPhysics.liftShare(player), weightless);
        if (hit == null) {
            if (log) LOGGER.info("[FullStop] client: ship {} touches you but doesn't strike you (hull {} m/s into you, closing {} m/s, "
                            + "you {} m/s into it, normal ({})): left to VS", touch.ship().id(), fmt(hullIn), fmt(touch.closing()),
                    fmt(-own.dot(normal)), fmt(normal));
            return false;
        }
        Vec3 change = ShipContactPhysics.capLaunch(player, own, hit.change());
        long tick = player.level().getGameTime();
        boolean cooling = cap.isClientContactCooling(touch.ship().id(), tick);
        if (motionFree) {
            // real motion in the world, into the player's own velocity: a player Pehkui shrank moves by its motion
            // scale's share of it (BodyPush)
            Vec3 changeBpt = net.camacraft.camafy.compat.scale.EntityScale.velocityFor(player, change.scale(0.05));
            if (now) {
                Gravity.setWorldVelocity(player, Gravity.getWorldVelocity(player, frame).add(changeBpt), frame);
                player.hasImpulse = true;
                liftOff(player, frame, change);
            } else {
                cap.queueShipKick(changeBpt);
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
