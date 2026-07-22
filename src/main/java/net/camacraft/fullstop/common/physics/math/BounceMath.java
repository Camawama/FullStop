package net.camacraft.fullstop.common.physics.math;

import net.camacraft.fullstop.common.data.Collision;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Pure bounce/deflection math shared by the server bounce handler (which applies
 * velocity) and the client camera handler (which only aims the camera).
 * All vectors are in m/s.
 */
public final class BounceMath {

    /**
     * Minimum speed INTO the surface (m/s) for a hit to count as a bounce rather
     * than rubbing along the surface. Shared by the server bounce and the client
     * camera so both always agree on what bounced.
     */
    public static final double MIN_IMPACT_SPEED_MPS = 2.5;

    /** Above this approach speed (m/s) a hit always counts, however oblique. */
    private static final double ALWAYS_IMPACT_SPEED_MPS = 8.0;

    /** Below {@link #ALWAYS_IMPACT_SPEED_MPS}, the approach must be this fraction of the total speed. */
    private static final double MIN_DIRECTNESS = 0.6;

    private BounceMath() {
    }

    /**
     * Whether a hit is a genuine impact INTO the surface rather than a brush
     * along it. The absolute floor alone wasn't enough: walking diagonally
     * beside a slime/honey wall pushes 2–3 m/s into it while mostly sliding
     * along — enough to clear the floor, so the wall bounced (or, for honey,
     * dead-stopped) the walker every few ticks and made movement stuttery.
     * A slow hit now also has to be mostly AT the wall; fast hits (≥8 m/s into
     * the surface) always count, so high-speed grazes still bounce.
     */
    public static boolean isDirectImpact(Vec3 velocityMps, Vec3 normal) {
        double approach = -velocityMps.dot(normal);
        if (approach < MIN_IMPACT_SPEED_MPS) return false;
        return approach >= ALWAYS_IMPACT_SPEED_MPS
                || approach >= velocityMps.length() * MIN_DIRECTNESS;
    }

    /**
     * Computes the post-impact velocity for a collision against a surface, or null
     * if the velocity is not actually moving into the surface.
     *
     * @param velocityMps incoming velocity in m/s
     * @param normal      unit surface normal pointing away from the surface
     */
    @Nullable
    public static Vec3 bounceVelocity(Vec3 velocityMps, Vec3 normal, Collision.CollisionType type) {
        double restitution;
        double friction;

        switch (type) {
            case SLIME -> {
                restitution = -1.0;
                friction = 1.0;
            }
            case BED -> {
                restitution = -0.66;
                friction = 0.66;
            }
            case HONEY -> {
                restitution = 0.0;
                friction = 0.0;
            }
            default -> {
                restitution = 0.0;
                friction = 0.8;
            }
        }

        double vDotN = velocityMps.dot(normal);
        if (vDotN >= 0) return null;

        Vec3 vNormal = normal.scale(vDotN);
        Vec3 vTangential = velocityMps.subtract(vNormal);

        return vTangential.scale(friction).add(vNormal.scale(restitution));
    }
}
