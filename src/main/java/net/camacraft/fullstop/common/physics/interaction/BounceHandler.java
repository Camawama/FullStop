package net.camacraft.fullstop.common.physics.interaction;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class BounceHandler {
    private BounceHandler() {
    }

    public static void apply(Entity entity, FullStopCapability fullstop, Collision collision, boolean hasBrokenBlock) {
        if (hasBrokenBlock) return;

        if (entity.isCrouching() || collision.fake()) return;

        // If not bouncy and low speed, ignore.
        if (!collision.bouncy() && fullstop.getPreviousScaledVelocity().length() < 9) return;

        if (collision.collisionType == Collision.CollisionType.ENTITY) return;

        if (collision.blockStates != null) {
            for (BlockState state : collision.blockStates) {
                Block block = state.getBlock();
                if (block instanceof DoorBlock ||
                        block instanceof TrapDoorBlock ||
                        block instanceof FenceGateBlock) {
                    return;
                }
            }
        }

        Vec3 preV = fullstop.getPreviousScaledVelocity();
        if (preV.lengthSqr() < 0.0001) return;

        double restitution;
        double friction;

        Collision.CollisionType type = collision.collisionType;
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

        Vec3 averageNormal = Vec3.ZERO;
        int count = 0;

        // OLD CODE
//        if (collision.impactedHits != null) {
//            for (BlockHitResult hit : collision.impactedHits) {
//                averageNormal = averageNormal.add(Vec3.atLowerCornerOf(hit.getDirection().getNormal()));
//                count++;
//            }
//        }

        if (collision.impactedHits != null && !collision.impactedHits.isEmpty()) {
            BlockHitResult hit = collision.impactedHits.get(0);
            averageNormal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
            count = 1;
        }

        if (count == 0) return;

        averageNormal = averageNormal.normalize();

        double vDotN = preV.dot(averageNormal);
        if (vDotN >= 0) return;

        // If falling mostly downward and hitting solid ground (not bouncy), skip bounce logic
        // to prevent velocity cancellation which might interfere with fall damage.
        if (fullstop.isMostlyDownward() && !collision.bouncy()) {
            return;
        }

        Vec3 vNormal = averageNormal.scale(vDotN);
        Vec3 vTangential = preV.subtract(vNormal);

        Vec3 newV = vTangential.scale(friction).add(vNormal.scale(restitution));

        if (entity instanceof Minecart) return;

        entity.setDeltaMovement(newV.scale(0.05));
        entity.hurtMarked = true;

        if (collision.bouncy()) {
            fullstop.setJustBounced(true);
        }

        if (!entity.level().isClientSide && entity instanceof Mob mob) {
            // Stop any currently running path
            mob.getNavigation().stop();

            // Clear common brain targets (safe even if unused)
            mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            mob.getBrain().eraseMemory(MemoryModuleType.PATH);
            mob.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);

            // Optional: helps avoid the visual "burst sprint" frame
            mob.setSprinting(false);
        }

        // Camera rotation is client-side only
        if (entity.level().isClientSide) {
            if (!FullStopConfig.CLIENT.rotateCamera.get()) return;
        } else {
            // On server side, we don't care about camera rotation, but we might want to skip this block
            // if we were relying on the config to disable bouncing entirely?
            // The original code was: if (!SERVER.rotateCamera.get()) return;
            // This implies that if camera rotation was disabled, the bounce logic (setting target angle/pitch)
            // was skipped. Since target angle/pitch are stored in the capability and synced/used by client,
            // we should probably check the client config if we are on client, or just set it anyway?
            // Actually, the capability fields targetAngle/targetPitch are likely only used for rendering/camera.
            // So we can just skip this block if we are on server.
            return;
        }

        if (newV.length() < 3.0) return;
        if (entity instanceof Player && ((Player) entity).getAbilities().flying) return;

        if (collision.bouncy()) {
            double newAngle = Math.atan2(-newV.x, newV.z);
            double targetAngle = newAngle / Math.PI * 180;
            fullstop.setTargetAngle(targetAngle);

            double horizontalDistance = Math.sqrt(newV.x * newV.x + newV.z * newV.z);
            double targetPitch = -Math.toDegrees(Math.atan2(newV.y, horizontalDistance));

            boolean isElytraFlying = entity instanceof LivingEntity && ((LivingEntity) entity).isFallFlying();
            if (!fullstop.isMostlyDownward() || isElytraFlying) {
                fullstop.setTargetPitch(targetPitch);
            }
//            fullstop.setTargetPitch(targetPitch);
        }
    }
}
