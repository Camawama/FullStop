package net.camacraft.fullstop.common.physics.math;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.capability.FullStopCapability.Provider.DELTAV_CAP;

public class VelocityMath {
    public static final double RESTING_Y_DELTA = 0.0784000015258789;

    public static Vec3 entityVelocity(Entity entity) {
        if (entity.getCapability(DELTAV_CAP).isPresent()) {
            Vec3 velocity = entity.getCapability(DELTAV_CAP).orElseThrow(IllegalStateException::new).getCurrentScaledVelocity();
            if (entity.onGround() && !(entity instanceof Player) && velocity.y < 0) {
                return velocity.add(0, RESTING_Y_DELTA * 20, 0);
            }
            return velocity;
        }

        Vec3 movement = entity.getDeltaMovement();
        if (entity.onGround() && movement.y < 0) {
            return movement.add(0, RESTING_Y_DELTA, 0).scale(20);
        }
        return movement.scale(20);
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
        if (targetVelocity.y() <=  attackerVelocity.y() && target.position().y() < attacker.position().y()) {
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
