package net.camacraft.fullstop.common.physics.math;

import net.camacraft.camafy.compat.scale.EntityScale;

import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class RaycastUtil {

    /**
     * Minimum ray reach in blocks. The bottom ray corners start 0.1 above the box
     * floor, so anything shorter can never touch the block the entity is resting
     * against — on the late tick of a two-tick impact the previous travel distance
     * alone can be arbitrarily small even though the entity is flush to the block.
     */
    private static final double MIN_RAY_LENGTH = 0.15;

    /**
     * The layout's fixed lengths - the 0.1 the bottom row is raised above the feet, the 0.15 minimum reach, the
     * start margin - are for a full-size body. A body a size mod shrank (Pehkui: everything in VS Genesis's space is
     * 1/16) gets them shrunk with it, or a 1/16 player's "bottom" rays start above its head and its shortest rays
     * reach two of its lengths (which is how its F3+V view was huge and in the wrong place). Never enlarged for a
     * big body: 1 at normal size and above.
     */
    public static double sizeFactor(Entity entity) {
        double factor = EntityScale.heightFactor(entity);
        return factor > 0.0 && Double.isFinite(factor) ? Math.min(1.0, factor) : 1.0;
    }

    public static Vec3 getRayDirection(FullStopCapability fullstop) {
        // Pre-impact velocity: on the damage tick of a fast impact the one-tick-old
        // velocity is only the tiny post-contact remnant and may point along the
        // wall instead of into it.
        Vec3 velocity = fullstop.getPreImpactScaledVelocity();
        if (velocity.lengthSqr() > 0.0001) {
            return velocity.normalize();
        }

        Vec3 acc = fullstop.getAcceleration();
        if (acc == null || acc.lengthSqr() == 0) return Vec3.ZERO;
        return acc.normalize();
    }

    /** Ray length in blocks: how far the entity travelled going into the impact, plus a contact margin. */
    public static double getRayLength(Entity entity, FullStopCapability fullstop) {
        return raysFor(entity) ? getRayLength(entity, fullstop, sizeFactor(entity)) : 0;
    }

    /** {@link #getRayLength(Entity, FullStopCapability)} with the entity's {@link #sizeFactor} already worked out. */
    public static double getRayLength(Entity entity, FullStopCapability fullstop, double size) {
        if (!raysFor(entity)) return 0;
        return Math.max(fullstop.getPreImpactNativeVelocity().length(), MIN_RAY_LENGTH * size) + 0.01 * size;
    }

    /** Whether FullStop casts rays for this entity at all (not for a projectile, see below). */
    public static boolean raysFor(Entity entity) {
        // Projectiles own their impacts (arrows stick, tridents return), so
        // FullStop does not raycast for them — unless a pack opts one in via
        // fullstop:physics_projectiles (modded grappling hooks and the like).
        // This single gate is what withholds ALL of FullStop's physics from
        // projectiles: with rays enabled, bouncing, water skips, block
        // breaking and entity-collision damage all apply, and the pre-contact
        // reactions fire before the projectile's own onHit does.
        return !(entity instanceof Projectile)
                || entity.getType().is(net.camacraft.fullstop.common.physics.rules.FullStopTags.PHYSICS_PROJECTILES);
    }

    /**
     * The Y below which this entity cannot really collide: its own box floor —
     * or, when riding, the vehicle's, whichever is higher. A seated boat
     * passenger's box floor sits 0.45 blocks BELOW the hull (riding offsets),
     * i.e. inside the floor layer the boat glides on; rays cast from inside
     * that layer read every block seam as a head-on wall, which is how a rider
     * "collided with" and ground away the ice under a moving boat.
     */
    public static double effectiveFloorY(Entity entity) {
        double minY = entity.getBoundingBox().minY;
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            minY = Math.max(minY, vehicle.getBoundingBox().minY);
        }
        return minY;
    }

    /**
     * Ray starts are inflated outward by this margin. floor() handedness: a
     * corner sitting exactly ON a block boundary is traversed in the +axis
     * cell, so flush wall contact only registered against walls on the
     * SOUTH/EAST side — rubbing a NORTH/WEST wall ran the rays in the air
     * column beside it (debug rays showed green while visibly touching).
     * Pushing the starts a hair outward lands them in the adjacent cell on
     * both sides. Half of the seam guard's MIN_CROSS_SECTION_OVERLAP, so the
     * extra sliver can never turn a rub into a collision.
     */
    private static final double RAY_START_MARGIN = 5.0e-5;

    public static List<Vec3> getRayStarts(Entity entity, FullStopConfig.RaycastMode mode) {
        return getRayStarts(entity, mode, GravityFrame.DEFAULT);
    }

    /**
     * Ray starts in the entity's gravity frame. In the default frame these are
     * the bounding box's corners, face centers and edge midpoints (bottom row
     * raised 0.1 above the effective floor). Under a rotated frame the stored
     * AABB is only the world-aligned ENVELOPE of the tilted body (a capsule
     * under Gravity Unbound): its corners hang in the air around the body and
     * its floor is a side, not the feet — so the same layout is built on the
     * entity's own width × height box in ITS frame and rotated into the world
     * about the feet point.
     */
    public static List<Vec3> getRayStarts(Entity entity, FullStopConfig.RaycastMode mode, GravityFrame frame) {
        return getRayStarts(entity, mode, frame, sizeFactor(entity));
    }

    /** {@link #getRayStarts(Entity, FullStopConfig.RaycastMode, GravityFrame)} with the entity's {@link #sizeFactor} already worked out. */
    public static List<Vec3> getRayStarts(Entity entity, FullStopConfig.RaycastMode mode, GravityFrame frame, double size) {
        if (!frame.isDefault()) {
            return getRotatedRayStarts(entity, mode, frame, size);
        }
        AABB box = entity.getBoundingBox().inflate(RAY_START_MARGIN * size);
        double minX = box.minX;
        double minY = Math.min(effectiveFloorY(entity) + 0.1 * size, box.maxY);
        double minZ = box.minZ;
        double maxX = box.maxX;
        double maxY = box.maxY;
        double maxZ = box.maxZ;
        double cenX = box.getCenter().x;
        double cenY = box.getCenter().y;
        double cenZ = box.getCenter().z;

        List<Vec3> points = new ArrayList<>(switch (mode) {
            case CORNERS_ONLY -> 8;
            case CORNERS_AND_CENTERS -> 15;
            case FULL_SWEEP -> 27;
        });

        // 8 Corners (Always included in all modes)
        points.add(new Vec3(minX, minY, minZ));
        points.add(new Vec3(minX, minY, maxZ));
        points.add(new Vec3(minX, maxY, minZ));
        points.add(new Vec3(minX, maxY, maxZ));
        points.add(new Vec3(maxX, minY, minZ));
        points.add(new Vec3(maxX, minY, maxZ));
        points.add(new Vec3(maxX, maxY, minZ));
        points.add(new Vec3(maxX, maxY, maxZ));

        if (mode == FullStopConfig.RaycastMode.CORNERS_ONLY) {
            return points;
        }

        // 6 Face Centers + 1 Center
        points.add(new Vec3(minX, cenY, cenZ)); // West
        points.add(new Vec3(maxX, cenY, cenZ)); // East
        points.add(new Vec3(cenX, minY, cenZ)); // Bottom
        points.add(new Vec3(cenX, maxY, cenZ)); // Top
        points.add(new Vec3(cenX, cenY, minZ)); // North
        points.add(new Vec3(cenX, cenY, maxZ)); // South
        points.add(box.getCenter());

        if (mode == FullStopConfig.RaycastMode.CORNERS_AND_CENTERS) {
            return points;
        }

        // FULL_SWEEP: Add midpoints on edges for better coverage
        // 12 Edges
        points.add(new Vec3(cenX, minY, minZ)); // Bottom North Edge
        points.add(new Vec3(cenX, minY, maxZ)); // Bottom South Edge
        points.add(new Vec3(minX, minY, cenZ)); // Bottom West Edge
        points.add(new Vec3(maxX, minY, cenZ)); // Bottom East Edge

        points.add(new Vec3(cenX, maxY, minZ)); // Top North Edge
        points.add(new Vec3(cenX, maxY, maxZ)); // Top South Edge
        points.add(new Vec3(minX, maxY, cenZ)); // Top West Edge
        points.add(new Vec3(maxX, maxY, cenZ)); // Top East Edge

        points.add(new Vec3(minX, cenY, minZ)); // West North Edge
        points.add(new Vec3(minX, cenY, maxZ)); // West South Edge
        points.add(new Vec3(maxX, cenY, minZ)); // East North Edge
        points.add(new Vec3(maxX, cenY, maxZ)); // East South Edge

        return points;
    }

    private static List<Vec3> getRotatedRayStarts(Entity entity, FullStopConfig.RaycastMode mode, GravityFrame frame, double size) {
        // The entity's box in its own frame, feet at the origin, inflated by
        // the same margin as the vanilla path; bottom row raised 0.1 like the
        // vanilla minY so the rays never start inside the surface stood on.
        double half = entity.getBbWidth() / 2.0 + RAY_START_MARGIN * size;
        double top = entity.getBbHeight() + RAY_START_MARGIN * size;
        double bottom = Math.min(0.1 * size, top);
        double middle = entity.getBbHeight() / 2.0;
        Vec3 feet = entity.position();

        List<Vec3> points = new ArrayList<>(switch (mode) {
            case CORNERS_ONLY -> 8;
            case CORNERS_AND_CENTERS -> 15;
            case FULL_SWEEP -> 27;
        });

        // 8 corners
        addRotated(points, feet, frame, -half, bottom, -half);
        addRotated(points, feet, frame, -half, bottom, half);
        addRotated(points, feet, frame, -half, top, -half);
        addRotated(points, feet, frame, -half, top, half);
        addRotated(points, feet, frame, half, bottom, -half);
        addRotated(points, feet, frame, half, bottom, half);
        addRotated(points, feet, frame, half, top, -half);
        addRotated(points, feet, frame, half, top, half);

        if (mode == FullStopConfig.RaycastMode.CORNERS_ONLY) {
            return points;
        }

        // 6 face centers + 1 center
        addRotated(points, feet, frame, -half, middle, 0);
        addRotated(points, feet, frame, half, middle, 0);
        addRotated(points, feet, frame, 0, bottom, 0);
        addRotated(points, feet, frame, 0, top, 0);
        addRotated(points, feet, frame, 0, middle, -half);
        addRotated(points, feet, frame, 0, middle, half);
        addRotated(points, feet, frame, 0, middle, 0);

        if (mode == FullStopConfig.RaycastMode.CORNERS_AND_CENTERS) {
            return points;
        }

        // 12 edge midpoints
        addRotated(points, feet, frame, 0, bottom, -half);
        addRotated(points, feet, frame, 0, bottom, half);
        addRotated(points, feet, frame, -half, bottom, 0);
        addRotated(points, feet, frame, half, bottom, 0);

        addRotated(points, feet, frame, 0, top, -half);
        addRotated(points, feet, frame, 0, top, half);
        addRotated(points, feet, frame, -half, top, 0);
        addRotated(points, feet, frame, half, top, 0);

        addRotated(points, feet, frame, -half, middle, -half);
        addRotated(points, feet, frame, -half, middle, half);
        addRotated(points, feet, frame, half, middle, -half);
        addRotated(points, feet, frame, half, middle, half);

        return points;
    }

    private static void addRotated(List<Vec3> points, Vec3 feet, GravityFrame frame, double x, double y, double z) {
        points.add(feet.add(frame.localToWorld(x, y, z)));
    }
}
