package net.camacraft.fullstop.server.physics.interaction;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.physics.math.BounceMath;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
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

    /** Refractory period between bounces; breaks corner-seam ping-pong loops. */
    private static final int BOUNCE_COOLDOWN_TICKS = 6;

    private BounceHandler() {
    }

    /** SLIME/HONEY when the entity is a falling sticky block, else null. */
    private static Collision.CollisionType stickyCarrierType(Entity entity) {
        if (entity instanceof FallingBlockEntity fallingBlock) {
            BlockState state = fallingBlock.getBlockState();
            // instanceof (not a Blocks reference) so modded slime blocks bounce too,
            // matching CommonCollisionDetector's classification.
            if (state.getBlock() instanceof SlimeBlock) return Collision.CollisionType.SLIME;
            if (state.isStickyBlock()) return Collision.CollisionType.HONEY;
        }
        return null;
    }

    public static void apply(Entity entity, FullStopCapability fullstop, Collision collision, boolean hasBrokenBlock) {
        if (hasBrokenBlock) return;

        // A passenger's motion is slaved to its vehicle: the vehicle takes the
        // bounce (and syncs it to its driver below); bouncing the rider only
        // wrote motion vanilla ignores and burned the rider's bounce cooldown.
        if (entity.isPassenger()) return;

        if (entity.isCrouching() || collision.fake()) return;

        // Deliberately before the Minecart early-out: minecarts skipping across
        // water like thrown stones is intended behavior.
        if (collision.collisionType == Collision.CollisionType.WATER) {
            handleWaterSkip(entity, fullstop, collision);
            return;
        }

        // A sticky falling block (slime/honey) bounces off ANY surface it hits.
        Collision.CollisionType carrierType = stickyCarrierType(entity);

        if (!collision.bouncy() && collision.collisionType != Collision.CollisionType.HONEY
                && carrierType == null) return;
        if (entity instanceof Minecart) return;

        if (!fullstop.canBounce()) return;

        Vec3 preV = fullstop.getPreviousScaledVelocity();
        Vec3 normal = BounceMath.mostOpposedNormal(collision.impactedHits, preV);
        if (normal == null) return;

        // Rubbing along a surface is not an impact: without real, mostly-direct
        // speed INTO the surface, walking through a slime tunnel (or diagonally
        // beside a slime/honey wall) bounced or dead-stopped the player around.
        if (!BounceMath.isDirectImpact(preV, normal)) return;

        // Already rebounding (e.g. last tick's bounce): re-applying a bounce from
        // the stale pre-impact velocity would pin the entity to the wall.
        if (fullstop.getCurrentScaledVelocity().dot(normal) > 0.5) return;

        Collision.CollisionType bounceType = carrierType != null ? carrierType : collision.collisionType;
        Vec3 newV = BounceMath.bounceVelocity(preV, normal, bounceType);
        if (newV == null) return;

        if (carrierType != null) {
            newV = newV.scale(0.8); // carriers lose energy so they eventually settle and place
        }

        entity.setDeltaMovement(newV.scale(0.05));
        entity.hurtMarked = true;
        PacketHandler.syncMotionToControllingDriver(entity);
        fullstop.setBounceCooldown(BOUNCE_COOLDOWN_TICKS);

        if (collision.bouncy() || carrierType == Collision.CollisionType.SLIME) {
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

    private static void handleWaterSkip(Entity entity, FullStopCapability fullstop, Collision collision) {
        Vec3 preV = fullstop.getPreviousScaledVelocity();
        // Minimum speed to skip. Minecarts get a lower bar: their vanilla top
        // speed is 8 m/s, so the general 10 m/s floor made cart skipping
        // (explicitly intended, see the caller) unreachable in practice.
        double minSkipSpeed = entity instanceof Minecart ? 6.0 : 10.0;
        if (preV.lengthSqr() < minSkipSpeed * minSkipSpeed) return;

        if (collision.impactedHits.isEmpty()) return;
        // Water surfaces are only ever detected on their UP face, but a mixed
        // shore collision (WATER outranks SOLID in type priority) can put a wall
        // hit first in the list — the skip must always reflect off the surface.
        Vec3 normal = new Vec3(0, 1, 0);

        // Water normals are always UP, so this angle is measured from VERTICAL:
        // 0° is a straight-down dive, 90° is skimming parallel to the surface.
        // Skip only shallow grazes (within 25° of the surface); steep entries
        // plunge. The old `angle > 25` return had this exactly inverted — divers
        // trampolined off the surface and skimmers plunged.
        double angle = Math.toDegrees(Math.acos(preV.normalize().dot(normal.scale(-1))));
        if (angle < 65) return;

        double vDotN = preV.dot(normal);
        if (vDotN >= 0) return;

        double restitution = -0.6; // Energy returned vertically
        double friction = 0.9;     // Energy kept horizontally

        Vec3 vNormal = normal.scale(vDotN);
        Vec3 vTangential = preV.subtract(vNormal);

        Vec3 newV = vTangential.scale(friction).add(vNormal.scale(restitution));

        entity.setDeltaMovement(newV.scale(0.05));
        entity.hurtMarked = true;
        PacketHandler.syncMotionToControllingDriver(entity);
        fullstop.setWaterSkipCooldown(10); // 10 ticks of water immunity
        fullstop.setJustBounced(true);
    }
}
