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
 */
public final class ShipSweptContact {

    /** Blocks of relative travel between two coarse samples. */
    private static final double SEGMENT = 0.5;
    private static final int MAX_SEGMENTS = 96;
    /** Blocks of relative travel between two fine samples, inside a coarse step that touches the hull. */
    private static final double FINE = 0.2;
    private static final int MAX_FINE = 32;
    /** How close (blocks) counts as touching. */
    public static final double SKIN = 0.05;
    private static final int BISECTIONS = 8;

    private ShipSweptContact() {
    }

    /** See the class notes. All vectors world space: m and m/s, rad/s. */
    public static final class Relative {
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

        /** Where the world point {@code point} of the entity (now) is at time {@code t}, in the ship's frame now. */
        public Vec3 image(Vec3 point, double t) {
            return com.add(turn(point.add(u.subtract(v).scale(t)).subtract(com), -t));
        }

        /** The entity's box at time {@code t} in the ship's frame now: the bounds of its turned corners. */
        public AABB box(AABB box, double t) {
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

        /** Blocks of relative travel per second at a box (the entity's speed across the ship's frame there). */
        double relativeSpeed(AABB box) {
            Vec3 centre = box.getCenter();
            double halfDiagonal = 0.5 * Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
                    + box.getZsize() * box.getZsize());
            return u.subtract(v).length() + spin * (centre.distanceTo(com) + halfDiagonal);
        }
    }

    /** Where the swept test met the hull: when (s from now), the entity's box then (ship's frame now), the blocks touched. */
    public record Meeting(double t, AABB box, List<ShipContact> contacts) {}

    /**
     * The first time in {@code [from, to]} (seconds) the entity's box, following {@code motion},
     * comes within {@link #SKIN} of one of the ship's blocks, with the blocks it touches then; or
     * null. See {@link #firstTouch}.
     */
    @Nullable
    public static Meeting sweep(ShipRef ship, AABB hullBounds, Relative motion, AABB box, double from, double to) {
        double t = firstTouch(ship::touchesBlock, hullBounds, motion, box, from, to);
        if (Double.isNaN(t)) return null;
        AABB at = motion.box(box, t);
        List<ShipContact> contacts = ship.contacts(at.inflate(SKIN));
        return contacts.isEmpty() ? null : new Meeting(t, at, contacts);
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
    public static double firstTouch(Predicate<AABB> touches, AABB hullBounds, Relative motion, AABB box, double from,
                                    double to) {
        double speed = motion.relativeSpeed(box);
        double span = Math.max(0.0, to - from);
        AABB reach = hullBounds.inflate(SKIN);
        AABB previous = motion.box(box, from);
        if (previous.intersects(reach) && touches.test(previous.inflate(SKIN))) return from;
        if (span <= 0.0) return Double.NaN;
        int segments = Mth.clamp((int) Math.ceil(speed * span / SEGMENT), 1, MAX_SEGMENTS);
        int fine = Mth.clamp((int) Math.ceil(speed * span / segments / FINE), 1, MAX_FINE);
        double before = from;
        for (int k = 1; k <= segments; k++) {
            double t = from + span * k / segments;
            AABB next = motion.box(box, t);
            AABB swept = previous.minmax(next).inflate(SKIN);
            if (swept.intersects(reach) && touches.test(swept)) {
                double clear = before;
                for (int j = 1; j <= fine; j++) {
                    double at = before + (t - before) * j / fine;
                    if (!touches.test(motion.box(box, at).inflate(SKIN))) {
                        clear = at;
                        continue;
                    }
                    double touching = at;
                    for (int i = 0; i < BISECTIONS; i++) {
                        double mid = 0.5 * (clear + touching);
                        if (touches.test(motion.box(box, mid).inflate(SKIN))) {
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
