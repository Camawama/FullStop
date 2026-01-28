package net.camacraft.fullstop.common.physics.interaction;

import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.Physics;
import net.camacraft.fullstop.common.physics.util.EntityStackUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.capabilities.FullStopCapability.grabCapability;

public final class EntityCollisionHandler {
    private EntityCollisionHandler() {
    }

    public static void handle(Physics physics) {
        if (!SERVER.entityCollisionDamage.get()) return;
        Collision collision = physics.getCollision();
        if (collision.collisionType != Collision.CollisionType.ENTITY) return;
        if (collision.collidingEntities.isEmpty()) return;

        Entity entity = physics.getEntity();
        FullStopCapability fullstop = physics.getFullstop();

        Vec3 v1 = fullstop.getPreviousNativeVelocity();
        double m1 = EntityStackUtils.getEntityMass(entity);
        boolean ridingActionTaken = false;

        for (Entity other : collision.collidingEntities) {
            if (other == entity) continue;
            if (!other.isAlive()) continue;
            if (ridingActionTaken) break;

            double m2 = EntityStackUtils.getEntityMass(other);

            Vec3 v2 = other.getDeltaMovement();
            FullStopCapability otherCap = grabCapability(other);
            if (otherCap != null) {
                v2 = otherCap.getPreviousNativeVelocity();
            }

            Vec3 dist = entity.position().subtract(other.position());
            if (dist.lengthSqr() < 1.0E-7) {
                dist = v1.subtract(v2);
                if (dist.lengthSqr() < 1.0E-7) {
                    dist = new Vec3(0, 1, 0);
                }
            }
            Vec3 normal = dist.normalize();

            Vec3 relativeVelocity = v1.subtract(v2);
            double velAlongNormal = relativeVelocity.dot(normal);

            if (velAlongNormal > 0) continue;

            double yDiff = entity.getY() - other.getY();

            if (yDiff > other.getBbHeight() * 0.5 && v1.y < -0.2) {
                if (tryStartRidingSafely(entity, other, fullstop)) {
                    ridingActionTaken = true;
                    continue;
                }
            }

            if (yDiff < -entity.getBbHeight() * 0.5 && v1.y > 0.2) {
                if (canRideSafely(other, entity)) {
                    other.startRiding(entity, true);
                    ridingActionTaken = true;
                    continue;
                }
            }

            double restitution = 0.4;

            double j = -(1 + restitution) * velAlongNormal;
            j /= (1 / m1 + 1 / m2);

            Vec3 impulse = normal.scale(j);

            Vec3 v1New = v1.add(impulse.scale(1 / m1));
            Vec3 v2New = v2.subtract(impulse.scale(1 / m2));

            entity.setDeltaMovement(v1New);
            other.setDeltaMovement(v2New);
            other.hasImpulse = true;

            v1 = v1New;
        }
    }

    public static boolean tryStartRidingSafely(Entity rider, Entity vehicle, FullStopCapability fullstop) {
        if (vehicle == null) return false;
        if (rider.level().isClientSide()) return false;
        if (!rider.isAlive() || !vehicle.isAlive()) return false;
        if (rider == vehicle) return false;

        if (rider.isCrouching()) return false;

        if (rider.getVehicle() != null) return false;
        if (vehicle.getVehicle() == rider) return false;
        if (!vehicle.getPassengers().isEmpty()) return false;
        if (rider.isPassengerOfSameVehicle(vehicle)) return false;

        if (Objects.requireNonNull(fullstop).getDismountCooldown() > 0) return false;

        if (isInPassengerChain(rider, vehicle) || isInPassengerChain(vehicle, rider)) return false;

        return rider.startRiding(vehicle, true);
    }

    public static boolean canRideSafely(Entity rider, Entity vehicle) {
        if (vehicle == null || rider == null) return false;
        if (rider.level().isClientSide()) return false;
        if (!rider.isAlive() || !vehicle.isAlive()) return false;
        if (rider == vehicle) return false;
        if (rider.isCrouching()) return false;
        if (rider.getVehicle() != null) return false;
        if (vehicle.getVehicle() == rider) return false;
        if (!vehicle.getPassengers().isEmpty()) return false;
        if (rider.isPassengerOfSameVehicle(vehicle)) return false;

        FullStopCapability riderCap = grabCapability(rider);
        if (riderCap != null && riderCap.getDismountCooldown() > 0) return false;

        if (isInPassengerChain(rider, vehicle) || isInPassengerChain(vehicle, rider)) return false;

        return true;
    }

    private static boolean isInPassengerChain(Entity possibleAncestor, Entity possibleDescendant) {
        Entity v = possibleDescendant;
        while (v != null) {
            Entity vehicle = v.getVehicle();
            if (vehicle == null) return false;
            if (vehicle == possibleAncestor) return true;
            v = vehicle;
        }
        return false;
    }
}
