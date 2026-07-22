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
        // Projectiles have their own impact handling; FullStop does not raycast for them.
        if (entity instanceof Projectile) {
            return 0;
        }
        return Math.max(fullstop.getPreImpactNativeVelocity().length(), MIN_RAY_LENGTH) + 0.01;
    }

    public static List<Vec3> getRayStarts(Entity entity, FullStopConfig.RaycastMode mode) {
        AABB box = entity.getBoundingBox();
        double minX = box.minX;
        double minY = box.minY + 0.1;
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
