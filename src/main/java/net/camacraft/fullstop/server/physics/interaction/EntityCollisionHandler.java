package net.camacraft.fullstop.server.physics.interaction;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.registry.ModEnchantments;
import net.camacraft.fullstop.common.util.EnchantmentUtils;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
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

/**
 * Entity-vs-entity momentum transfer (experimental; gated by entityCollisionDamage).
 * Impulses are inelastic-ish (restitution < 1) and resolved sequentially against
 * the running velocity. All velocity changes set hurtMarked so they reach clients.
 */
public final class EntityCollisionHandler {

    /** Bounciness of body-on-body collisions. 1.0 would be perfectly elastic. */
    private static final double RESTITUTION = 0.3;
    /** A falling slime/honey block makes any collision it is part of springy. */
    private static final double SLIME_CARRIER_RESTITUTION = 0.85;

    private EntityCollisionHandler() {
    }

    private static boolean isStickyCarrier(Entity entity) {
        return entity instanceof FallingBlockEntity fallingBlock && fallingBlock.getBlockState().isStickyBlock();
    }

    public static void handle(Entity entity, FullStopCapability fullstop, Collision collision) {
        if (!SERVER.entityCollisionDamage.get()) return;
        if (collision.collisionType != Collision.CollisionType.ENTITY) return;
        if (collision.collidingEntities.isEmpty()) return;

        Vec3 v1Initial = entity.hasImpulse ? entity.getDeltaMovement() : fullstop.getCurrentNativeVelocity();
        double m1 = EntityStackUtils.getEntityMass(entity);
        if (m1 <= 0) return;

        List<CollisionCandidate> candidates = new ArrayList<>();

        Vec3 entityCenterOffset = new Vec3(0, entity.getBbHeight() / 2, 0);
        Vec3 entityCenter = entity.position().add(entityCenterOffset);

        for (Entity other : collision.collidingEntities) {
            if (other == entity || !other.isAlive()) continue;

            Vec3 v2 = other.getDeltaMovement();

            Vec3 relativeVelocity = v1Initial.subtract(v2);

            Vec3 startCenter = entityCenter.subtract(relativeVelocity);
            Vec3 endCenter = entityCenter;

            AABB otherBox = other.getBoundingBox().inflate(
                    entity.getBbWidth() / 2,
                    entity.getBbHeight() / 2,
                    entity.getBbWidth() / 2
            );

            Vec3 otherCenter = other.getBoundingBox().getCenter();

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
                if (otherBox.contains(startCenter)) {
                    continue;
                }

                Optional<Vec3> hit = otherBox.clip(startCenter, endCenter);
                if (hit.isPresent()) {
                    hitPos = hit.get();
                    normal = hitPos.subtract(otherCenter).normalize();
                    currentDistSq = startCenter.distanceToSqr(hitPos);
                    valid = true;
                    passedThrough = true;
                } else if (entity.getBoundingBox().intersects(other.getBoundingBox())) {
                    valid = true;
                    currentDistSq = 0;
                }
            } else {
                if (entity.getBoundingBox().intersects(other.getBoundingBox())) {
                    currentDistSq = 0.0;
                    valid = true;
                } else {
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

        // Auto-mounting is a player-only convenience; mobs stacking on each other was chaos.
        if (entity instanceof Player) {
            double yDiff = entity.getY() - closest.other.getY();
            if (yDiff > closest.other.getBbHeight() * 0.5 && v1Initial.y < -0.2) {
                if (tryStartRidingSafely(entity, closest.other, fullstop)) {
                    return;
                }
            }
        }
        if (closest.other instanceof Player) {
            double yDiff = entity.getY() - closest.other.getY();
            if (yDiff < -entity.getBbHeight() * 0.5 && v1Initial.y > 0.2) {
                if (canRideSafely(closest.other, entity)) {
                    closest.other.startRiding(entity, true);
                    return;
                }
            }
        }

        if (closest.passedThrough) {
            Vec3 newPos = closest.hitPos.subtract(entityCenterOffset);
            entity.setPos(newPos);
        }

        double closestDistSq = closest.distSq;
        double tolerance = 0.1;
        List<CollisionCandidate> activeCandidates = new ArrayList<>();
        for (CollisionCandidate c : candidates) {
            if (c.distSq <= closestDistSq + tolerance) {
                activeCandidates.add(c);
            } else {
                break;
            }
        }

        int selfReflective = entity instanceof LivingEntity livingSelf
                ? EnchantmentUtils.totalArmorLevel(livingSelf, ModEnchantments.REFLECTIVE.get())
                : 0;

        Vec3 v1 = v1Initial;
        boolean anyCollision = false;

        for (CollisionCandidate c : activeCandidates) {
            Entity other = c.other;
            Vec3 normal = c.normal;
            Vec3 v2 = c.v2;

            double velAlongNormal = v1.subtract(v2).dot(normal);

            if (velAlongNormal > 0 && !c.passedThrough) {
                if (entity.getBoundingBox().intersects(other.getBoundingBox())) {
                    velAlongNormal = -0.1;
                } else {
                    continue;
                }
            }

            double m2 = EntityStackUtils.getEntityMass(other);
            if (m2 <= 0) continue;

            double restitution = RESTITUTION;
            if (isStickyCarrier(entity) || isStickyCarrier(other)) {
                restitution = SLIME_CARRIER_RESTITUTION;
            }

            double j = -(1 + restitution) * velAlongNormal;
            j /= (1 / m1 + 1 / m2);

            Vec3 impulse = normal.scale(j);

            // Reflective armor lets the wearer keep more of their momentum.
            Vec3 impulseOnSelf = impulse;
            if (selfReflective > 0) {
                impulseOnSelf = impulseOnSelf.scale(1.0 - (selfReflective * 0.1));
            }

            Vec3 impulseOnOther = impulse.scale(-1);
            if (other instanceof LivingEntity livingOther) {
                int otherReflective = EnchantmentUtils.totalArmorLevel(livingOther, ModEnchantments.REFLECTIVE.get());
                if (otherReflective > 0) {
                    // The wearer soaks less of the transfer and throws it back:
                    // whatever rams a reflective target rebounds harder.
                    impulseOnOther = impulseOnOther.scale(1.0 - (otherReflective * 0.1));
                    impulseOnSelf = impulseOnSelf.scale(1.0 + (otherReflective * 0.15));
                }
            }

            v1 = v1.add(impulseOnSelf.scale(1 / m1));

            other.setDeltaMovement(v2.add(impulseOnOther.scale(1 / m2)));
            other.hasImpulse = true;
            other.hurtMarked = true; // without this, living entities never sync the push to clients
            anyCollision = true;
        }

        if (anyCollision) {
            entity.setDeltaMovement(v1);
            entity.hasImpulse = true;
            entity.hurtMarked = true;
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

    private static final class CollisionCandidate {
        final Entity other;
        final Vec3 normal;
        final double distSq;
        final Vec3 v2;
        final Vec3 hitPos;
        final boolean passedThrough;

        CollisionCandidate(Entity other, Vec3 normal, double distSq, Vec3 v2, Vec3 hitPos, boolean passedThrough) {
            this.other = other;
            this.normal = normal;
            this.distSq = distSq;
            this.v2 = v2;
            this.hitPos = hitPos;
            this.passedThrough = passedThrough;
        }
    }
}
