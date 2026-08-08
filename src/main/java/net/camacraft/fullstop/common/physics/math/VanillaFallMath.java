package net.camacraft.fullstop.common.physics.math;

/**
 * Maps a measured impact speed back to the vanilla fall distance that produces
 * it, using vanilla's own per-tick integration for living entities
 * ({@code v' = (v + 0.08) * 0.98}, distance accruing one move per tick).
 * Backs the VANILLA_PARITY fall damage mode: FullStop measures impacts as
 * stopping force (m/s), vanilla bills them as blocks fallen — this is the
 * bridge between the two, so a 20-block fall deals exactly vanilla's
 * {@code 20 - 3 = 17} damage.
 */
public final class VanillaFallMath {

    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;

    /**
     * 400 ticks of freefall ≈ 1500 blocks and within a rounding error of
     * terminal velocity (3.92 blocks/tick = 78.4 m/s) — any real impact speed
     * lands inside the table.
     */
    private static final int TABLE_TICKS = 400;

    private static final double[] SPEED_MPS = new double[TABLE_TICKS];
    private static final double[] DISTANCE = new double[TABLE_TICKS];

    static {
        double v = 0.0, d = 0.0;
        for (int i = 0; i < TABLE_TICKS; i++) {
            v = (v + GRAVITY) * DRAG;
            d += v;
            SPEED_MPS[i] = v * 20.0;
            DISTANCE[i] = d;
        }
    }

    private VanillaFallMath() {
    }

    /**
     * The fall distance (blocks) a vanilla living entity dropping from rest
     * needs to hit the ground at {@code impactSpeedMps}. Monotonic in the input;
     * speeds at/above terminal velocity clamp to the table's last entry (past
     * terminal, distance is unrecoverable from speed anyway).
     */
    public static double equivalentFallDistance(double impactSpeedMps) {
        if (impactSpeedMps <= 0) return 0;
        if (impactSpeedMps >= SPEED_MPS[TABLE_TICKS - 1]) return DISTANCE[TABLE_TICKS - 1];

        int lo = 0;
        int hi = TABLE_TICKS - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (SPEED_MPS[mid] < impactSpeedMps) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }

        if (lo == 0) {
            return DISTANCE[0] * (impactSpeedMps / SPEED_MPS[0]);
        }
        double denom = SPEED_MPS[lo] - SPEED_MPS[lo - 1];
        double t = denom > 0 ? (impactSpeedMps - SPEED_MPS[lo - 1]) / denom : 0.0;
        return DISTANCE[lo - 1] + t * (DISTANCE[lo] - DISTANCE[lo - 1]);
    }
}
