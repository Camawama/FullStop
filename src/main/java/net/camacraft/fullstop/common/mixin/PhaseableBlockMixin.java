package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.physics.BlockPhasing;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
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

    @org.spongepowered.asm.mixin.Unique
    private static final org.slf4j.Logger FULLSTOP$LOGGER = com.mojang.logging.LogUtils.getLogger();

    @org.spongepowered.asm.mixin.Unique
    private static long fullstop$lastPhaseLog = Long.MIN_VALUE;

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void fullstop$phaseableCollision(BlockGetter level, BlockPos pos, CollisionContext context,
                                             CallbackInfoReturnable<VoxelShape> cir) {
        // Tag check first: this hook runs for every block shape query near every
        // entity (movement, pathfinding, our raycasts), and virtually every block
        // fails the tag — the config/capability work below must not run for stone.
        BlockBehaviour.BlockStateBase state = (BlockBehaviour.BlockStateBase) (Object) this;
        if (!state.is(FullStopTags.PHASEABLE)) return;

        if (!(context instanceof EntityCollisionContext entityContext)) return;
        if (!(entityContext.getEntity() instanceof LivingEntity living)) return;
        if (!FullStopConfig.SERVER_SPEC.isLoaded()) return;

        // Confirmed gate: instantaneous speed past the threshold AND the
        // previous tick's MEASURED speed backing it up — a one-tick spike must
        // never turn a wall passable (see BlockPhasing.phaseConfirmed).
        if (!BlockPhasing.phaseConfirmed(living)) return;

        // Diagnostic: phasing a PLAYER is a rare, deliberate event — log each
        // activation (rate-limited) so "sand behaved weirdly" reports can be
        // matched against whether phasing actually engaged, and at what speed.
        if (living instanceof net.minecraft.world.entity.player.Player) {
            long now = living.level().getGameTime();
            if (now - fullstop$lastPhaseLog >= 20) {
                fullstop$lastPhaseLog = now;
                FULLSTOP$LOGGER.info("[FullStop] phaseable open for {} ({}): inst {} m/s, measured-prev {} m/s",
                        living.getName().getString(),
                        living.level().isClientSide ? "client" : "server",
                        String.format("%.1f", Math.sqrt(BlockPhasing.phaseSpeedSqr(living)) * 20.0),
                        String.format("%.1f", Math.sqrt(
                                net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability(living)
                                        .getPreviousScaledVelocity().lengthSqr())));
            }
        }

        // The RUNNING SURFACE stays solid: phasing the very sand underfoot
        // dropped a fast runner INTO their own floor — sink, engulf-drag below
        // the threshold, sand re-solidifies around the legs, vanilla shoves
        // them back out, repeat. That jitter loop smashed the sand being run
        // on and spiked g-force into a blackout. A phaseable block whose top
        // is at or below the feet only phases for a genuine DIVE (falling
        // faster than 8 m/s); blocks at body height (dune walls, canopies you
        // fly into) phase exactly as before.
        if (pos.getY() + 1.0 <= living.getBoundingBox().minY + 0.15
                && BlockPhasing.phaseVelocityNativeY(living) > -0.4) {
            return;
        }

        cir.setReturnValue(Shapes.empty());
    }
}
