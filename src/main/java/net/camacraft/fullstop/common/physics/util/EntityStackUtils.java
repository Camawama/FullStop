package net.camacraft.fullstop.common.physics.util;

import net.minecraft.world.entity.Entity;

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
}
