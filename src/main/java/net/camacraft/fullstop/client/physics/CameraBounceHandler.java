package net.camacraft.fullstop.client.physics;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.BounceMath;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
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

        Vec3 preV = fullstop.getPreviousScaledVelocity();
        if (preV.lengthSqr() < 0.0001) return;

        if (collision.impactedHits.isEmpty()) return;
        BlockHitResult hit = collision.impactedHits.get(0);
        Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());

        Vec3 newV = BounceMath.bounceVelocity(preV, normal, collision.collisionType);
        if (newV == null || newV.length() < 3.0) return;

        fullstop.setJustBounced(true);

        double newAngle = Math.atan2(-newV.x, newV.z);
        fullstop.setTargetAngle(newAngle / Math.PI * 180);

        double horizontalDistance = Math.sqrt(newV.x * newV.x + newV.z * newV.z);
        double targetPitch = -Math.toDegrees(Math.atan2(newV.y, horizontalDistance));

        boolean isElytraFlying = player.isFallFlying();
        if (!fullstop.isMostlyDownward() || isElytraFlying) {
            fullstop.setTargetPitch(targetPitch);
        }
    }
}
