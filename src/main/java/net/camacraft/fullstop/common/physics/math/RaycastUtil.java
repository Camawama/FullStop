package net.camacraft.fullstop.common.physics.math;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
            return previousVelocity.length() + 0.01; // ADJUST THIS VALUE TO FIX COLLIDING THROUGH BLOCKS
        }
    }

    public static List<Vec3> getRayStarts(Entity entity) {
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

        return List.of(
                // 8 Corners
                new Vec3(minX, minY, minZ),
                new Vec3(minX, minY, maxZ),
                new Vec3(minX, maxY, minZ),
                new Vec3(minX, maxY, maxZ),
                new Vec3(maxX, minY, minZ),
                new Vec3(maxX, minY, maxZ),
                new Vec3(maxX, maxY, minZ),
                new Vec3(maxX, maxY, maxZ),

                // 6 Face Centers
                new Vec3(minX, cenY, cenZ), // West
                new Vec3(maxX, cenY, cenZ), // East
                new Vec3(cenX, minY, cenZ), // Bottom
                new Vec3(cenX, maxY, cenZ), // Top
                new Vec3(cenX, cenY, minZ), // North
                new Vec3(cenX, cenY, maxZ), // South

                // 1 Center
                box.getCenter()
        );
    }
}
