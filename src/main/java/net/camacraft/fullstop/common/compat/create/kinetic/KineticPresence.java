package net.camacraft.fullstop.common.compat.create.kinetic;

import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Whether any Create kinetic block entity in a level is turning: every spinning one notes its level as it ticks
 * ({@code KineticTickMixin}, both sides), and {@link KineticHazards} stands down for every body in a level where none
 * has within the last two ticks ({@code idleFastPaths}). A kinetic block that doesn't tick (an unloaded or lazily
 * loaded chunk) can't be turning next to a body that does.
 */
public final class KineticPresence {

    /** Level to the game time a turning kinetic block entity last ticked in it. */
    private static final Map<Level, long[]> LAST_SPIN = Collections.synchronizedMap(new WeakHashMap<>());

    private KineticPresence() {
    }

    /** A kinetic block entity with a non-zero speed ticks in {@code level} now. */
    public static void note(Level level) {
        long[] at = LAST_SPIN.get(level);
        long now = level.getGameTime();
        if (at == null) {
            LAST_SPIN.put(level, new long[] {now});
        } else {
            at[0] = now;
        }
    }

    /** Whether a turning kinetic block entity ticked in {@code level} this tick or the one before. */
    public static boolean anySpinning(Level level, long now) {
        long[] at = LAST_SPIN.get(level);
        return at != null && now - at[0] <= 2L;
    }
}
