package net.camacraft.fullstop.common.physics.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public final class EntityStackUtils {
    private EntityStackUtils() {
    }

    public static List<Entity> getAllEntitiesInStack(Entity start) {
        List<Entity> list = new ArrayList<>();

        list.add(start);
        for (Entity passenger : start.getPassengers()) {
            list.addAll(getAllEntitiesInStack(passenger));
        }

        return list;
    }

    public static List<Entity> getPassengersRecursive(Entity entity) {
        List<Entity> stack = getAllEntitiesInStack(entity);
        stack.remove(entity);
        return stack;
    }

    public static int getStackSize(Entity entity) {
        return getAllEntitiesInStack(entity).size();
    }

    public static double getEntityMass(Entity entity) {
        AABB box = entity.getBoundingBox();
        double entityVolume = (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);

        if (entity instanceof LivingEntity living && living.isFallFlying()) {
            return entityVolume * 3;
        }
        if (entity instanceof IronGolem) {
            return entityVolume * 8;
        }
        if (entity instanceof Skeleton || entity instanceof SkeletonHorse) {
            return entityVolume / 3;
        }
        if (entity instanceof ItemEntity) {
            return entityVolume * 0;
        }
        if (entity instanceof Ghast) {
            return entityVolume / 8;
        }
        if (entity instanceof Arrow) {
            return entityVolume * 1000000;
        }

        return entityVolume;
    }
}
