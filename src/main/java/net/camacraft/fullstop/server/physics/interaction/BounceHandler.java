package net.camacraft.fullstop.server.physics.interaction;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.BounceMath;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side bounce for springy/sticky surfaces. Applies velocity and syncs it
 * (hurtMarked); the client never touches remote entities' motion — camera-only
 * reactions live in {@code client.physics.CameraBounceHandler}.
 *
 * Plain solid blocks are deliberately NOT handled here: the raycast collision
 * fires one tick before physical contact, so changing velocity for solids
 * pre-empted vanilla collision — the entity never set its collision flags and
 * never showed a measured stopping force, which silently disabled kinetic
 * wall/ceiling damage.
 */
public final class BounceHandler {
    private BounceHandler() {
    }

    public static void apply(Entity entity, FullStopCapability fullstop, Collision collision, boolean hasBrokenBlock) {
        if (hasBrokenBlock) return;

        if (entity.isCrouching() || collision.fake()) return;

        if (collision.collisionType == Collision.CollisionType.WATER) {
            handleWaterSkip(entity, fullstop, collision);
            return;
        }

        if (!collision.bouncy() && collision.collisionType != Collision.CollisionType.HONEY) return;
        if (entity instanceof Minecart) return;

        Vec3 preV = fullstop.getPreviousScaledVelocity();
        Vec3 normal = firstHitNormal(collision);
        if (normal == null) return;

        // Rubbing along a surface is not an impact: without real speed INTO the
        // surface, walking through a slime tunnel bounced the player around.
        if (-preV.dot(normal) < BounceMath.MIN_IMPACT_SPEED_MPS) return;

        Vec3 newV = BounceMath.bounceVelocity(preV, normal, collision.collisionType);
        if (newV == null) return;

        entity.setDeltaMovement(newV.scale(0.05));
        entity.hurtMarked = true;

        if (collision.bouncy()) {
            fullstop.setJustBounced(true);
        }

        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            mob.getBrain().eraseMemory(MemoryModuleType.PATH);
            mob.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
            mob.setSprinting(false);
        }
    }

    static Vec3 firstHitNormal(Collision collision) {
        if (collision.impactedHits.isEmpty()) return null;
        BlockHitResult hit = collision.impactedHits.get(0);
        return Vec3.atLowerCornerOf(hit.getDirection().getNormal());
    }

    private static void handleWaterSkip(Entity entity, FullStopCapability fullstop, Collision collision) {
        Vec3 preV = fullstop.getPreviousScaledVelocity();
        if (preV.lengthSqr() < 10 * 10) return; // Need a minimum speed (10 m/s) to skip

        Vec3 normal = firstHitNormal(collision);
        if (normal == null) return;

        // Angle of incidence: 90° is a direct hit, 0° is parallel to the surface.
        double angle = Math.toDegrees(Math.acos(preV.normalize().dot(normal.scale(-1))));
        if (angle > 25) return; // Only skip at shallow angles

        double vDotN = preV.dot(normal);
        if (vDotN >= 0) return;

        double restitution = -0.6; // Energy returned vertically
        double friction = 0.9;     // Energy kept horizontally

        Vec3 vNormal = normal.scale(vDotN);
        Vec3 vTangential = preV.subtract(vNormal);

        Vec3 newV = vTangential.scale(friction).add(vNormal.scale(restitution));

        entity.setDeltaMovement(newV.scale(0.05));
        entity.hurtMarked = true;
        fullstop.setWaterSkipCooldown(10); // 10 ticks of water immunity
        fullstop.setJustBounced(true);
    }
}
