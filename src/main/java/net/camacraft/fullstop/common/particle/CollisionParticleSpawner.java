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
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        ParticleOptions particleType = switch (collision.collisionType) {
            case HONEY -> ParticleTypes.FALLING_HONEY;
            case SOLID, BED, SLIME -> blockState != null ? new BlockParticleOption(ParticleTypes.BLOCK, blockState) : null;
            case ENTITY -> ParticleTypes.CLOUD;
            default -> null;
        };

        if (particleType != null) {
            // Spawn 6 particles
            // We use a small spread for position (0.1) and a speed of 0.1 to give them some motion.
            serverLevel.sendParticles(
                    particleType,
                    pos.x, pos.y, pos.z,
                    6,
                    0.1, 0.1, 0.1,
                    0.1
            );
        }
    }
}