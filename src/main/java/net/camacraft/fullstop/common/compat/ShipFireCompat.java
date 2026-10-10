package net.camacraft.fullstop.common.compat;

import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Method;

/**
 * Camafy's burning ships ({@code compat.vs.ShipFire}, Camafy 0.1.28 and later), reached by name: FullStop builds
 * against an older Camafy, and a pack may run one without ship fire at all. The whole bridge is this class; with a
 * Camafy that has no ship fire every call answers "not burning".
 *
 * <p>{@link #reduce} takes time off a burning ship's fire. Camafy 0.1.33 does that itself
 * ({@code ShipFire.reduceFireTicks}); before it a ship's fire time could only be read and the fire put out, so the
 * caller keeps what it took ({@link #reduce} returns false) and puts the ship out once that reaches what is left.
 */
public final class ShipFireCompat {

    private static final Method FIRE_TICKS;
    private static final Method EXTINGUISH;
    private static final Method REDUCE;

    static {
        Method fireTicks = null, extinguish = null, reduce = null;
        try {
            Class<?> fire = Class.forName("net.camacraft.camafy.compat.vs.ShipFire");
            fireTicks = fire.getMethod("fireTicks", ServerLevel.class, long.class);
            extinguish = fire.getMethod("extinguish", ServerLevel.class, long.class);
            try {
                reduce = fire.getMethod("reduceFireTicks", ServerLevel.class, long.class, int.class);
            } catch (NoSuchMethodException older) {
                reduce = null;
            }
        } catch (ReflectiveOperationException | LinkageError none) {
            fireTicks = null;
            extinguish = null;
        }
        FIRE_TICKS = fireTicks;
        EXTINGUISH = extinguish;
        REDUCE = reduce;
    }

    private ShipFireCompat() {
    }

    /** Whether this Camafy has burning ships (and Valkyrien Skies is there). */
    public static boolean available() {
        return FIRE_TICKS != null && EXTINGUISH != null && Ships.available();
    }

    /** Whether this Camafy takes time off a ship's fire itself (0.1.33 and later). */
    public static boolean reduces() {
        return REDUCE != null;
    }

    /** The ship's remaining fire ticks; 0 when it isn't burning (or there is no ship fire). */
    public static int fireTicks(ServerLevel level, long shipId) {
        if (!available()) return 0;
        try {
            return (Integer) FIRE_TICKS.invoke(null, level, shipId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * Takes {@code ticks} off the ship's fire. True when Camafy did (the fire goes out by itself at 0); false when this
     * Camafy can't, and the caller has to count and call {@link #extinguish} in the end.
     */
    public static boolean reduce(ServerLevel level, long shipId, int ticks) {
        if (REDUCE == null || !available()) return false;
        try {
            REDUCE.invoke(null, level, shipId, ticks);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public static void extinguish(ServerLevel level, long shipId) {
        if (!available()) return;
        try {
            EXTINGUISH.invoke(null, level, shipId);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // nothing to put out
        }
    }
}
