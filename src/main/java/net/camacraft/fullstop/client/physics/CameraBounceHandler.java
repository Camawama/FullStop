package net.camacraft.fullstop.client.physics;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.BounceMath;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side camera reaction to bounces for the LOCAL player only. Computes the
 * same bounce vector as the server (via BounceMath) but only aims the camera —
 * it never modifies velocity; the server owns motion.
 */
public final class CameraBounceHandler {
    private CameraBounceHandler() {
    }

    public static void apply(Player player, FullStopCapability fullstop, Collision collision) {
        if (player.isCrouching() || collision.fake()) return;
        if (!collision.bouncy()) return;
        if (player.getAbilities().flying) return;

        // Passengers keep their head still: the VEHICLE takes the bounce, and a
        // rider's own rays grazing the surface must not yank their view around.
        if (player.isPassenger()) return;

        // Pre-impact velocity, matching the server BounceHandler.
        Vec3 preV = fullstop.getPreImpactScaledVelocity();
        if (preV.lengthSqr() < 0.0001) return;

        // Most-opposed face, not first-listed: corner impacts record several
        // faces in ray order, and aiming off the arbitrary first one rotated the
        // camera in the wrong direction. Must match the server's bounce normal.
        Vec3 normal = BounceMath.mostOpposedNormal(collision.impactedHits, preV);
        if (normal == null) return;

        // Same rubbing-vs-impact and refractory gates as the server BounceHandler:
        // brushing a slime wall while walking must not swing the camera, and a
        // corner-seam ping-pong must not re-aim it every tick.
        if (!fullstop.canBounce()) return;
        if (!BounceMath.isDirectImpact(preV, normal)) return;
        if (fullstop.getCurrentScaledVelocity().dot(normal) > 0.5) return;

        Vec3 newV = BounceMath.bounceVelocity(preV, normal, collision.collisionType);
        if (newV == null || newV.length() < 3.0) return;

        fullstop.setBounceCooldown(1); // matches BounceHandler's reduced refractory
        fullstop.setJustBounced(true);

        double newAngle = Math.atan2(-newV.x, newV.z);
        fullstop.setTargetAngle(newAngle / Math.PI * 180);

        // Pitch is elytra-only: mid-flight a bounce genuinely redirects the
        // flyer, so the camera follows the new arc — but on foot, jumping into
        // a slime wall and having the view yanked up/down felt wrong. Yaw
        // (above) still tracks the rebound for everyone.
        if (player.isFallFlying()) {
            double horizontalDistance = Math.sqrt(newV.x * newV.x + newV.z * newV.z);
            double targetPitch = -Math.toDegrees(Math.atan2(newV.y, horizontalDistance));
            fullstop.setTargetPitch(targetPitch);
        }
    }
}
