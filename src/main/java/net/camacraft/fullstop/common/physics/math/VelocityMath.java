package net.camacraft.fullstop.common.physics.math;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

public class VelocityMath {

    /** Entity velocity in m/s: the capability's measured value when present, else deltaMovement. */
    public static Vec3 entityVelocity(Entity entity) {
        FullStopCapability cap = FullStopCapability.grabCapability(entity);
        if (cap != null) {
            return cap.getCurrentScaledVelocity();
        }
        return entity.getDeltaMovement().scale(20);
    }

    public static double calculateApproachVelocity(Entity attacker, Entity target) {
        Vec3 attackerVelocity =
                attacker instanceof Projectile projectile
                        ? entityVelocity(projectile).subtract(entityVelocity(projectile).scale(SERVER.projectileMultiplier.get()))
                        : entityVelocity(attacker);
        Vec3 targetVelocity = entityVelocity(target);

        if (attackerVelocity.length() == 0 && targetVelocity.length() == 0) {
            return 0;
        }

        Vec3 attackerPosition = attacker.position();
        Vec3 targetPosition = target.position();

        if (targetVelocity.y() >= attackerVelocity.y() && target.position().y() > attacker.position().y()) {
            attackerPosition = attacker.getEyePosition();
        }
        if (targetVelocity.y() <= attackerVelocity.y() && target.position().y() < attacker.position().y()) {
            targetPosition = target.getEyePosition();
        }

        Vec3 velocityDifference = attackerVelocity.subtract(targetVelocity);
        Vec3 directionToTarget = targetPosition.subtract(attackerPosition).normalize();

        return directionToTarget.dot(velocityDifference);
    }

    public static boolean velocitiesAreSimilar(Vec3 v1, Vec3 v2, double threshold) {
        return v1.distanceTo(v2) < threshold;
    }
}
