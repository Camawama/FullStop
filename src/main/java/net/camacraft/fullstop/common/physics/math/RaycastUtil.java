package net.camacraft.fullstop.common.physics.math;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class RaycastUtil {

    public static Vec3 getRayDirection(FullStopCapability fullstop) {
        Vec3 velocity = fullstop.getPreviousScaledVelocity();
        if (velocity != null && velocity.lengthSqr() > 0.0001) {
            return velocity.normalize();
        }

        Vec3 acc = fullstop.getAcceleration();
        if (acc == null) return Vec3.ZERO;
        return acc.normalize();
    }

    public static double getRayLength(Entity entity, FullStopCapability fullstop) {
        Vec3 previousVelocity = fullstop.getPreviousScaledVelocity().scale(0.05);
        if (entity instanceof Arrow) {
            return Math.min(previousVelocity.length(), 0.00);
        } else {
            // TODO: Implement proper shape intersection to avoid this band-aid
            return previousVelocity.length() + 0.01;
        }
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

        List<Vec3> points = new ArrayList<>();

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
    
    // Backward compatibility method
    public static List<Vec3> getRayStarts(Entity entity) {
        return getRayStarts(entity, FullStopConfig.RaycastMode.CORNERS_AND_CENTERS);
    }
}
