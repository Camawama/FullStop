package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.vs.ShipCollision;
import net.camacraft.camafy.compat.vs.ShipCollisions;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Slime and honey where Valkyrien Skies ships meet terrain or each other. At each contact of a
 * crash the two blocks that touch are looked up (the hull's and the terrain's, or both hulls'):
 * <ul>
 *   <li><b>Slime</b> (block tag {@code fullstop:bouncy}) on either side, and no honey in the crash:
 *   the ship springs back off it like an entity off slime. Whatever VS's own solver gave back, the
 *   rest is added at the contact, so that the speed at the contact leaving the surface is
 *   {@code slimeRestitution x} the speed it met it with ({@code sqrt(slimeRestitution)} with slime on
 *   both sides). Ship against ship: equal and opposite impulses, with the pair's reduced
 *   effective mass.</li>
 *   <li><b>Honey</b> ({@code fullstop:gluey}) on either side, slime or not on the other: no
 *   bounce - whatever VS gave back is taken out, and the hull's slide along the honey too, as far
 *   as the crash's own impulse grips it - and the ship is offered to {@link ShipGlue}, which glues
 *   the two together once they have settled.</li>
 * </ul>
 * The speed a contact met at is the fastest the hull moved into it over the last few physics
 * frames (VS's PhysX measures its contacts after its solver has bounced the ship off, and a crash
 * can reach the game a frame late); the speed it leaves at is the ship's latest frame, read when
 * that frame shows the crash (a bounce waits for it, a few ticks at most). A slime bounce reads
 * both at the ship's centre of mass and pushes there: it bounces the ship, it doesn't turn it.
 * Pushed at the patch that met, off the ship's centre, every bounce added spin, and a spinning
 * ship's corner met the ground far faster than the ship itself moved - the spin went into the
 * next bounce, which sent it higher and spinning harder (the owner: slime ships gathered spin
 * and bounced ever higher). VS's own collision still turns a ship landing on an edge. Slime also
 * grips: each bounce takes {@link #SLIME_SPIN_LOSS} of the ship's spin out, as a rubbery surface
 * does. Honey holds at the patch that met. Momentum goes in through
 * {@link ShipImpulses#applyCapped} (so {@code maxForceNewtons} bounds it; a bounce too big for one
 * physics step is finished over the next frames). Works with VS's own collision events (PhysX,
 * Jolt) and Camafy's fallback for the classic backend alike, in any gravity.
 */
public final class ShipSlimeHoney {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Ticks back a contact's approach may be read from (see {@link ShipTracker.State#recentInto}). */
    private static final int APPROACH_TICKS = 4;
    /** Ticks a ship (or pair) isn't bounced again after a bounce: the same crash's later contacts. */
    private static final int COOLDOWN_TICKS = 6;
    /** The share of a ship's spin a slime bounce takes out (slime's grip on the hull as it springs back). */
    private static final double SLIME_SPIN_LOSS = 0.4;
    /** Ticks a queued bounce may wait for the frame that shows the crash, or finish a capped push. */
    private static final int PENDING_TICKS = 8;
    /** A hull block whose face is this close (m) to a crash's contact plane is part of the patch that met. */
    private static final double PATCH_GAP = 0.35;
    /** Hulls bigger than this (shipyard cells) aren't searched for the patch that met (the contacts' middle is used). */
    private static final long PATCH_MAX_CELLS = 262_144;
    /** Below this (m/s) a bounce is done. */
    private static final double DONE_SPEED = 0.02;
    /**
     * Honey's grip on a hull sliding along it: the slide it stops, at most this times the speed the
     * crash met the honey with (a coefficient of friction on the crash's own impulse).
     */
    private static final double HONEY_GRIP = 1.0;

    /**
     * A bounce (or, target 0, a rebound to take out) queued for a ship or a pair. {@code normal}
     * points from the other body (terrain, or ship {@code b}) toward ship {@code a}; {@code target}
     * is the separating speed at {@code point} wanted along it.
     */
    private static final class Pending {
        final long a;
        final long b; // ShipRef.NO_ID: terrain
        final Vec3 point;
        final Vec3 normal;
        final double target;
        final double met;
        final String what;
        final long expires;
        /** New physics frames to wait before looking again (an impulse queued isn't in the frame yet). */
        int waitFrames;
        boolean applied;
        /** A slime bounce: read and pushed at the centre of mass (honey holds at {@link #point}). */
        boolean throughCentre;

        Pending(long a, long b, Vec3 point, Vec3 normal, double target, double met, String what, long expires) {
            this.a = a;
            this.b = b;
            this.point = point;
            this.normal = normal;
            this.target = target;
            this.met = met;
            this.what = what;
            this.expires = expires;
        }
    }

    private static final Map<ServerLevel, List<Pending>> PENDING = new WeakHashMap<>();
    /** Per level: ship id (or pair key) -> the tick until which it isn't bounced again. */
    private static final Map<ServerLevel, Map<Long, Long>> COOLDOWN = new WeakHashMap<>();

    private ShipSlimeHoney() {
    }

    /** Whether a block is slime-like to ships (tag fullstop:bouncy). */
    public static boolean bouncy(@Nullable BlockState state) {
        return state != null && state.is(FullStopTags.BOUNCY);
    }

    /** Whether a block is honey-like to ships (tag fullstop:gluey). */
    public static boolean gluey(@Nullable BlockState state) {
        return state != null && state.is(FullStopTags.GLUEY);
    }

    static boolean enabled() {
        return SERVER.slimeBounce.get() || SERVER.honeyGlue.get();
    }

    /** Once per level tick, after the crash rules: this tick's crashes, then the queued bounces. */
    static void process(ServerLevel level, ShipTracker tracker, long now) {
        if (!enabled()) {
            PENDING.remove(level);
            return;
        }
        for (ShipCollision collision : ShipCollisions.thisTick(level)) {
            if (collision.isTerrain()) {
                terrain(level, tracker, collision, now);
            } else if (collision.ship().id() < collision.other().id()) {
                // each pair comes twice (once from each side): handled once
                pair(level, tracker, collision, now);
            }
        }
        applyPending(level, tracker, now);
    }

    // ------------------------------------------------------------------
    // Ship against terrain
    // ------------------------------------------------------------------

    private static void terrain(ServerLevel level, ShipTracker tracker, ShipCollision collision, long now) {
        ShipRef ship = collision.ship();
        ShipTracker.State state = tracker.get(ship.id(), now);
        if (state == null || state.inGrace(now) || ship.isStatic()) return;

        List<ShipTerrainImpacts.Strike> slime = new ArrayList<>();
        List<ShipTerrainImpacts.Strike> honey = new ArrayList<>();
        boolean slimeBoth = false;
        double fastest = 0.0;
        for (ShipCollision.Contact contact : collision.contacts()) {
            double into = Math.max(contact.approachMps(), Math.max(
                    -state.prevVelocityAt(contact.point()).dot(contact.normal()),
                    state.recentInto(contact.point(), contact.normal(), now, APPROACH_TICKS)));
            ShipTerrainImpacts.Strike strike = ShipTerrainImpacts.resolve(level, ship, contact, into);
            boolean slimeHere = bouncy(strike.terrain()) || bouncy(strike.hull());
            boolean honeyHere = gluey(strike.terrain()) || gluey(strike.hull());
            if (honeyHere) {
                honey.add(strike);
            } else if (slimeHere) {
                slime.add(strike);
                slimeBoth |= bouncy(strike.terrain()) && bouncy(strike.hull());
                fastest = Math.max(fastest, into);
            }
        }

        if (!honey.isEmpty()) {
            if (!SERVER.honeyGlue.get()) return;
            // honey wins: no bounce off anything in this crash, and the two stick
            if (collision.started()) {
                Vec3[] at = centre(honey);
                queue(level, now, new Pending(ship.id(), ShipRef.NO_ID, patchCentre(level, ship, at[0], at[1], null), at[1],
                        0.0, maxSpeed(honey), "honey on terrain", now + PENDING_TICKS), ship.id());
                ShipGlue.offerTerrain(level, ship, honey, now);
            }
            return;
        }
        if (slime.isEmpty() || !SERVER.slimeBounce.get() || !collision.started()) return;
        if (fastest < SERVER.slimeMinSpeed.get() || coolingDown(level, ship.id(), now)) return;
        double e = restitution(slimeBoth);
        Vec3[] at = centre(slime);
        Vec3 patch = patchCentre(level, ship, at[0], at[1], null);
        // how fast the ship itself (its centre of mass) met the surface (see the class notes)
        Vec3 com = state.position();
        double met = Math.max(-state.prevVelocityAt(com).dot(at[1]), state.recentInto(com, at[1], now, APPROACH_TICKS));
        if (met < SERVER.slimeMinSpeed.get()) return;
        Pending bounce = new Pending(ship.id(), ShipRef.NO_ID, patch, at[1], e * met, met,
                slimeBoth ? "slime hull on slime" : "slime against terrain", now + PENDING_TICKS);
        bounce.throughCentre = true;
        queue(level, now, bounce, ship.id());
    }

    // ------------------------------------------------------------------
    // Ship against ship
    // ------------------------------------------------------------------

    private static void pair(ServerLevel level, ShipTracker tracker, ShipCollision collision, long now) {
        ShipRef a = collision.ship();
        ShipRef b = collision.other();
        ShipTracker.State stateA = tracker.get(a.id(), now);
        ShipTracker.State stateB = tracker.get(b.id(), now);
        if (stateA == null || stateB == null || stateA.inGrace(now) || stateB.inGrace(now)) return;
        if (a.isStatic() && b.isStatic()) return;

        List<Vec3[]> slimePoints = new ArrayList<>();
        List<Vec3[]> honeyPoints = new ArrayList<>();
        List<BlockPos[]> honeyBlocks = new ArrayList<>();
        boolean slimeBoth = false;
        double fastest = 0.0;
        double honeyFastest = 0.0;
        for (ShipCollision.Contact contact : collision.contacts()) {
            Vec3 point = contact.point();
            Vec3 normal = contact.normal(); // from b toward a
            double closing = Math.max(contact.approachMps(), Math.max(
                    -stateA.prevVelocityAt(point).subtract(stateB.prevVelocityAt(point)).dot(normal),
                    stateA.recentClosing(stateB, point, normal, now, APPROACH_TICKS)));
            BlockPos posA = ShipTerrainImpacts.shipBlock(level, a, point, normal, 1.0);
            BlockPos posB = ShipTerrainImpacts.shipBlock(level, b, point, normal, -1.0);
            BlockState blockA = posA != null ? ShipTerrainImpacts.loadedState(level, posA) : null;
            BlockState blockB = posB != null ? ShipTerrainImpacts.loadedState(level, posB) : null;
            if (gluey(blockA) || gluey(blockB)) {
                honeyPoints.add(new Vec3[]{point, normal});
                honeyBlocks.add(new BlockPos[]{posA, posB});
                honeyFastest = Math.max(honeyFastest, closing);
            } else if (bouncy(blockA) || bouncy(blockB)) {
                slimePoints.add(new Vec3[]{point, normal});
                slimeBoth |= bouncy(blockA) && bouncy(blockB);
                fastest = Math.max(fastest, closing);
            }
        }

        long key = pairKey(a.id(), b.id());
        if (!honeyPoints.isEmpty()) {
            if (!SERVER.honeyGlue.get() || !collision.started()) return;
            Vec3[] at = centrePoints(honeyPoints);
            queue(level, now, new Pending(a.id(), b.id(), patchCentre(level, a, at[0], at[1], b), at[1], 0.0, honeyFastest,
                    "honey between ships", now + PENDING_TICKS), key);
            ShipGlue.offerShips(level, a, b, honeyPoints, honeyBlocks, now);
            return;
        }
        if (slimePoints.isEmpty() || !SERVER.slimeBounce.get() || !collision.started()) return;
        if (fastest < SERVER.slimeMinSpeed.get() || coolingDown(level, key, now)) return;
        double e = restitution(slimeBoth);
        Vec3[] at = centrePoints(slimePoints);
        Vec3 patch = patchCentre(level, a, at[0], at[1], b);
        // how fast the two ships themselves (their centres of mass) closed along the contact's normal
        Vec3 comA = stateA.position(), comB = stateB.position();
        double met = Math.max(-stateA.prevVelocityAt(comA).subtract(stateB.prevVelocityAt(comB)).dot(at[1]),
                -stateA.velocityAt(comA).subtract(stateB.velocityAt(comB)).dot(at[1]));
        if (met < SERVER.slimeMinSpeed.get()) return;
        Pending bounce = new Pending(a.id(), b.id(), patch, at[1], e * met, met,
                slimeBoth ? "slime hulls" : "slime between ships", now + PENDING_TICKS);
        bounce.throughCentre = true;
        queue(level, now, bounce, key);
    }

    /**
     * Honey holding a ship to the ground ({@code other} {@link ShipJoints#GROUND}) or to another ship
     * while their glue sets ({@link ShipGlue}): takes out the parting and sliding at {@code point}
     * once more ({@code normal} from the other body toward the ship).
     */
    static void hold(ServerLevel level, long ship, long other, Vec3 point, Vec3 normal, long now) {
        long b = other == net.camacraft.camafy.compat.vs.ShipJoints.GROUND ? ShipRef.NO_ID : other;
        List<Pending> list = PENDING.computeIfAbsent(level, l -> new ArrayList<>());
        for (Pending pending : list) {
            if (pending.a == ship && pending.b == b) return;
        }
        // grip: the slide it may stop is as big as it needs to be
        list.add(new Pending(ship, b, point, normal, 0.0, 100.0, "honey holding while the glue sets", now + PENDING_TICKS));
    }

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    /** Restitution at a contact: slimeRestitution, or its square root with slime on both sides. */
    static double restitution(boolean slimeBothSides) {
        double e = SERVER.slimeRestitution.get();
        return slimeBothSides ? Math.sqrt(e) : e;
    }

    private static void queue(ServerLevel level, long now, Pending pending, long cooldownKey) {
        List<Pending> list = PENDING.computeIfAbsent(level, l -> new ArrayList<>());
        // one per ship or pair at a time: the crash's later records are the same crash
        for (Pending other : list) {
            if (other.a == pending.a && other.b == pending.b) return;
        }
        list.add(pending);
        COOLDOWN.computeIfAbsent(level, l -> new HashMap<>()).put(cooldownKey, now + COOLDOWN_TICKS);
        debug("ship {}{}: {} met at {} m/s at {}, normal {} -> wants {} m/s off it", pending.a,
                pending.b == ShipRef.NO_ID ? "" : " and ship " + pending.b, pending.what, fmt(pending.met),
                vec(pending.point), vec(pending.normal), fmt(pending.target));
    }

    private static boolean coolingDown(ServerLevel level, long key, long now) {
        Map<Long, Long> map = COOLDOWN.get(level);
        if (map == null) return false;
        Long until = map.get(key);
        if (until == null) return false;
        if (until < now) {
            map.remove(key);
            return false;
        }
        return true;
    }

    private static void applyPending(ServerLevel level, ShipTracker tracker, long now) {
        List<Pending> list = PENDING.get(level);
        if (list == null || list.isEmpty()) return;
        for (Iterator<Pending> it = list.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (now > p.expires) {
                it.remove();
                continue;
            }
            if (apply(level, tracker, p, now)) it.remove();
        }
    }

    /** One look at a queued bounce; true when it is done with. */
    private static boolean apply(ServerLevel level, ShipTracker tracker, Pending p, long now) {
        ShipRef a = Ships.byId(level, p.a);
        ShipTracker.State stateA = tracker.get(p.a, now);
        if (a == null || stateA == null) return true;
        ShipRef b = null;
        ShipTracker.State stateB = null;
        if (p.b != ShipRef.NO_ID) {
            b = Ships.byId(level, p.b);
            stateB = tracker.get(p.b, now);
            if (b == null || stateB == null) return true;
        }
        if (p.waitFrames > 0) {
            if (stateA.newFrame() || (stateB != null && stateB.newFrame())) p.waitFrames--;
            return false;
        }

        // separating speed now (positive: leaving the surface): of the ships themselves for a bounce, at the contact for honey
        Vec3 va = stateA.velocityAt(p.throughCentre ? stateA.position() : p.point);
        Vec3 vb = stateB == null ? Vec3.ZERO : stateB.velocityAt(p.throughCentre ? stateB.position() : p.point);
        double leaving = va.subtract(vb).dot(p.normal);
        // the latest frame still shows the ship coming in: the crash isn't in it yet
        if (!p.applied && leaving < -0.25 * Math.max(p.met, 1.0)) return false;

        // the change of relative velocity at the contact this look wants
        Vec3 change;
        if (p.target > 0.0) {
            double missing = p.target - leaving;
            if (missing <= DONE_SPEED) return true;
            change = p.normal.scale(missing);
        } else {
            // honey: take out whatever rebound VS gave, and the slide along the surface too, as far
            // as honey's grip goes (the speed the crash met it with)
            Vec3 relative = va.subtract(vb);
            Vec3 slide = relative.subtract(p.normal.scale(leaving));
            change = leaving > DONE_SPEED ? p.normal.scale(-leaving) : Vec3.ZERO;
            double sliding = slide.length();
            if (sliding > DONE_SPEED) {
                double stop = Math.min(sliding, HONEY_GRIP * Math.max(p.met, 0.0));
                change = change.add(slide.scale(-stop / sliding));
            }
            if (change.length() <= DONE_SPEED) return true;
        }
        double wanted = change.length();
        Vec3 direction = change.scale(1.0 / wanted);

        double massA = a.isStatic() ? Double.POSITIVE_INFINITY : stateA.mass();
        double massB = b == null || b.isStatic() ? Double.POSITIVE_INFINITY : stateB.mass();
        // a bounce pushes through each centre of mass, where a ship's effective mass is its mass
        double effA = p.throughCentre ? massA : ShipInertia.effectiveMassKg(a, massA, p.point, direction);
        double effB = b == null ? Double.POSITIVE_INFINITY
                : p.throughCentre ? massB : ShipInertia.effectiveMassKg(b, massB, p.point, direction);
        double inverse = (Double.isFinite(effA) && effA > 0 ? 1.0 / effA : 0.0)
                + (Double.isFinite(effB) && effB > 0 ? 1.0 / effB : 0.0);
        if (!(inverse > 0.0)) return true;
        double impulse = wanted / inverse;
        Vec3 j = direction.scale(impulse);
        double given = 0.0;
        if (Double.isFinite(massA)) {
            given = ShipImpulses.applyCapped(level, a, j, p.throughCentre ? stateA.position() : p.point).length();
        }
        if (b != null && Double.isFinite(massB)) {
            given = Math.max(given, ShipImpulses.applyCapped(level, b, j.scale(-1.0), p.throughCentre ? stateB.position() : p.point).length());
        }
        if (p.throughCentre && !p.applied) {
            // slime grips as it springs back: part of the spin goes with the bounce
            if (Double.isFinite(massA)) net.camacraft.camafy.compat.vs.ShipForces.dampSpin(level, a.id(), SLIME_SPIN_LOSS);
            if (b != null && Double.isFinite(massB)) net.camacraft.camafy.compat.vs.ShipForces.dampSpin(level, b.id(), SLIME_SPIN_LOSS);
        }
        debug("ship {}{}: {} - leaving at {} m/s, wants {}: {} N s along {} at {} (effective mass {} kg){}", p.a,
                b == null ? "" : " and ship " + p.b, p.what, fmt(leaving), fmt(p.target), fmt(impulse), vec(direction),
                vec(p.point), fmt(1.0 / inverse),
                given < Math.abs(impulse) * 0.99 ? " - capped by maxForceNewtons, finishing next frames" : "");
        if (!(given > 0.0) || given >= Math.abs(impulse) * 0.99) return true;
        // capped: look again once the frames show what went in
        p.applied = true;
        p.waitFrames = 2;
        return false;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Where a crash meets the hull: the middle of the hull's faces that lie on the contact plane
     * (through {@code point}, normal {@code normal} pointing into the ship) with terrain, or the
     * other ship's blocks, just beyond them. Backends report a flat landing differently - PhysX
     * five points spread over the face, Camafy's fallback up to eight, Jolt a single corner - and
     * a bounce pushed at one corner would spin the ship instead of bouncing it, so the patch is
     * found from the blocks themselves. {@code point} when no face is found (or the hull is too big
     * to look through).
     */
    static Vec3 patchCentre(ServerLevel level, ShipRef ship, Vec3 point, Vec3 normal, @Nullable ShipRef other) {
        Vec3 ex = ship.dirToWorld(new Vec3(1, 0, 0));
        Vec3 ey = ship.dirToWorld(new Vec3(0, 1, 0));
        Vec3 ez = ship.dirToWorld(new Vec3(0, 0, 1));
        net.minecraft.world.level.levelgen.structure.BoundingBox box = ship.shipyardBlocks();
        if (ex == null || ey == null || ez == null || box == null) return point;
        if ((long) box.getXSpan() * box.getYSpan() * box.getZSpan() > PATCH_MAX_CELLS) return point;
        double half = 0.5 * (Math.abs(normal.dot(ex)) + Math.abs(normal.dot(ey)) + Math.abs(normal.dot(ez)));
        double[] sum = new double[4];
        ship.forEachBlock(state -> !state.isAir(), (pos, state) -> {
            Vec3 centre = ship.toWorld(Vec3.atCenterOf(pos));
            if (centre == null) return;
            double gap = centre.subtract(point).dot(normal) - half;
            if (Math.abs(gap) > PATCH_GAP) return;
            Vec3 face = centre.subtract(normal.scale(half));
            Vec3 beyond = face.subtract(normal.scale(0.3));
            if (other == null) {
                BlockPos terrain = BlockPos.containing(beyond);
                if (Ships.inShipyard(level, terrain)) return;
                BlockState there = ShipTerrainImpacts.loadedState(level, terrain);
                if (there == null || there.getCollisionShape(level, terrain).isEmpty()) return;
            } else {
                Vec3 inOther = other.toShip(beyond);
                if (inOther == null) return;
                BlockState there = ShipTerrainImpacts.loadedState(level, BlockPos.containing(inOther));
                if (there == null || there.isAir()) return;
            }
            sum[0] += face.x;
            sum[1] += face.y;
            sum[2] += face.z;
            sum[3]++;
        });
        return sum[3] > 0 ? new Vec3(sum[0] / sum[3], sum[1] / sum[3], sum[2] / sum[3]) : point;
    }

    /** Mean point and mean (unit) normal of some contacts. */
    private static Vec3[] centre(List<ShipTerrainImpacts.Strike> strikes) {
        Vec3 point = Vec3.ZERO;
        Vec3 normal = Vec3.ZERO;
        for (ShipTerrainImpacts.Strike s : strikes) {
            point = point.add(s.point());
            normal = normal.add(s.normal());
        }
        return new Vec3[]{point.scale(1.0 / strikes.size()), unit(normal, strikes.get(0).normal())};
    }

    private static Vec3[] centrePoints(List<Vec3[]> points) {
        Vec3 point = Vec3.ZERO;
        Vec3 normal = Vec3.ZERO;
        for (Vec3[] p : points) {
            point = point.add(p[0]);
            normal = normal.add(p[1]);
        }
        return new Vec3[]{point.scale(1.0 / points.size()), unit(normal, points.get(0)[1])};
    }

    private static Vec3 unit(Vec3 v, Vec3 fallback) {
        double length = v.length();
        return length > 1.0e-6 ? v.scale(1.0 / length) : fallback;
    }

    private static double maxSpeed(List<ShipTerrainImpacts.Strike> strikes) {
        double max = 0.0;
        for (ShipTerrainImpacts.Strike s : strikes) max = Math.max(max, s.speed());
        return max;
    }

    /** A key for a pair of ships (never a ship id: the high bit is set). */
    static long pairKey(long a, long b) {
        long lo = Math.min(a, b), hi = Math.max(a, b);
        return Long.MIN_VALUE | ((lo * 0x9E3779B97F4A7C15L) ^ hi);
    }

    static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    static String vec(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    static void debug(String message, Object... args) {
        if (SERVER.debugShipCollisionLog.get()) {
            LOGGER.info("[FullStop] " + message, args);
        }
    }
}
