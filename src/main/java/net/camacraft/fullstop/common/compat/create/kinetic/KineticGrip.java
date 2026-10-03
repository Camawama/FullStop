package net.camacraft.fullstop.common.compat.create.kinetic;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The geometry and the forces of a spinning part on a body: where the body is against the part's
 * disc ({@link #of}), and the change of velocity a single gear's teeth ({@link #push}, or {@link #fling} when very fast), the nip of two
 * meshing gears ({@link #nipPull}) or the paddles ({@link #wheel}) give it this tick. Pure math on world vectors (blocks, blocks per tick); no Create
 * type, so the tests can drive it directly.
 *
 * <p>A part is a disc: its centre, its axis, the radius its teeth or paddles reach and half its
 * thickness. Its teeth move at {@code omega x radius} along the tangent ({@code omega} is right-handed
 * about the axis; {@link HazardScanner#resolve} converts Create's speed, also right-handed). A body
 * is measured by the point of its box nearest the part's centre: how far that point is out from the
 * axis ({@code radial}) and off the disc's plane ({@code axial}).
 */
final class KineticGrip {

    /** How far beyond its paddles (blocks, at full size) a water wheel still catches a body; the outer bound of every part's reach. */
    static final double CATCH_MARGIN = 0.45;
    /** How far off the disc's plane (beyond half its thickness) a part still catches a body. */
    static final double AXIAL_MARGIN = 0.35;
    /** Within this of the teeth, the body is in them. */
    static final double CONTACT_MARGIN = 0.07;
    /** Largest change of velocity one tick of a part gives (blocks per tick): a pull, never a launch. */
    static final double MAX_DELTA = 0.3;

    private KineticGrip() {
    }

    /**
     * Where a body is against a part.
     *
     * @param radial    the body's nearest point's distance from the axis
     * @param axial     its signed distance from the disc's plane (along the axis)
     * @param radialDir unit, from the axis out to the body (perpendicular to the axis)
     * @param tangent   unit, the way the teeth move where the body is
     * @param caught    within the part's reach
     * @param inTeeth   touching the teeth or paddles
     * @param scale     the part's size against full size (its ship's scale)
     */
    record Catch(Hazard.Live part, double radial, double axial, Vec3 radialDir, Vec3 tangent, boolean caught,
                 boolean inTeeth, double scale) {

        /** How far outside the teeth the body is (negative: inside the rim). */
        double outside() {
            return radial - part.radius();
        }

        /** Speed of the teeth (or of the paddles at {@code atRadius}), blocks per tick. */
        double speedAt(double atRadius) {
            return Math.abs(part.omega()) * atRadius;
        }
    }

    /** The body's catch by a part, or null when the body is out of its reach. */
    @Nullable
    static Catch of(AABB body, Hazard.Live part) {
        Vec3 c = part.center();
        Vec3 axis = part.axis();
        double scale = part.radius() / part.hazard().kind().radius;
        Vec3 p = new Vec3(clamp(c.x, body.minX, body.maxX), clamp(c.y, body.minY, body.maxY), clamp(c.z, body.minZ, body.maxZ));
        Vec3 d = p.subtract(c);
        double axial = d.dot(axis);
        Vec3 r = d.subtract(axis.scale(axial));
        double radial = r.length();
        boolean caught = Math.abs(axial) <= part.halfWidth() + AXIAL_MARGIN * scale && radial <= part.radius() + CATCH_MARGIN * scale;
        if (!caught) return null;
        Vec3 radialDir;
        if (radial > 1.0E-4) {
            radialDir = r.scale(1.0 / radial);
        } else {
            // the box spans the axis: measure from the body's centre instead
            Vec3 dc = body.getCenter().subtract(c);
            Vec3 rc = dc.subtract(axis.scale(dc.dot(axis)));
            if (rc.lengthSqr() < 1.0E-8) rc = perpendicular(axis);
            radialDir = rc.normalize();
        }
        Vec3 tangent = axis.cross(radialDir);
        if (part.omega() < 0) tangent = tangent.scale(-1.0);
        boolean inTeeth = Math.abs(axial) <= part.halfWidth() + CONTACT_MARGIN * scale
                && radial <= part.radius() + CONTACT_MARGIN * scale;
        return new Catch(part, radial, axial, radialDir, tangent, true, inTeeth, scale);
    }

    /**
     * A water wheel's paddles on a body. Out of the water, and on the upstroke, the paddles sweep a body
     * along with them at their speed where it is: off the top of the wheel and down the downstroke into
     * the water, or up and out on the other side. Once it's in the water on the downstroke or round the
     * bottom it is <em>pinned</em>: each paddle that comes round presses it down under the wheel, so it's
     * held at the bottom of the paddles' circle and can't rise (a swimming mob's or player's stroke up is
     * cancelled). Only along the axle does the wheel hold weakly: swimming out sideways, out of the
     * wheel's width, escapes.
     *
     * @param v      the body's world velocity, blocks per tick
     * @param centre the body's centre
     * @param up     the way up for the body (against its gravity)
     * @param wet    whether the body is in the water the wheel turns in
     * @param grip   the paddles' grip (config, 1 = as designed)
     * @return the change of velocity; {@link WheelResult#pinned} when the body is held under
     */
    static WheelResult wheel(Catch k, Vec3 v, Vec3 centre, Vec3 up, boolean wet, double grip) {
        Hazard.Live part = k.part();
        Vec3 axis = part.axis();
        double r = part.radius();
        double at = Math.max(0.3 * k.scale(), Math.min(k.radial(), r));
        Vec3 carry = k.tangent().scale(k.speedAt(at)).add(part.base());
        Vec3 vPlane = v.subtract(axis.scale(v.dot(axis)));
        double height = k.radialDir().dot(up);
        // pinned on the downstroke and round the bottom; on the upstroke the paddles carry the body up and out
        boolean pinned = wet && height < 0.35 && k.tangent().dot(up) <= 0.3;
        Vec3 dv;
        if (pinned) {
            // held at the bottom of the paddles' circle, three quarters of the way out, pressed down
            Vec3 bottom = part.center().add(up.scale(-0.75 * r));
            Vec3 toBottom = bottom.subtract(centre);
            toBottom = toBottom.subtract(axis.scale(toBottom.dot(axis)));
            double dist = toBottom.length();
            Vec3 drift = dist > 1.0E-6 ? toBottom.scale(Math.min(0.06, dist * 0.2) / dist) : Vec3.ZERO;
            Vec3 drag = k.tangent().scale(Math.min(0.02, k.speedAt(at)));
            dv = drift.add(drag.scale(0.25)).subtract(vPlane).scale(Math.min(0.9, 0.35 * grip));
            double rise = v.add(dv).dot(up);
            double hold = -0.04 * Math.min(1.0, grip);
            if (rise > hold) dv = dv.add(up.scale(hold - rise));
        } else {
            double along = carry.subtract(v).dot(k.tangent());
            dv = k.tangent().scale(along * Math.min(0.5, (wet ? 0.3 : 0.15) * grip));
            if (wet && k.tangent().dot(up) < -0.2) dv = dv.add(up.scale(-0.02 * grip));
        }
        double off = Math.abs(k.axial()) - 0.5 * part.halfWidth();
        if (wet && off > 0) dv = dv.add(axis.scale(-Math.signum(k.axial()) * Math.min(0.008 * grip, off)));
        return new WheelResult(cap(dv), pinned);
    }

    record WheelResult(Vec3 delta, boolean pinned) {
    }

    // ------------------------------------------------------------------ gears: a single gear's rim, and the nip of two

    /** Within this of a gear's teeth (beyond them, blocks at full size) a body touches them and is pushed off. */
    static final double TOUCH_MARGIN = 0.08;
    /** ... and within this of the disc's plane beyond half its thickness. */
    static final double TOUCH_AXIAL = 0.1;
    /** The mouth of a nip reaches this far beyond the smaller gear's radius, upstream of the mesh (blocks at full size). */
    static final double NIP_DEPTH_MARGIN = 0.1;
    /** ... this far past the mesh line downstream (the body's face pressed into the mesh). */
    static final double NIP_FRONT = 0.05;
    /** ... this far outside the two rims across the gap. */
    static final double NIP_SIDE = 0.1;
    /** ... and this far off the discs' plane beyond half their thickness. */
    static final double NIP_AXIAL = 0.2;
    /** Fastest a single gear pushes a body off (blocks per tick): a shove, not a launch (7 m/s). */
    static final double MAX_PUSH = 0.35;

    /** Whether a body touches a gear's teeth (its rim, in its plane): the gear pushes it off. Null when it doesn't. */
    @Nullable
    static Catch touch(AABB body, Hazard.Live part) {
        Catch k = of(body, part);
        if (k == null) return null;
        double margin = TOUCH_MARGIN * k.scale();
        boolean touching = k.radial() <= part.radius() + margin && Math.abs(k.axial()) <= part.halfWidth() + TOUCH_AXIAL * k.scale();
        return touching ? k : null;
    }

    /**
     * A single gear's teeth pushing a body that touches them: out from the gear (across gravity: a body standing on top
     * of a gear isn't bounced, it's carried off) and along the way the teeth move, up to a share of their speed. A
     * crawling gear barely nudges, a fast one shoves at up to {@link #MAX_PUSH}; teeth past {@code gearFlingSpeed} throw
     * the body instead ({@link #fling}). Nothing pulls, nothing bites.
     *
     * @param v        the body's world velocity, blocks per tick
     * @param up       the way up for the body
     * @param strength 1 for a player's weight, standing (heavier bodies and a sneaking player give less)
     */
    static Vec3 push(Catch k, Vec3 v, Vec3 up, double strength) {
        Hazard.Live part = k.part();
        double teeth = k.speedAt(part.radius());
        Vec3 out = k.radialDir().subtract(up.scale(k.radialDir().dot(up)));
        double outLen = out.length();
        Vec3 dv = Vec3.ZERO;
        if (outLen > 0.2) {
            out = out.scale(1.0 / outLen);
            double target = Math.min(MAX_PUSH, 0.4 * teeth * strength + 0.005);
            double now = v.subtract(part.base()).dot(out);
            if (now < target) dv = dv.add(out.scale(target - now));
        }
        double alongTarget = Math.min(MAX_PUSH, 0.6 * teeth * strength);
        double along = v.subtract(part.base()).dot(k.tangent());
        if (along < alongTarget) dv = dv.add(k.tangent().scale((alongTarget - along) * 0.5));
        return cap(dv);
    }

    /** A flung body's share of the throw out from the gear: the rest goes along the teeth. */
    static final double FLING_OUT = 0.5;
    /** At least this share of a throw goes out from the gear (a body the teeth would drive into the floor squeezes out). */
    static final double FLING_MIN_OUT = 0.45;
    /** A throw this far down (or more, m/s) has its downward part dropped. */
    private static final double INTO_FLOOR = 1.0E-6;

    /** The speed of a gear's teeth at its rim (m/s): what a still body touching them is hit at. */
    static double teethMps(Hazard.Live part) {
        return Math.abs(part.omega()) * part.radius() * 20.0;
    }

    /**
     * A very fast single gear's teeth throwing a body that touches them.
     *
     * @param closing how fast the teeth hit the body (m/s): their speed against the body's own, along the way they move
     * @param change  the body's change of velocity (m/s, world), before {@code maxHitLaunchSpeed}
     */
    record Fling(double closing, Vec3 change) {
    }

    /**
     * The throw a single gear's teeth give a body touching them, by Full Stop's strike law for a driven hull: the teeth
     * hit it at their speed against its own ({@code closing}), and it leaves at {@code (1 + restitution)} of that, along
     * the way the teeth move and {@value #FLING_OUT} of that out from the gear (across gravity), lifted off the ground
     * by {@code lift} of the sideways part as a hull's strike lifts it. Nothing is thrown down: teeth running down at a
     * body would drive it into the floor (and slam it, a gear hurting what touches it), so they squeeze it out along the
     * floor instead, at least {@value #FLING_MIN_OUT} of the throw out from the gear. Whether the throw happens
     * at all ({@code closing} against {@code gearFlingSpeed}) is the caller's.
     *
     * @param ownMps      the body's world velocity (m/s)
     * @param up          the way up for the body
     * @param grounded    the body stands on the ground
     * @param restitution {@code entityRestitution}
     * @param lift        {@code hitLift}, already scaled by the body's gravity
     */
    static Fling fling(Catch k, Vec3 ownMps, Vec3 up, boolean grounded, double restitution, double lift) {
        Hazard.Live part = k.part();
        Vec3 t = k.tangent();
        Vec3 teeth = t.scale(teethMps(part)).add(part.base().scale(20.0));
        double closing = teeth.subtract(ownMps).dot(t);
        // out from the gear, across gravity: a body on top of a gear is thrown off sideways, not bounced
        Vec3 out = k.radialDir().subtract(up.scale(k.radialDir().dot(up)));
        double outLen = out.length();
        boolean hasOut = outLen > 0.2;
        if (hasOut) out = out.scale(1.0 / outLen);
        Vec3 dir = hasOut ? t.add(out.scale(FLING_OUT)).normalize() : t;
        double speed = (1.0 + restitution) * Math.max(0.0, closing);
        Vec3 change = dir.scale(speed);
        // never down: teeth running down at a body drive it into the floor, which stops that part (or would slam it)
        double down = change.dot(up);
        if (down < -INTO_FLOOR) change = change.subtract(up.scale(down));
        if (hasOut) {
            double outward = change.dot(out);
            double least = FLING_MIN_OUT * speed;
            if (outward < least) change = change.add(out.scale(least - outward));
        }
        if (grounded && lift > 0.0) {
            double across = change.subtract(up.scale(change.dot(up))).length();
            change = change.add(up.scale(lift * across));
        }
        return new Fling(closing, change);
    }

    /**
     * Two meshing gears (Create's own rule: two cogwheels side by side on one axis, or a large and a small one
     * diagonally on one axis) and where they meet: {@code mesh}, the point on the line between their centres where
     * their pitch circles touch (a large cog's twice a small one's); {@code across}, unit from {@code a} to {@code b};
     * {@code along}, unit, the way both gears' teeth move through the mesh (from their driven direction, so a jammed
     * pair still knows it); {@code toA}, {@code toB}, the mesh's distance from each centre.
     */
    record Nip(Hazard.Live a, Hazard.Live b, Vec3 mesh, Vec3 across, Vec3 along, Vec3 axis, double toA, double toB, double scale) {

        /** How far upstream of the mesh the mouth reaches. */
        double depth() {
            return Math.min(a.radius(), b.radius()) + NIP_DEPTH_MARGIN * scale;
        }

        /** Across the gap, where gear a's rim stands {@code s} upstream of the mesh (from the mesh, toward b: positive). */
        double rimA(double s) {
            return -toA + Math.sqrt(Math.max(0.0, a.radius() * a.radius() - s * s));
        }

        /** ... and gear b's. */
        double rimB(double s) {
            return toB - Math.sqrt(Math.max(0.0, b.radius() * b.radius() - s * s));
        }

        /** The fastest part of the pair as it turns now (RPM). */
        double rpm() {
            return Math.max(Math.abs(a.rpm()), Math.abs(b.rpm()));
        }

        /** Driven, but its network overstressed: the pair stands jammed. */
        boolean stalled() {
            return a.stalled() || b.stalled() || a.rpm() == 0.0 || b.rpm() == 0.0;
        }

        /** Whether a large cogwheel is one of them (its bite is a large cogwheel's). */
        boolean large() {
            return a.hazard().kind().large() || b.hazard().kind().large();
        }
    }

    /** Whether two gears mesh, by Create's own rule (in RotationPropagator); two large cogs only mesh at right angles: no nip. */
    static boolean meshes(Hazard.Live a, Hazard.Live b) {
        Hazard ha = a.hazard(), hb = b.hazard();
        if (ha.kind().wheel || hb.kind().wheel || ha.shipId() != hb.shipId() || a.gridAxis() != b.gridAxis()) return false;
        net.minecraft.core.BlockPos d = hb.pos().subtract(ha.pos());
        net.minecraft.core.Direction.Axis axis = a.gridAxis();
        if (axis.choose(d.getX(), d.getY(), d.getZ()) != 0) return false;
        boolean largeA = ha.kind().large(), largeB = hb.kind().large();
        if (!largeA && !largeB) return Math.abs(d.getX()) + Math.abs(d.getY()) + Math.abs(d.getZ()) == 1;
        if (largeA == largeB) return false;
        for (net.minecraft.core.Direction.Axis other : net.minecraft.core.Direction.Axis.values()) {
            if (other != axis && Math.abs(other.choose(d.getX(), d.getY(), d.getZ())) != 1) return false;
        }
        return true;
    }

    /** The nip of two meshing gears, or null when they aren't driven (nothing turns them either way). */
    @Nullable
    static Nip nip(Hazard.Live a, Hazard.Live b) {
        if (a.driveRpm() == 0.0) return null;
        Vec3 ab = b.center().subtract(a.center());
        double d = ab.length();
        if (d < 1.0E-6) return null;
        Vec3 across = ab.scale(1.0 / d);
        double wa = a.hazard().kind().large() ? 2.0 : 1.0;
        double wb = b.hazard().kind().large() ? 2.0 : 1.0;
        double toA = d * wa / (wa + wb);
        Vec3 mesh = a.center().add(across.scale(toA));
        // a's teeth at the mesh move omega x r: right-handed about its axis, the way its network drives it
        Vec3 along = a.axis().cross(across).normalize().scale(Math.signum(a.driveRpm()));
        double scale = a.radius() / a.hazard().kind().radius;
        return new Nip(a, b, mesh, across, along, a.axis(), toA, d - toA, scale);
    }

    /**
     * A body at the in-running mouth of a nip: where both gears' teeth run into the mesh. {@code s} is how far its
     * nearest point is from the mesh along the teeth's motion (negative: upstream, in the mouth), {@code w} across the
     * gap (from the mesh toward gear b), {@code axial} off the discs' plane.
     */
    record NipCatch(Nip nip, double s, double w, double axial) {
    }

    /** The body's place in the nip's in-running mouth, or null when it isn't in it (the out-running side included). */
    @Nullable
    static NipCatch inMouth(AABB body, Nip nip) {
        Vec3 m = nip.mesh();
        Vec3 p = new Vec3(clamp(m.x, body.minX, body.maxX), clamp(m.y, body.minY, body.maxY), clamp(m.z, body.minZ, body.maxZ));
        Vec3 d = p.subtract(m);
        double s = d.dot(nip.along());
        double w = d.dot(nip.across());
        double axial = d.dot(nip.axis());
        double scale = nip.scale();
        double halfWidth = Math.max(nip.a().halfWidth(), nip.b().halfWidth());
        if (Math.abs(axial) > halfWidth + NIP_AXIAL * scale) return null;
        if (s > NIP_FRONT * scale || s < -nip.depth()) return null;
        double side = NIP_SIDE * scale;
        if (w < nip.rimA(s) - side || w > nip.rimB(s) + side) return null;
        return new NipCatch(nip, s, w, axial);
    }

    /**
     * The nip drawing a body in: along the teeth's motion toward the mesh (toward their speed there, scaled by
     * {@code strength}), into the middle of the gap, and onto the discs' plane.
     *
     * @param v        the body's world velocity, blocks per tick
     * @param strength 1 at 64 RPM for a player's weight, standing
     */
    static Vec3 nipPull(NipCatch k, Vec3 v, double strength) {
        Nip nip = k.nip();
        Vec3 rel = v.subtract(nip.a().base());
        double teeth = Math.abs(nip.a().omega()) * nip.toA();
        double target = Math.min(0.25, teeth * 0.5 * Math.max(0.0, strength));
        double along = rel.dot(nip.along());
        Vec3 dv = Vec3.ZERO;
        if (along < target) dv = dv.add(nip.along().scale((target - along) * Math.min(0.6, 0.25 * strength)));
        double middle = 0.5 * (nip.rimA(k.s()) + nip.rimB(k.s()));
        double off = k.w() - middle;
        dv = dv.add(nip.across().scale(-Math.signum(off) * Math.min(0.03 * strength, Math.abs(off) * 0.3)));
        double outOfPlane = Math.abs(k.axial()) - Math.max(nip.a().halfWidth(), nip.b().halfWidth());
        if (outOfPlane > 0) dv = dv.add(nip.axis().scale(-Math.signum(k.axial()) * Math.min(0.03 * strength, outOfPlane)));
        return cap(dv);
    }

    private static Vec3 cap(Vec3 dv) {
        double len = dv.length();
        return len > MAX_DELTA ? dv.scale(MAX_DELTA / len) : dv;
    }

    private static Vec3 perpendicular(Vec3 axis) {
        Vec3 other = Math.abs(axis.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        return axis.cross(other);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
