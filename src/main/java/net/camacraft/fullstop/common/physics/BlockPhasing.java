package net.camacraft.fullstop.common.physics;

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
    /** Below this speed (4 m/s in blocks/tick, squared) no drag applies — idle/walking entities skip the block scan. */
    private static final double MIN_DRAG_SPEED_SQR = 0.2 * 0.2;

    private BlockPhasing() {
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        Level level = entity.level();

        boolean hasAuthority = !level.isClientSide
                || (entity instanceof Player player && player.isLocalPlayer());
        if (!hasAuthority) return;

        Vec3 velocity = entity.getDeltaMovement();
        if (velocity.lengthSqr() < MIN_DRAG_SPEED_SQR) return;

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
