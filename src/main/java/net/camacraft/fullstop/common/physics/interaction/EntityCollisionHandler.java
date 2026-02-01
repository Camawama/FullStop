package net.camacraft.fullstop.common.physics.interaction;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.rules.EntityPhysicsRules;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability;

public final class EntityCollisionHandler {
    private EntityCollisionHandler() {
    }

    public static void handle(Entity entity, FullStopCapability fullstop, Collision collision) {
        if (!SERVER.entityCollisionDamage.get()) return;
        if (collision.collisionType != Collision.CollisionType.ENTITY) return;
        if (collision.collidingEntities.isEmpty()) return;

        if (entity.hasImpulse) return;

        Vec3 v1 = fullstop.getCurrentNativeVelocity();
        double m1 = EntityPhysicsRules.getEntityMass(entity);
        
        Entity bestOther = null;
        Vec3 bestNormal = null;
        Vec3 bestV2 = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Entity other : collision.collidingEntities) {
            if (other == entity) continue;
            if (!other.isAlive()) continue;

            Vec3 v2 = other.getDeltaMovement();
            FullStopCapability otherCap = grabCapability(other);
            if (otherCap != null) {
                if (other.tickCount != otherCap.getLastTick()) {
                    otherCap.tick(other);
                    otherCap.setLastTick(other.tickCount);
                }
                v2 = otherCap.getCurrentNativeVelocity();
            }

            Vec3 relativeVelocity = v1.subtract(v2);
            
            Vec3 distVec = entity.position().subtract(other.position());
            if (distVec.lengthSqr() < 1.0E-7) {
                distVec = relativeVelocity;
                if (distVec.lengthSqr() < 1.0E-7) distVec = new Vec3(0, 1, 0);
            }
            Vec3 normal = distVec.normalize();
            double velAlongNormal = relativeVelocity.dot(normal);
            
            double currentDistSq = Double.MAX_VALUE;
            boolean valid = false;

            if (velAlongNormal > 0) {
                // Separating or passed through
                AABB otherBox = other.getBoundingBox().inflate(entity.getBbWidth() / 2, entity.getBbHeight() / 2, entity.getBbWidth() / 2);
                Vec3 start = entity.position().subtract(relativeVelocity);
                
                if (otherBox.contains(start)) {
                    continue;
                }

                boolean intersecting = entity.getBoundingBox().intersects(other.getBoundingBox());
                Vec3 hitPos = null;

                if (intersecting) {
                    Vec3 end = entity.position();
                    Optional<Vec3> hit = otherBox.clip(start, end);
                    if (hit.isPresent()) {
                        hitPos = hit.get();
                    } else {
                        hitPos = entity.position();
                    }
                    valid = true;
                } else {
                    Vec3 end = entity.position();
                    Optional<Vec3> hit = otherBox.clip(start, end);
                    if (hit.isPresent()) {
                        hitPos = hit.get();
                        valid = true;
                    }
                }

                if (valid) {
                    normal = relativeVelocity.normalize().scale(-1);
                    currentDistSq = start.distanceToSqr(hitPos);
                }
            } else {
                // Approaching
                if (entity.getBoundingBox().intersects(other.getBoundingBox())) {
                    currentDistSq = 0.0;
                    valid = true;
                } else {
                    // Check swept
                    AABB otherBox = other.getBoundingBox().inflate(entity.getBbWidth() / 2, entity.getBbHeight() / 2, entity.getBbWidth() / 2);
                    Vec3 start = entity.position().subtract(relativeVelocity);
                    Vec3 end = entity.position();
                    
                    Optional<Vec3> hit = otherBox.clip(start, end);
                    if (hit.isPresent()) {
                        currentDistSq = start.distanceToSqr(hit.get());
                        valid = true;
                    }
                }
            }

            if (valid && currentDistSq < bestDistSq) {
                bestDistSq = currentDistSq;
                bestOther = other;
                bestNormal = normal;
                bestV2 = v2;
            }
        }

        if (bestOther != null) {
            Entity other = bestOther;
            Vec3 normal = bestNormal;
            Vec3 v2 = bestV2;
            
            Vec3 relativeVelocity = v1.subtract(v2);
            double velAlongNormal = relativeVelocity.dot(normal);

            double yDiff = entity.getY() - other.getY();

            if (yDiff > other.getBbHeight() * 0.5 && v1.y < -0.2) {
                if (tryStartRidingSafely(entity, other, fullstop)) {
                    return;
                }
            }

            if (yDiff < -entity.getBbHeight() * 0.5 && v1.y > 0.2) {
                if (canRideSafely(other, entity)) {
                    other.startRiding(entity, true);
                    return;
                }
            }

            double restitution = 0.4;
            double m2 = EntityPhysicsRules.getEntityMass(other);

            double j = -(1 + restitution) * velAlongNormal;
            j /= (1 / m1 + 1 / m2);

            Vec3 impulse = normal.scale(j);

            Vec3 v1New = v1.add(impulse.scale(1 / m1));
            Vec3 v2New = v2.subtract(impulse.scale(1 / m2));

            entity.setDeltaMovement(v1New);
            other.setDeltaMovement(v2New);
            other.hasImpulse = true;
            entity.hasImpulse = true;
        }
    }

    public static boolean tryStartRidingSafely(Entity rider, Entity vehicle, FullStopCapability fullstop) {
        if (canRideSafely(rider, vehicle)) {
            if (Objects.requireNonNull(fullstop).getDismountCooldown() > 0) return false;
            return rider.startRiding(vehicle, true);
        }
        return false;
    }

    public static boolean canRideSafely(Entity rider, Entity vehicle) {
//        if (rider instanceof LivingEntity living) {
//            
//        }

        if (vehicle == null || rider == null) return false;
        if (rider.level().isClientSide()) return false;
        if (!rider.isAlive() || !vehicle.isAlive()) return false;
        if (rider == vehicle) return false;
        if (rider.isCrouching()) return false;
        if (vehicle.isCrouching()) return false;
        if (rider instanceof Player player && player.getAbilities().flying) return false;
        if (rider.getVehicle() != null) return false;
        if (vehicle.getVehicle() == rider) return false;
        if (!vehicle.getPassengers().isEmpty()) return false;
        if (rider.isPassengerOfSameVehicle(vehicle)) return false;
        if (rider instanceof ItemEntity) return false;

        double riderMass = EntityPhysicsRules.getEntityMass(rider);
        double vehicleMass = EntityPhysicsRules.getEntityMass(vehicle);
        if (riderMass > vehicleMass) return false;

        if (rider.getType() == vehicle.getType()) {
            if (riderMass >= vehicleMass) {
                if (!(rider instanceof LivingEntity living && living.isBaby())) {
                    return false;
                }
            }
        }

        FullStopCapability riderCap = grabCapability(rider);
        if (riderCap != null && riderCap.getDismountCooldown() > 0) return false;

        if (EntityStackUtils.isInPassengerChain(rider, vehicle) || EntityStackUtils.isInPassengerChain(vehicle, rider)) return false;

        return true;
    }
}
