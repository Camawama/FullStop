package net.camacraft.fullstop.client.render;

import net.camacraft.fullstop.common.data.Collision;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

public class ParticleRenderer {

//    public static void spawnParticle(ParticleOptions particleType, Vec3 pos, Vec3 vel) {
//
//        Minecraft.getInstance().level.addParticle(
//                particleType,
//                pos.x, pos.y, pos.z,
//                vel.x, vel.y, vel.z
//
//
//        );
//    }
    public static void spawnParticle(Vec3 pos, Collision collision) {
        ParticleOptions particleType;

        if (collision.collisionType == Collision.CollisionType.SLIME) {
            particleType = ParticleTypes.ITEM_SLIME;
        } else if (collision.collisionType == Collision.CollisionType.HONEY) {
            particleType = ParticleTypes.FALLING_HONEY;
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
