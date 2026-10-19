package net.camacraft.fullstop.common.particle;

import net.camacraft.fullstop.common.data.Collision;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class CollisionParticleSpawner {

    public static void spawnParticle(Level level, Vec3 pos, Collision collision, BlockState blockState) {
        spawnParticle(level, pos, collision.collisionType, blockState);
    }

    public static void spawnParticle(Level level, Vec3 pos, Collision.CollisionType collisionType, BlockState blockState) {
        spawnParticle(level, pos, collisionType, blockState, 1.0);
    }

    /**
     * As above, for an entity {@code size} times its normal height (Camafy's EntityScale): the burst spreads that
     * much less, and below half size the block's chips - a fixed tenth of a block each, bigger than a player shrunk
     * to 1/16 - become dust in the block's colour, sized to the entity.
     */
    public static void spawnParticle(Level level, Vec3 pos, Collision.CollisionType collisionType, BlockState blockState, double size) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        double scale = Double.isFinite(size) && size > 0.0 ? Math.min(size, 1.0) : 1.0;
        if (scale < 0.5 && blockState != null && (collisionType == Collision.CollisionType.SOLID
                || collisionType == Collision.CollisionType.BED || collisionType == Collision.CollisionType.SLIME)) {
            int colour = blockState.getMapColor(level, net.minecraft.core.BlockPos.containing(pos)).col;
            org.joml.Vector3f rgb = new org.joml.Vector3f(((colour >> 16) & 0xFF) / 255.0f, ((colour >> 8) & 0xFF) / 255.0f,
                    (colour & 0xFF) / 255.0f);
            serverLevel.sendParticles(new net.minecraft.core.particles.DustParticleOptions(rgb, (float) Math.max(0.01, scale * 1.2)),
                    pos.x, pos.y, pos.z, 6, 0.1 * scale, 0.1 * scale, 0.1 * scale, 0.1 * scale);
            return;
        }

        ParticleOptions particleType = switch (collisionType) {
            case HONEY -> ParticleTypes.FALLING_HONEY;
            case SOLID, BED, SLIME -> blockState != null ? new BlockParticleOption(ParticleTypes.BLOCK, blockState) : null;
            default -> null;
        };

        if (particleType != null) {
            // Spawn 6 particles
            // We use a small spread for position (0.1) and a speed of 0.1 to give them some motion.
            serverLevel.sendParticles(
                    particleType,
                    pos.x, pos.y, pos.z,
                    6,
                    0.1 * scale, 0.1 * scale, 0.1 * scale,
                    0.1 * scale
            );
        }
    }
}