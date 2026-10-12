package net.camacraft.fullstop.common.compat;

import java.lang.reflect.Method;

/**
 * Camafy's ship-carry rules ({@code compat.vs.ShipCarry}, Camafy 0.1.36 and later), reached by name: FullStop builds
 * against an older Camafy. When they are on, Camafy decides when Valkyrien Skies stops carrying a body that left the
 * deck (no block of the ship under it any more), and FullStop only keeps the momentum the player left with and lets
 * go of a player that jumps. With an older Camafy, or its rules switched off, FullStop's own "three ticks off the deck"
 * release stays.
 */
public final class ShipCarryCompat {

    private static final Method ENABLED = find();

    private ShipCarryCompat() {
    }

    private static Method find() {
        try {
            return Class.forName("net.camacraft.camafy.compat.vs.ShipCarry").getMethod("enabled");
        } catch (ReflectiveOperationException | LinkageError none) {
            return null;
        }
    }

    /** Whether Camafy's rules decide when a body that left the deck is let go of. */
    public static boolean camafyDecides() {
        if (ENABLED == null) return false;
        try {
            return (Boolean) ENABLED.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
