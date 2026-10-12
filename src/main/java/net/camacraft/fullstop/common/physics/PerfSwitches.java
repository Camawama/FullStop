package net.camacraft.fullstop.common.physics;

/**
 * Test switches that put the 2026-10-10 performance work back to the old way, read live from system properties once a
 * level tick, so the pack harness can measure old against new in one session ({@code perf_mobs}). They change only
 * how much work is done, never what comes out. Nothing sets them in play.
 * <ul>
 * <li>{@code fullstop.perf.eagerBodies}: the body-collision pass works out every ride stack's body every tick, as
 *   before, not only the fast movers' and their neighbours'.</li>
 * <li>{@code fullstop.perf.alwaysClipLiquids}: every ray of a fast body looks for liquids that act solid at its speed,
 *   as before, even when none is in its swept volume.</li>
 * </ul>
 */
public final class PerfSwitches {
    private static boolean eagerBodies;
    private static boolean alwaysClipLiquids;

    private PerfSwitches() {
    }

    /** Called once at the start of each server level tick's physics pass. */
    public static void refresh() {
        eagerBodies = Boolean.getBoolean("fullstop.perf.eagerBodies");
        alwaysClipLiquids = Boolean.getBoolean("fullstop.perf.alwaysClipLiquids");
    }

    public static boolean eagerBodies() {
        return eagerBodies;
    }

    public static boolean alwaysClipLiquids() {
        return alwaysClipLiquids;
    }
}
