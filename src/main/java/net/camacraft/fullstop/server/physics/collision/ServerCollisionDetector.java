package net.camacraft.fullstop.server.physics.collision;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector;
import net.minecraft.world.entity.Entity;

/**
 * What an entity ran into this tick on the server: the blocks {@link CommonCollisionDetector} finds. Bodies meeting
 * each other are a pairwise pass of their own ({@code BodyCollisions}, once a level tick), not part of an entity's
 * own impact; the old per-entity entity collisions ({@code entityCollisionDamage}) are gone.
 */
public class ServerCollisionDetector {

    public static Collision detect(Entity entity, FullStopCapability fullstop) {
        return CommonCollisionDetector.detectBlocks(entity, fullstop);
    }
}
