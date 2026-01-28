package net.camacraft.fullstop.common.physics.util;

import net.minecraft.world.phys.Vec3;

public final class VelocityUtils {
    private VelocityUtils() {
    }

    public static boolean velocitiesAreSimilar(Vec3 v1, Vec3 v2, double threshold) {
        return v1.distanceTo(v2) < threshold;
    }
}
