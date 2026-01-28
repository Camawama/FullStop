package net.camacraft.fullstop.common.physics.interaction;

import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.Physics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

public final class BounceHandler {
    private BounceHandler() {
    }

    public static void apply(Physics physics) {
        if (physics.hasBrokenBlock()) return;

        Entity entity = physics.getEntity();
        Collision collision = physics.getCollision();

        if (entity.isCrouching() || collision.fake() || (!collision.bouncy() && physics.getFullstop().getPreviousScaledVelocity().length() < 9)) return;

        if (physics.getDamage() == 0 && !entity.level().isClientSide
                && (entity.hasControllingPassenger() || entity instanceof Player)) {
            return;
        }

        if (physics.getFullstop().isMostlyDownward()) return;

        Vec3 preV = physics.getFullstop().getPreviousScaledVelocity();
        Vec3 curV = physics.getFullstop().getCurrentScaledVelocity();
        double perpScaleFactor;
        double paraScaleFactor;

        Collision.CollisionType horizontalImpactType = collision.collisionType;

        if (horizontalImpactType == Collision.CollisionType.SLIME) {
            perpScaleFactor = -1.0;
            paraScaleFactor = 1.0;
        } else if (horizontalImpactType == Collision.CollisionType.HONEY) {
            perpScaleFactor = -0.0;
            paraScaleFactor = 0.0;
        } else if (horizontalImpactType == Collision.CollisionType.BED) {
            perpScaleFactor = -0.66;
            paraScaleFactor = 0.66;
        } else if (physics.getDamage() > 0) {
            perpScaleFactor = -0.75 / Math.sqrt(Math.max(physics.getDamage(), 1));
            paraScaleFactor = 1.0 / Math.sqrt(physics.getDamage());
        } else {
            perpScaleFactor = -0.5;
            paraScaleFactor = 0.5;
        }

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

        double aCurVX = Math.abs(curV.x);
        double aCurVZ = Math.abs(curV.z);
        double aPreVX = Math.abs(preV.x);
        double aPreVZ = Math.abs(preV.z);
        Vec3 newV = (aCurVZ == aCurVX ?
                new Vec3(
                        preV.x * (aPreVX > aPreVZ ? perpScaleFactor : paraScaleFactor),
                        curV.y,
                        preV.z * (aPreVZ > aPreVX ? perpScaleFactor : paraScaleFactor)
                )
                :
                new Vec3(
                        preV.x * (aCurVX < aCurVZ ? perpScaleFactor : paraScaleFactor),
                        curV.y,
                        preV.z * (aCurVZ < aCurVX ? perpScaleFactor : paraScaleFactor)
                )
        );

        if (entity instanceof Minecart) return;

        entity.setDeltaMovement(newV.scale(0.05));

        if (!SERVER.rotateCamera.get()) return;
        if (physics.getFullstop().getStoppingForce() < 3.0) return;
        if (entity instanceof Player && ((Player) entity).getAbilities().flying) return;

        double newAngle = Math.atan2(-newV.x, newV.z);

        double targetAngle = switch (horizontalImpactType) {
            case NONE -> 0.0;
            case SLIME -> newAngle;
            case HONEY -> Double.NaN;
            case BED -> newAngle;
            case SOLID -> Double.NaN;
            case ENTITY -> Double.NaN;
        } / Math.PI * 180;

        physics.getFullstop().setTargetAngle(targetAngle);
    }
}
