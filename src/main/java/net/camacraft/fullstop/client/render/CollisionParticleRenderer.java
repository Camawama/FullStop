package net.camacraft.fullstop.client.render;

import net.camacraft.fullstop.common.data.Collision;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class CollisionParticleRenderer {

    public static void spawnParticle(Vec3 pos, Collision collision, BlockState blockState) {
        ParticleOptions particleType;

        if (collision.collisionType == Collision.CollisionType.SLIME) {
            particleType = ParticleTypes.ITEM_SLIME;
        } else if (collision.collisionType == Collision.CollisionType.HONEY) {
            particleType = ParticleTypes.FALLING_HONEY;
        } else if (collision.collisionType == Collision.CollisionType.SOLID) {
            particleType = new BlockParticleOption(ParticleTypes.BLOCK, blockState);
        } else if (collision.collisionType == Collision.CollisionType.ENTITY) {
            particleType = ParticleTypes.CLOUD;
        } else {
            throw new IllegalStateException("not a sticky type");
        }

        Minecraft.getInstance().level.addParticle(
                particleType,
                pos.x, pos.y, pos.z,
                0.1, 0.1, 0.1
        );
    }
}
