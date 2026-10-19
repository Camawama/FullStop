package net.camacraft.fullstop.common.physics;

import net.camacraft.fullstop.FullStopConfig;

/**
 * Test switches that put the 2026-10-10 and 2026-10-17 performance work back to the old way, read live from system
 * properties once a level tick, so the pack harness can measure old against new in one session ({@code perf_mobs}).
 * They change only how much work is done, never (or, for the idle fast paths, hardly) what comes out. Nothing sets
 * them in play.
 * <ul>
 * <li>{@code fullstop.perf.eagerBodies}: the body-collision pass works out every ride stack's body every tick, as
 *   before, not only the fast movers' and their neighbours'.</li>
 * <li>{@code fullstop.perf.alwaysClipLiquids}: every ray of a fast body looks for liquids that act solid at its speed,
 *   as before, even when none is in its swept volume.</li>
 * <li>{@code fullstop.perf.noIdleFastPaths}: the idle fast paths of 2026-10-17 off (the {@code idleFastPaths} config
 *   setting forced off): every resting body is measured every tick, the Create hazard tick runs for every body with
 *   no kinetic block turning, the honey-wall field is scanned every tick, and a body's health is read every tick.</li>
 * </ul>
 */
public final class PerfSwitches {
    private static boolean eagerBodies;
    private static boolean alwaysClipLiquids;
    private static boolean idleFastPaths = true;

    private PerfSwitches() {
    }

    /** Called once at the start of each server level tick's physics pass. */
    public static void refresh() {
        eagerBodies = Boolean.getBoolean("fullstop.perf.eagerBodies");
        alwaysClipLiquids = Boolean.getBoolean("fullstop.perf.alwaysClipLiquids");
        idleFastPaths = !Boolean.getBoolean("fullstop.perf.noIdleFastPaths")
                && (!FullStopConfig.SERVER_SPEC.isLoaded() || FullStopConfig.SERVER.idleFastPaths.get());
    }

    public static boolean eagerBodies() {
        return eagerBodies;
    }

    public static boolean alwaysClipLiquids() {
        return alwaysClipLiquids;
    }

    /**
     * The idle fast paths ({@code idleFastPaths}, General settings): a body at rest on the ground skips its physics
     * measurement, the Create hazard tick stands down while no kinetic block turns in the level, the honey-wall field
     * is scanned once per block a body moves through, and a body's health (the dead check) is read once a second.
     */
    public static boolean idleFastPaths() {
        return idleFastPaths;
    }
}
