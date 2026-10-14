package net.camacraft.fullstop.common.compat;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Whether there is air at a place for a shock wave to form in: Camafy's {@code compat.space.Air.ambient} (Camafy 0.1.37
 * and later: VS Genesis's space, Ad Astra's orbits, the Moon...), reached by name since FullStop builds against an older
 * Camafy. With an older Camafy: "no gravity there, no air" (Genesis's space and Ad Astra's orbits only), as before.
 */
public final class AirCompat {

    private static final MethodHandle AMBIENT = find();

    private AirCompat() {
    }

    private static MethodHandle find() {
        try {
            Class<?> air = Class.forName("net.camacraft.camafy.compat.space.Air");
            return MethodHandles.publicLookup().findStatic(air, "ambient", MethodType.methodType(boolean.class, Level.class, BlockPos.class));
        } catch (ReflectiveOperationException | LinkageError none) {
            return null;
        }
    }

    /** Whether the place at {@code at} has air of its own (sound carries, a body faster than sound drags a shock cone). */
    public static boolean hasAir(Level level, Vec3 at) {
        if (AMBIENT != null) {
            try {
                return (boolean) AMBIENT.invokeExact(level, BlockPos.containing(at));
            } catch (Throwable ignored) {
                // fall through to the gravity rule
            }
        }
        return Gravity.ambientStrength(level, at) >= Gravity.WEIGHTLESS_BELOW;
    }
}
