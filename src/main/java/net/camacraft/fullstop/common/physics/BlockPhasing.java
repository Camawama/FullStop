package net.camacraft.fullstop.common.physics;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Drag for entities phasing through fullstop:phaseable blocks (the collision
 * bypass itself lives in PhaseableBlockMixin). Engulfing blocks (sand, gravel,
 * snow) bleed speed off hard, so a diver is swallowed within a few blocks and —
 * once below phase speed — embedded, left to dig out. Other phaseables (leaves)
 * only brush speed off so elytra flight can carry through a canopy.
 *
 * Runs on the server and on the client's LOCAL player, whose motion is
 * client-authoritative — server-only drag would rubber-band.
 */
public final class BlockPhasing {

    private static final double ENGULFING_DRAG = 0.5;
    private static final double PHASEABLE_DRAG = 0.9;

    /** Per-tick horizontal drag while brushing a fullstop:slowing block (honey, soul sand family). */
    private static final double SLOWING_FIELD_DRAG = 0.8;

    private BlockPhasing() {
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        Level level = entity.level();

        boolean hasAuthority = !level.isClientSide
                || (entity instanceof Player player && player.isLocalPlayer());
        if (!hasAuthority) return;
        if (!FullStopConfig.SERVER_SPEC.isLoaded()) return;

        Vec3 velocity = entity.getDeltaMovement();

        // Viscous field (honey / soul sand walls) works at ANY speed — walking
        // beside them is exactly the case — so it runs before the phase gate.
        applySlowingField(entity, velocity);
        velocity = entity.getDeltaMovement();

        // Drag only applies once the entity is moving fast enough to actually
        // phase INTO the block — the same threshold PhaseableBlockMixin uses to
        // turn the block passable. Below it the block is solid and simply walked
        // or rolled on, so a slow mover whose hitbox merely clips the sand it
        // stands on (e.g. a dodge-roll across a dune) is no longer yanked to a
        // halt every tick, which the server rejected as a rubber-band.
        double thresholdNative = FullStopConfig.SERVER.phaseMinimumSpeed.get() * 0.05;
        if (phaseSpeedSqr(entity) < thresholdNative * thresholdNative) return;

        AABB box = entity.getBoundingBox().deflate(0.01);
        BlockState engulfingState = null;
        BlockPos engulfingPos = null;
        boolean phasing = false;

        for (BlockPos pos : BlockPos.betweenClosed(
                Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
            BlockState state = level.getBlockState(pos);
            if (state.is(FullStopTags.ENGULFING)) {
                engulfingState = state;
                engulfingPos = pos.immutable();
                break;
            }
            if (state.is(FullStopTags.PHASEABLE)) {
                phasing = true;
            }
        }

        if (engulfingState != null) {
            entity.setDeltaMovement(velocity.scale(ENGULFING_DRAG));
            burrowEffects(entity, engulfingState, engulfingPos);
        } else if (phasing) {
            entity.setDeltaMovement(velocity.scale(PHASEABLE_DRAG));
        }
    }

    /**
     * Honey and the soul sand family cling to anything moving BESIDE them: a
     * steady horizontal drag, matching Minecraft's own soul-sand-slows-you
     * style. Deliberately continuous and silent — the old behavior for honey
     * emerged from the collision pipeline, which replayed the impact sound and
     * particles every time it re-slowed the runner. Blocks at or below foot
     * level are ignored: standing ON soul sand is the block's own speed factor,
     * not this field.
     */
    private static void applySlowingField(LivingEntity entity, Vec3 velocity) {
        if (velocity.horizontalDistanceSqr() < 1.0e-6) return;

        Level level = entity.level();
        AABB box = entity.getBoundingBox().inflate(0.1, 0.0, 0.1);
        for (BlockPos pos : BlockPos.betweenClosed(
                Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
            // Wall blocks only: skip anything whose top sits at/below foot level.
            if (pos.getY() + 1.0 <= box.minY + 0.5) continue;
            if (level.getBlockState(pos).is(FullStopTags.SLOWING)) {
                entity.setDeltaMovement(velocity.multiply(SLOWING_FIELD_DRAG, 1.0, SLOWING_FIELD_DRAG));
                return;
            }
        }
    }

    /**
     * THE phase-speed check — shared with PhaseableBlockMixin so passability and
     * drag can never drift apart (they used to be verbatim copies). A server-side
     * player's {@code getDeltaMovement} is never written back from move packets,
     * so its capability velocity is the real speed there.
     */
    public static double phaseSpeedSqr(LivingEntity living) {
        if (living instanceof Player && !living.level().isClientSide) {
            FullStopCapability cap = FullStopCapability.grabCapability(living);
            if (cap != null) {
                return Math.max(cap.getCurrentNativeVelocity().lengthSqr(),
                        living.getDeltaMovement().lengthSqr());
            }
        }
        return living.getDeltaMovement().lengthSqr();
    }

    private static void burrowEffects(LivingEntity entity, BlockState state, BlockPos pos) {
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;

        serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                entity.getX(), entity.getY(entity.isVisuallySwimming() ? 0.5 : 0.8), entity.getZ(),
                12,
                entity.getBbWidth() * 0.5, entity.getBbHeight() * 0.4, entity.getBbWidth() * 0.5,
                0.05);

        if (serverLevel.random.nextFloat() < 0.4f) {
            serverLevel.playSound(null, pos, state.getSoundType().getHitSound(), SoundSource.BLOCKS,
                    0.8f, 0.8f + serverLevel.random.nextFloat() * 0.4f);
        }
    }
}
