package net.camacraft.fullstop.common.physics.math;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * When two boxes, each moving in a straight line over one tick, first touch: the slab test on the
 * box of one moving relative to the other (continuous collision detection). Checking only where
 * two fast things end up each tick misses them passing through each other in between: two arrows
 * closing at 120 m/s cross six blocks a tick.
 */
public final class SweptBoxes {

    private SweptBoxes() {
    }

    /**
     * The first touch of the two boxes during the tick.
     *
     * @param t           when, as a share of the tick (0 = its start)
     * @param axis        the axis whose faces met last - the one they touched across (null when they
     *                    overlapped from the start on every axis without moving apart)
     * @param overlapping they already overlapped at the start of the tick ({@code t} is then 0)
     */
    public record Touch(double t, @Nullable Direction.Axis axis, boolean overlapping) {
    }

    /**
     * When box {@code a} (at {@code a0} at the start of the tick, moving by {@code da} blocks) and
     * box {@code b} (at {@code b0}, moving by {@code db}) first touch during the tick, or null when
     * they don't.
     */
    @Nullable
    public static Touch first(AABB a0, Vec3 da, AABB b0, Vec3 db) {
        Vec3 d = da.subtract(db); // a's motion as b sees it
        double enter = Double.NEGATIVE_INFINITY;
        double exit = Double.POSITIVE_INFINITY;
        Direction.Axis axis = null;
        for (Direction.Axis ax : Direction.Axis.values()) {
            double aMin = a0.min(ax), aMax = a0.max(ax);
            double bMin = b0.min(ax), bMax = b0.max(ax);
            double v = d.get(ax);
            if (Math.abs(v) < 1.0e-12) {
                // not moving apart on this axis: they overlap on it for the whole tick, or never
                if (aMax <= bMin || aMin >= bMax) return null;
                continue;
            }
            // a's interval overlaps b's while aMin + v t < bMax and aMax + v t > bMin
            double t1 = (bMin - aMax) / v;
            double t2 = (bMax - aMin) / v;
            double in = Math.min(t1, t2);
            double out = Math.max(t1, t2);
            if (in > enter) {
                enter = in;
                axis = ax;
            }
            if (out < exit) exit = out;
        }
        if (enter >= exit || exit <= 0.0 || enter > 1.0) return null;
        if (enter <= 0.0) return new Touch(0.0, axis, true);
        return new Touch(enter, axis, false);
    }
}
