package net.camacraft.fullstop.common.physics.interaction;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.message.LogToChat;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

        //LogToChat.sendTo(entity, entity.getName(), "has collided with", collision.collidingEntities.stream().findFirst().get().getName());

        Vec3 v1Initial = entity.hasImpulse ? entity.getDeltaMovement() : fullstop.getCurrentNativeVelocity();
        double m1 = EntityStackUtils.getEntityMass(entity);

        List<CollisionCandidate> candidates = new ArrayList<>();
        
        // Use center positions for more accurate swept collision
        Vec3 entityCenterOffset = new Vec3(0, entity.getBbHeight() / 2, 0);
        Vec3 entityPos = entity.position();
        Vec3 entityCenter = entityPos.add(entityCenterOffset);

        for (Entity other : collision.collidingEntities) {
            if (other == entity) continue;
            if (!other.isAlive()) continue;

            Vec3 v2 = other.getDeltaMovement();
            if (!other.hasImpulse) {
                FullStopCapability otherCap = grabCapability(other);
                if (otherCap != null) {
                    if (other.tickCount != otherCap.getLastTick()) {
                        otherCap.tick(other);
                        otherCap.setLastTick(other.tickCount);
                    }
                    v2 = otherCap.getCurrentNativeVelocity();
                }
            }

            Vec3 relativeVelocity = v1Initial.subtract(v2);
            
            // Calculate start and end points for the entity's center
            Vec3 startCenter = entityCenter.subtract(relativeVelocity);
            Vec3 endCenter = entityCenter;

            // Inflate other's box by entity's size to create the configuration space obstacle
            // We use the center of 'other' as the reference, so we inflate by half dimensions on all sides
            AABB otherBox = other.getBoundingBox().inflate(
                    entity.getBbWidth() / 2, 
                    entity.getBbHeight() / 2, 
                    entity.getBbWidth() / 2
            );
            
            Vec3 otherCenter = other.getBoundingBox().getCenter();
            
            // Default normal and distance
            Vec3 distVec = entityCenter.subtract(otherCenter);
            if (distVec.lengthSqr() < 1.0E-7) {
                distVec = relativeVelocity;
                if (distVec.lengthSqr() < 1.0E-7) distVec = new Vec3(0, 1, 0);
            }
            Vec3 normal = distVec.normalize();
            double velAlongNormal = relativeVelocity.dot(normal);
            
            double currentDistSq = Double.MAX_VALUE;
            boolean valid = false;
            boolean passedThrough = false;
            Vec3 hitPos = entityCenter;

            if (velAlongNormal > 0) {
                // Separating or potentially passed through
                if (otherBox.contains(startCenter)) {
                    continue; // Started inside, ignoring
                }

                // Check for pass-through using raycast on the inflated box
                Optional<Vec3> hit = otherBox.clip(startCenter, endCenter);
                if (hit.isPresent()) {
                    hitPos = hit.get();
                    // Normal is vector from other center to hit position (center of entity at impact)
                    normal = hitPos.subtract(otherCenter).normalize();
                    currentDistSq = startCenter.distanceToSqr(hitPos);
                    valid = true;
                    passedThrough = true;
                } else if (entity.getBoundingBox().intersects(other.getBoundingBox())) {
                    // Intersecting but didn't clip? (Maybe just touching)
                    valid = true;
                    currentDistSq = 0;
                }
            } else {
                // Approaching
                if (entity.getBoundingBox().intersects(other.getBoundingBox())) {
                    currentDistSq = 0.0;
                    valid = true;
                } else {
                    // Check swept
                    Optional<Vec3> hit = otherBox.clip(startCenter, endCenter);
                    if (hit.isPresent()) {
                        hitPos = hit.get();
                        normal = hitPos.subtract(otherCenter).normalize();
                        currentDistSq = startCenter.distanceToSqr(hitPos);
                        valid = true;
                    }
                }
            }

            if (valid) {
                candidates.add(new CollisionCandidate(other, normal, currentDistSq, v2, hitPos, passedThrough));
            }
        }

        if (candidates.isEmpty()) return;

        candidates.sort(Comparator.comparingDouble(c -> c.distSq));

        CollisionCandidate closest = candidates.get(0);
        
        // Riding check on closest
        double yDiff = entity.getY() - closest.other.getY();
        if (yDiff > closest.other.getBbHeight() * 0.5 && v1Initial.y < -0.2) {
            if (tryStartRidingSafely(entity, closest.other, fullstop)) {
                return;
            }
        }
        if (yDiff < -entity.getBbHeight() * 0.5 && v1Initial.y > 0.2) {
            if (canRideSafely(closest.other, entity)) {
                closest.other.startRiding(entity, true);
                return;
            }
        }

        // If we passed through the closest entity, move back to the impact point
        if (closest.passedThrough) {
            // hitPos is the center position. Convert to bottom position.
            Vec3 newPos = closest.hitPos.subtract(entityCenterOffset);
            // Move slightly back along normal to avoid sticking?
            // newPos = newPos.add(closest.normal.scale(0.01));
            entity.setPos(newPos);
            // Update entityCenter for subsequent calculations if needed, though we use v1Initial
        }

        // Filter for simultaneous collisions
        double closestDistSq = closest.distSq;
        double tolerance = 0.1; // Tolerance for "same time"
        List<CollisionCandidate> activeCandidates = new ArrayList<>();
        for (CollisionCandidate c : candidates) {
            if (c.distSq <= closestDistSq + tolerance) {
                activeCandidates.add(c);
            } else {
                break;
            }
        }

        int n = activeCandidates.size();
        Vec3 totalImpulseOnEntity = Vec3.ZERO;
        boolean anyCollision = false;

        for (CollisionCandidate c : activeCandidates) {
            Entity other = c.other;
            Vec3 normal = c.normal;
            Vec3 v2 = c.v2;

            Vec3 relativeVelocity = v1Initial.subtract(v2);
            double velAlongNormal = relativeVelocity.dot(normal);

            // If we passed through, we force the collision response even if velAlongNormal > 0
            // because we've already moved the entity back (conceptually or physically)
            // and the normal points towards the incoming direction.
            // Wait, if passedThrough is true, normal points from other -> impact.
            // Entity was moving start -> end. Impact is between.
            // v_rel is start -> end (roughly).
            // So v_rel dot normal should be negative (opposing).
            
            // However, if we are just intersecting and separating, we might want to push apart.
            if (velAlongNormal > 0 && !c.passedThrough) {
                 if (entity.getBoundingBox().intersects(other.getBoundingBox())) {
                     // Push apart
                     velAlongNormal = -0.1; 
                 } else {
                     continue;
                 }
            }

            double m2 = EntityStackUtils.getEntityMass(other);
            double restitution = 1.0; // Perfectly elastic

            double j = -(1 + restitution) * velAlongNormal;
            j /= (n / m1 + 1 / m2);

            Vec3 impulse = normal.scale(j);
            
            totalImpulseOnEntity = totalImpulseOnEntity.add(impulse);
            
            Vec3 impulseOnOther = impulse.scale(-1);
            Vec3 v2New = v2.add(impulseOnOther.scale(1 / m2));
            
            other.setDeltaMovement(v2New);
            other.hasImpulse = true;
            anyCollision = true;
        }
        
        if (anyCollision) {
            Vec3 v1New = v1Initial.add(totalImpulseOnEntity.scale(1 / m1));
            entity.setDeltaMovement(v1New);
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

        double riderMass = EntityStackUtils.getEntityMass(rider);
        double vehicleMass = EntityStackUtils.getEntityMass(vehicle);
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

    private static class CollisionCandidate {
        final Entity other;
        final Vec3 normal;
        final double distSq;
        final Vec3 v2;
        final Vec3 hitPos;
        final boolean passedThrough;

        public CollisionCandidate(Entity other, Vec3 normal, double distSq, Vec3 v2, Vec3 hitPos, boolean passedThrough) {
            this.other = other;
            this.normal = normal;
            this.distSq = distSq;
            this.v2 = v2;
            this.hitPos = hitPos;
            this.passedThrough = passedThrough;
        }
    }
}