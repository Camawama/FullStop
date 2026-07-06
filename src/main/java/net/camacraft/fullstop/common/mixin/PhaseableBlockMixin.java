package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Living entities moving faster than the configured phaseMinimumSpeed pass
 * through blocks tagged fullstop:phaseable (leaves, sand, snow, ...): their
 * collision shape reads as empty for that entity.
 *
 * This single hook is deliberately placed on BlockStateBase so it covers BOTH
 * vanilla movement resolution AND FullStop's own collision raycasts
 * (ClipContext.Block.COLLIDER routes through this same context-aware overload),
 * keeping damage, breaking and bouncing consistently blind to phaseable blocks
 * while the entity is fast enough to phase.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class PhaseableBlockMixin {

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void fullstop$phaseableCollision(BlockGetter level, BlockPos pos, CollisionContext context,
                                             CallbackInfoReturnable<VoxelShape> cir) {
        if (!(context instanceof EntityCollisionContext entityContext)) return;
        if (!(entityContext.getEntity() instanceof LivingEntity living)) return;
        if (!FullStopConfig.SERVER_SPEC.isLoaded()) return;

        double thresholdNative = FullStopConfig.SERVER.phaseMinimumSpeed.get() * 0.05; // m/s → blocks/tick
        if (fullstop$phaseSpeedSqr(living) < thresholdNative * thresholdNative) return;

        BlockBehaviour.BlockStateBase state = (BlockBehaviour.BlockStateBase) (Object) this;
        if (!state.is(FullStopTags.PHASEABLE)) return;

        cir.setReturnValue(Shapes.empty());
    }

    /**
     * Player movement is client-authoritative and vanilla never writes the move
     * packets back into ServerPlayer#getDeltaMovement, so on the server the
     * capability's velocity (position deltas + PlayerDeltaPacket) is the truth.
     * Without this the server's move validation (noCollision) would still see
     * the block as solid and rubber-band the phasing player.
     */
    @Unique
    private static double fullstop$phaseSpeedSqr(LivingEntity living) {
        if (living instanceof Player && !living.level().isClientSide) {
            FullStopCapability cap = FullStopCapability.grabCapability(living);
            if (cap != null) {
                return Math.max(cap.getCurrentNativeVelocity().lengthSqr(),
                        living.getDeltaMovement().lengthSqr());
            }
        }
        return living.getDeltaMovement().lengthSqr();
    }
}
