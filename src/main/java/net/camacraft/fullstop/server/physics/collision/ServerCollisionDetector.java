package net.camacraft.fullstop.server.physics.collision;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector;
import net.camacraft.fullstop.common.physics.rules.EntityCollisionRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

public class ServerCollisionDetector {

    public static Collision detect(Entity entity, FullStopCapability fullstop) {
        Collision blockCollision = CommonCollisionDetector.detectBlocks(entity, fullstop);
        
        Collision.CollisionType impactType = blockCollision.collisionType;
        List<Entity> collidingEntities = Collections.emptyList();

        if (SERVER.entityCollisionDamage.get()) {
            Level level = entity.level();
            AABB box = entity.getBoundingBox();
            Vec3 movement = fullstop.getPreviousNativeVelocity();
            // Expand backwards to catch entities we passed through
            AABB entityCheckBox = box.expandTowards(movement.scale(-1)).inflate(0.1);
            collidingEntities = level.getEntities(
                    entity,
                    entityCheckBox,
                    e -> (e instanceof LivingEntity || e instanceof Boat || e instanceof AbstractMinecart || e instanceof ItemEntity)
                            && e != entity
                            && !(entity instanceof ItemEntity && ((ItemEntity) entity).getOwner() == e)
                            && !(e.isPassengerOfSameVehicle(entity))
                            && !EntityCollisionRules.shouldIgnoreCollision(entity, e)
            );

            if (!collidingEntities.isEmpty()) {
                if (impactType.ordinal() < Collision.CollisionType.ENTITY.ordinal()) {
                    impactType = Collision.CollisionType.ENTITY;
                }
            }
        }

        return new Collision(impactType, blockCollision.highestYLevel, blockCollision.lowestYLevel, blockCollision.blockStates, collidingEntities, blockCollision.impactedPositions, blockCollision.impactedHits);
    }
}
