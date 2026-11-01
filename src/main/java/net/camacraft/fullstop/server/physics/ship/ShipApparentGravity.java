package net.camacraft.fullstop.server.physics.ship;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * How much of a rider's weight its ship carries ({@code crewWeighByApparentGravity}): the gravity the rider feels on the
 * ship is the pull less the ship's own acceleration along it, so the deck carries {@code (g - a . down) / g} of the
 * rider's weight. A ship at rest, afloat or at its terminal speed carries all of it; one in free fall none (ship and crew
 * fall together and the crew press on nothing); one being slowed or pushed up carries more, up to {@link #MAX_SHARE}.
 *
 * <p>2026-10-30 (agent `space`, harness `spacespin_launch`): a 3x3 oak raft with an iron corner and a seated player,
 * falling from VS Genesis' entry at 1,440 blocks above VS's air (no drag there), took its rider's whole weight at the seat,
 * off its centre: 520 N m, 44 degrees of tilt in 11.5 s. With standing weight off 11-15 N m.
 */
final class ShipApparentGravity {
    /** The most of a rider's weight a ship carries while it is being slowed hard (three times its weight). */
    static final double MAX_SHARE = 3.0;

    private ShipApparentGravity() {
    }

    /**
     * The share of a rider's weight the ship carries: {@code acceleration} the ship's (m/s^2, world, smoothed; null while
     * unknown: all of it), {@code down} the rider's pull direction (unit), {@code pull} the pull's strength (m/s^2).
     */
    static double share(@Nullable Vec3 acceleration, Vec3 down, double pull) {
        if (acceleration == null || !(pull > 0.0)) return 1.0;
        double apparent = pull - acceleration.dot(down);
        if (!Double.isFinite(apparent)) return 1.0;
        return Mth.clamp(apparent / pull, 0.0, MAX_SHARE);
    }
}
