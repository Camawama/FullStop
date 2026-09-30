package net.camacraft.fullstop.common.physics;

import net.camacraft.camafy.compat.vs.ShipContact;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Whether, and where, an entity's box meets a Valkyrien Skies hull, judged the only way that holds
 * for a moving (and turning) hull: in the ship's own frame, with the two bodies' relative motion.
 *
 * <p>{@link Relative} maps the entity's box to where it is at time {@code t} (seconds from now)
 * relative to the ship as it is posed now: the ship moves rigidly (its centre of mass at
 * {@code v}, turning at {@code w} about it) and the entity straight on at {@code u}, so round a
 * spinning ship the entity's path in the ship's frame is an arc about the spin axis. {@link #sweep}
 * finds the first time in a window that path brings the box within {@link #SKIN} of one of the
 * ship's actual blocks. The hull's velocity is only ever taken on the hull: extrapolated out to an
 * entity twenty blocks from a fast rotor it was hundreds of m/s and struck what the hull would
 * never reach.
 *
 * <p>Used by the ship's hits on entities (server) and to tell a ray hit on a ship block from a
 * real contact (both sides: FullStop's entity rays look a tick of the entity's travel ahead, and
 * against a hull that moves, a block in the ray's way now is not one the entity will meet).
 *
 * <p>{@link Posed} follows the ship between two poses it really had (last tick's and this tick's) instead
 * of extrapolating its velocity and spin: what a client just received, and is about to draw. The local
 * player's hits are judged that way ({@code ClientShipContacts}): a fast rotor's arm turns tens of degrees
 * a tick, and extrapolated a tick ahead from a slightly noisy spin, it met players it never came near on
 * screen, a tick before it got there.</p>
 */
public final class ShipSweptContact {

    /** Blocks of relative travel between two coarse samples. */
    private static final double SEGMENT = 0.5;
    private static final int MAX_SEGMENTS = 192;
    /** Blocks of relative travel between two fine samples, inside a coarse step that touches the hull. */
    private static final double FINE = 0.2;
    private static final int MAX_FINE = 64;
    /** How close (blocks) counts as touching. */
    public static final double SKIN = 0.05;
    private static final int BISECTIONS = 8;

    private ShipSweptContact() {
    }

    /** How the entity's box moves in the ship's frame now ({@link Relative}, {@link Posed}). */
    public interface Motion {
        /** Where the world point {@code point} of the entity (now) is at time {@code t} (s), in the ship's frame now. */
        Vec3 image(Vec3 point, double t);

        /** The entity's box at time {@code t} in the ship's frame now: the bounds of its turned corners. */
        default AABB box(AABB box, double t) {
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 8; corner++) {
                Vec3 c = image(new Vec3((corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY,
                        (corner & 4) == 0 ? box.minZ : box.maxZ), t);
                minX = Math.min(minX, c.x);
                minY = Math.min(minY, c.y);
                minZ = Math.min(minZ, c.z);
                maxX = Math.max(maxX, c.x);
                maxY = Math.max(maxY, c.y);
                maxZ = Math.max(maxZ, c.z);
            }
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }

        /** Blocks of relative travel per second at a box (the entity's speed across the ship's frame there). */
        double relativeSpeed(AABB box);

        /**
         * Whether the entity's box at time {@code t}, within {@code skin}, overlaps one of the ship's blocks. By default
         * its image box ({@link #box}) against the ship as posed now, which a turn widens (the bounds of a turned box).
         */
        default boolean touches(ShipRef ship, AABB box, double t, double skin) {
            return ship.touchesBlock(box(box, t).inflate(skin));
        }

        /** The blocks the entity's box at {@code t} (within {@code skin}) overlaps, as {@link #touches} judges them. */
        default List<ShipContact> contacts(ShipRef ship, AABB box, double t, double skin) {
            return ship.contacts(box(box, t).inflate(skin));
        }
    }

    /** See the class notes. All vectors world space: m and m/s, rad/s. */
    public static final class Relative implements Motion {
        public final Vec3 com;
        public final Vec3 v;
        public final Vec3 w;
        public final Vec3 u;
        public final double spin;

        public Relative(Vec3 com, Vec3 v, Vec3 w, Vec3 u) {
            this.com = com;
            this.v = v;
            this.w = w;
            this.u = u;
            this.spin = w.length();
        }

        @Override
        public Vec3 image(Vec3 point, double t) {
            return com.add(turn(point.add(u.subtract(v).scale(t)).subtract(com), -t));
        }

        /** {@code vector} turned by the ship's spin over {@code t} seconds (world axes). */
        public Vec3 turn(Vec3 vector, double t) {
            if (spin < 1.0e-9) return vector;
            double angle = spin * t;
            Vec3 k = w.scale(1.0 / spin);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            return vector.scale(cos).add(k.cross(vector).scale(sin)).add(k.scale(k.dot(vector) * (1.0 - cos)));
        }

        /** The hull's own velocity at its point {@code x} (ship's frame now) at time {@code t}, in that frame. */
        public Vec3 hullVelocity(Vec3 x, double t) {
            return w.cross(x.subtract(com)).add(turn(v, -t));
        }

        /** The hull's velocity at its point {@code x} relative to the entity at time {@code t}, in the ship's frame now. */
        public Vec3 hullRelative(Vec3 x, double t) {
            return hullVelocity(x, t).subtract(turn(u, -t));
        }

        @Override
        public double relativeSpeed(AABB box) {
            Vec3 centre = box.getCenter();
            double halfDiagonal = 0.5 * Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
                    + box.getZsize() * box.getZsize());
            return u.subtract(v).length() + spin * (centre.distanceTo(com) + halfDiagonal);
        }
    }

    /**
     * The entity against a ship moving between two poses it really had over the tick just gone ({@code PREV_TICK} at
     * {@code t = -TICK}, {@code TICK} at {@code t = 0}): position and scale interpolated, rotation slerped, the entity
     * straight on at {@code u}. No velocity or spin is extrapolated, so where the hull is found to meet the entity is
     * exactly where the hull went (and what a client draws of it). Only for {@code t} in {@code [-TICK, 0]}.
     */
    public static final class Posed implements Motion {
        private static final double TICK = 0.05;
        private final Vec3 anchor;       // a point of the ship (its centre of mass), ship space
        private final Vec3 anchorPrev;   // where it was last tick, world
        private final Vec3 anchorNow;    // where it is now, world
        private final org.joml.Quaterniond rotationPrev;
        private final org.joml.Quaterniond rotationNow;
        private final double scalePrev;
        private final double scaleNow;
        private final Vec3 u;
        private final double speed;
        private final double spin;

        private Posed(Vec3 anchor, Vec3 anchorPrev, Vec3 anchorNow, org.joml.Quaterniond rotationPrev, org.joml.Quaterniond rotationNow,
                      double scalePrev, double scaleNow, Vec3 u) {
            this.anchor = anchor;
            this.anchorPrev = anchorPrev;
            this.anchorNow = anchorNow;
            this.rotationPrev = rotationPrev;
            this.rotationNow = rotationNow;
            this.scalePrev = scalePrev;
            this.scaleNow = scaleNow;
            this.u = u;
            this.speed = anchorNow.subtract(anchorPrev).length() / TICK;
            org.joml.Quaterniond turn = new org.joml.Quaterniond(rotationNow).mul(new org.joml.Quaterniond(rotationPrev).conjugate()).normalize();
            this.spin = 2.0 * Math.acos(Math.min(1.0, Math.abs(turn.w))) / TICK;
        }

        @Override
        public Vec3 image(Vec3 point, double t) {
            double f = Mth.clamp(1.0 + t / TICK, 0.0, 1.0);
            // the ship at that moment: anchor and scale in between, rotation slerped
            Vec3 anchorAt = anchorPrev.lerp(anchorNow, f);
            double scaleAt = Mth.lerp(f, scalePrev, scaleNow);
            org.joml.Quaterniond rotationAt = new org.joml.Quaterniond(rotationPrev).slerp(rotationNow, f);
            // the entity's point then, into the ship's space as the ship was posed then...
            Vec3 p = point.add(u.scale(t)).subtract(anchorAt);
            org.joml.Vector3d local = new org.joml.Vector3d(p.x, p.y, p.z);
            rotationAt.transformInverse(local).mul(1.0 / scaleAt);
            // ...and out again as the ship is posed now
            rotationNow.transform(local).mul(scaleNow);
            return new Vec3(anchorNow.x + local.x, anchorNow.y + local.y, anchorNow.z + local.z);
        }

        @Override
        public double relativeSpeed(AABB box) {
            Vec3 centre = box.getCenter();
            double halfDiagonal = 0.5 * Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
                    + box.getZsize() * box.getZsize());
            return u.length() + speed + spin * (centre.distanceTo(anchorNow) + halfDiagonal);
        }

        /**
         * Exact: the entity's own box where it was at {@code t}, against the ship posed as it was then (Camafy's
         * oriented-box test) - not the bounds of that box turned into the ship's frame now, which on a fast rotor
         * widened a player's box by a fifth of a block and turned near misses into hits.
         */
        @Override
        public boolean touches(ShipRef ship, AABB box, double t, double skin) {
            return ship.touchesBlock(box.move(u.scale(t)).inflate(skin), worldToShipAt(t));
        }

        @Override
        public List<ShipContact> contacts(ShipRef ship, AABB box, double t, double skin) {
            return ship.contacts(box.move(u.scale(t)).inflate(skin), worldToShipAt(t));
        }

        /** World to ship space, the ship posed as it was at {@code t}. */
        private org.joml.Matrix4d worldToShipAt(double t) {
            double f = Mth.clamp(1.0 + t / TICK, 0.0, 1.0);
            Vec3 anchorAt = anchorPrev.lerp(anchorNow, f);
            double scaleAt = Mth.lerp(f, scalePrev, scaleNow);
            org.joml.Quaterniond rotationAt = new org.joml.Quaterniond(rotationPrev).slerp(rotationNow, f);
            return new org.joml.Matrix4d().translation(anchorAt.x, anchorAt.y, anchorAt.z).rotate(rotationAt).scale(scaleAt)
                    .translate(-anchor.x, -anchor.y, -anchor.z).invert();
        }
    }

    /**
     * The entity, moving at {@code entityWorldMps} (m/s), against {@code ship} as it moved from last tick's pose to
     * this tick's ({@link Posed}); null when the ship or its poses can't be resolved.
     */
    @Nullable
    public static Posed lastTick(ShipRef ship, Vec3 entityWorldMps) {
        Vec3 com = ship.position();
        Vec3 anchor = com == null ? null : ship.toShip(com);
        Vec3 before = anchor == null ? null : ship.toWorld(anchor, ShipRef.Pose.PREV_TICK);
        org.joml.Quaterniond rotationPrev = ship.rotation(ShipRef.Pose.PREV_TICK);
        org.joml.Quaterniond rotationNow = ship.rotation(ShipRef.Pose.TICK);
        if (before == null || rotationPrev == null || rotationNow == null) return null;
        return new Posed(anchor, before, com, rotationPrev, rotationNow, ship.scale(ShipRef.Pose.PREV_TICK), ship.scale(ShipRef.Pose.TICK),
                entityWorldMps);
    }

    /** Where the swept test met the hull: when (s from now), the entity's box then (ship's frame now), the blocks touched. */
    public record Meeting(double t, AABB box, List<ShipContact> contacts) {}

    /**
     * The first time in {@code [from, to]} (seconds) the entity's box, following {@code motion},
     * comes within {@link #SKIN} of one of the ship's blocks, with the blocks it touches then; or
     * null. See {@link #firstTouch}. Sampled finely enough for the smaller of the entity and the
     * ship's blocks ({@link #feature}): a 1/16 ship's arm (VS Genesis's space) is a sixteenth of a
     * block thick, and a player there a sixteenth of a normal one.
     */
    @Nullable
    public static Meeting sweep(ShipRef ship, AABB hullBounds, Motion motion, AABB box, double from, double to) {
        double feature = feature(box, ship.scale());
        double skin = skin(feature);
        // the widened image boxes only cull; each touch the sweep finds is decided by the motion's own test
        double t = firstTouch(ship::touchesBlock, (image, at) -> motion.touches(ship, box, at, skin), hullBounds, motion, box,
                from, to, feature);
        if (Double.isNaN(t)) return null;
        List<ShipContact> contacts = motion.contacts(ship, box, t, skin);
        return contacts.isEmpty() ? null : new Meeting(t, motion.box(box, t), contacts);
    }

    /** Whether the entity touches the hull at a time, given its image box then (a finer test than the image box alone). */
    @FunctionalInterface
    public interface Touch {
        boolean at(AABB image, double t);
    }

    /** The smallest thing a sweep must not step over (blocks): the entity's thinnest side, or one of the ship's blocks. */
    public static double feature(AABB box, double shipScale) {
        double thinnest = Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize()));
        double block = shipScale > 0.0 && Double.isFinite(shipScale) ? shipScale : 1.0;
        return Math.max(1.0e-3, Math.min(thinnest, block));
    }

    /** How close counts as touching for things {@code feature} blocks small: {@link #SKIN}, or a tenth of the feature. */
    public static double skin(double feature) {
        return Math.min(SKIN, 0.1 * feature);
    }

    /**
     * The first time in {@code [from, to]} (seconds) the entity's box, following {@code motion},
     * comes within {@link #SKIN} of the hull ({@code touches}: whether a box in the ship's frame
     * now overlaps a block), or NaN. Coarse steps of about {@link #SEGMENT} blocks of relative
     * travel are tested as the box swept between them (skipped outright away from the hull's
     * bounds); a step that touches is walked in fine steps and the first touching one bisected,
     * so the contacts come from a box that has only just reached the hull and their faces (least
     * penetration) are the ones it met.
     */
    public static double firstTouch(Predicate<AABB> touches, AABB hullBounds, Motion motion, AABB box, double from,
                                    double to) {
        return firstTouch(touches, hullBounds, motion, box, from, to, 1.0);
    }

    /**
     * {@link #firstTouch(Predicate, AABB, Motion, AABB, double, double)} with steps no longer than {@code feature}
     * (blocks, see {@link #feature}) allows: a fine step longer than the thinnest thing in the way steps over it.
     */
    public static double firstTouch(Predicate<AABB> touches, AABB hullBounds, Motion motion, AABB box, double from,
                                    double to, double feature) {
        double skinned = skin(Mth.clamp(feature, 1.0e-3, 1.0));
        return firstTouch(touches, (image, t) -> touches.test(image.inflate(skinned)), hullBounds, motion, box, from, to, feature);
    }

    /**
     * {@link #firstTouch(Predicate, AABB, Motion, AABB, double, double, double)}, the swept image boxes culled with
     * {@code cull} and every touch decided by {@code exact}.
     */
    public static double firstTouch(Predicate<AABB> cull, Touch exact, AABB hullBounds, Motion motion, AABB box, double from,
                                    double to, double feature) {
        double small = Mth.clamp(feature, 1.0e-3, 1.0);
        double skin = skin(small);
        double segmentLength = Math.min(SEGMENT, small);
        double fineLength = Math.min(FINE, 0.5 * small);
        double speed = motion.relativeSpeed(box);
        double span = Math.max(0.0, to - from);
        AABB reach = hullBounds.inflate(skin);
        AABB previous = motion.box(box, from);
        if (previous.intersects(reach) && exact.at(previous, from)) return from;
        if (span <= 0.0) return Double.NaN;
        int segments = Mth.clamp((int) Math.ceil(speed * span / segmentLength), 1, MAX_SEGMENTS);
        int fine = Mth.clamp((int) Math.ceil(speed * span / segments / fineLength), 1, MAX_FINE);
        double before = from;
        for (int k = 1; k <= segments; k++) {
            double t = from + span * k / segments;
            AABB next = motion.box(box, t);
            AABB swept = previous.minmax(next).inflate(skin);
            if (swept.intersects(reach) && cull.test(swept)) {
                double clear = before;
                for (int j = 1; j <= fine; j++) {
                    double at = before + (t - before) * j / fine;
                    if (!exact.at(motion.box(box, at), at)) {
                        clear = at;
                        continue;
                    }
                    double touching = at;
                    for (int i = 0; i < BISECTIONS; i++) {
                        double mid = 0.5 * (clear + touching);
                        if (exact.at(motion.box(box, mid), mid)) {
                            touching = mid;
                        } else {
                            clear = mid;
                        }
                    }
                    return touching;
                }
            }
            previous = next;
            before = t;
        }
        return Double.NaN;
    }

    /**
     * The entity's motion relative to {@code ship} as the ship moves now (its centre of mass,
     * velocity and spin as this side knows them), or null when the ship can't be resolved.
     *
     * @param entityWorldMps the entity's world velocity (its own plus any carry), m/s
     */
    @Nullable
    public static Relative relativeTo(ShipRef ship, Vec3 entityWorldMps) {
        Vec3 com = ship.position();
        Vec3 v = ship.velocity();
        Vec3 w = ship.angularVelocity();
        if (com == null || v == null || w == null) return null;
        return new Relative(com, v, w, entityWorldMps);
    }

    /** The point of a contact's block face nearest {@code near} (world, the ship's frame now), in the world. */
    @Nullable
    public static Vec3 facePoint(ShipRef ship, ShipContact contact, Vec3 near) {
        Vec3 local = ship.toShip(near);
        if (local == null) return null;
        return ship.toWorld(facePointLocal(contact.pos(), contact.face(), local));
    }

    /** The point of block {@code pos}'s face {@code face} nearest the ship-space point {@code local}, in ship space. */
    public static Vec3 facePointLocal(BlockPos pos, Direction face, Vec3 local) {
        double x = Mth.clamp(local.x, pos.getX(), pos.getX() + 1.0);
        double y = Mth.clamp(local.y, pos.getY(), pos.getY() + 1.0);
        double z = Mth.clamp(local.z, pos.getZ(), pos.getZ() + 1.0);
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        switch (face.getAxis()) {
            case X -> x = positive ? pos.getX() + 1.0 : pos.getX();
            case Y -> y = positive ? pos.getY() + 1.0 : pos.getY();
            case Z -> z = positive ? pos.getZ() + 1.0 : pos.getZ();
        }
        return new Vec3(x, y, z);
    }
}
