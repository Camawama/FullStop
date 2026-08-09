package net.camacraft.fullstop.common.physics.math;

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
        // Projectiles own their impacts (arrows stick, tridents return), so
        // FullStop does not raycast for them — unless a pack opts one in via
        // fullstop:bouncing_projectiles (modded grappling hooks and the like),
        // in which case the pre-contact bounce redirects it before its own
        // onHit ever fires.
        if (entity instanceof Projectile
                && !entity.getType().is(net.camacraft.fullstop.common.physics.rules.FullStopTags.BOUNCING_PROJECTILES)) {
            return 0;
        }
        return Math.max(fullstop.getPreImpactNativeVelocity().length(), MIN_RAY_LENGTH) + 0.01;
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
        AABB box = entity.getBoundingBox().inflate(RAY_START_MARGIN);
        double minX = box.minX;
        double minY = Math.min(effectiveFloorY(entity) + 0.1, box.maxY);
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
}
