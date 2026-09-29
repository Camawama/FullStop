package net.camacraft.fullstop.common.physics.rules;

/**
 * Dizziness from spinning, as two numbers per player: the smoothed turning RATE (degrees per
 * tick, averaged over about a second) and the DOSE built up from it. Only turning faster than
 * the threshold ({@code spinNauseaThreshold}) builds dose - one degree per degree turned beyond
 * that rate - and anything slower drains it at the same rate (standing still drains the
 * threshold's worth every tick). Nausea sets in once the dose reaches {@code spinNauseaTurns}
 * full turns (360 degrees each), and lifts once it drops back under.
 *
 * <p>So it takes SUSTAINED spinning: a quick 180 or a flick of the mouse never lifts the
 * one-second average far past the threshold, and whatever dose it leaves drains within a second
 * or two; spinning round and round keeps the average up and the dose climbing.
 */
public final class SpinDizziness {

    /** Ticks the turning rate is averaged over. */
    public static final double RATE_WINDOW_TICKS = 20.0;
    /** One tick's turn counts at most this much (a frame-convention yaw jump must not register). */
    public static final double INPUT_CAP_DEG = 60.0;
    /** Turning slower than this per tick is noise (hand tremor, VS nudging the yaw). */
    public static final double NOISE_FLOOR_DEG = 1.5;
    /** Dose stored at most: this many turns beyond the nausea point, so recovery never takes long. */
    public static final double DOSE_BUFFER_TURNS = 1.0;

    private SpinDizziness() {
    }

    /** One tick's turning input (degrees), capped and with the noise floor applied. */
    public static double input(double degreesThisTick) {
        double spin = Math.min(Math.abs(degreesThisTick), INPUT_CAP_DEG);
        return spin < NOISE_FLOOR_DEG || !Double.isFinite(spin) ? 0.0 : spin;
    }

    /** The smoothed rate after one tick of {@code input} degrees. */
    public static double rate(double rate, double input) {
        double next = rate + (input - rate) / RATE_WINDOW_TICKS;
        return Double.isFinite(next) ? next : 0.0;
    }

    /**
     * The dose after one tick at the smoothed {@code rate}: up by what the rate exceeds the
     * threshold, down by what it falls short, never below 0 nor above the nausea point plus
     * {@link #DOSE_BUFFER_TURNS}. A threshold of 0 (spin nausea off) keeps it at 0.
     */
    public static double dose(double dose, double rate, double thresholdDegPerTick, double turns) {
        if (!(thresholdDegPerTick > 0.0)) return 0.0;
        double max = (Math.max(0.0, turns) + DOSE_BUFFER_TURNS) * 360.0;
        double next = dose + (rate - thresholdDegPerTick);
        if (!Double.isFinite(next)) return 0.0;
        return Math.max(0.0, Math.min(max, next));
    }

    /**
     * Whether that dose means nausea: at least {@code turns} full turns. With {@code turns} 0
     * any time spent turning faster than the threshold counts.
     */
    public static boolean dizzy(double dose, double rate, double thresholdDegPerTick, double turns) {
        if (!(thresholdDegPerTick > 0.0)) return false;
        if (turns <= 0.0) return rate >= thresholdDegPerTick;
        return dose >= turns * 360.0;
    }
}
