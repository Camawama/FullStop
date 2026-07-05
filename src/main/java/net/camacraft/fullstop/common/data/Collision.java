package net.camacraft.fullstop.common.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/** What an entity ran into this tick. Immutable; lists are never null. */
public class Collision {
    public static final Collision NONE = new Collision(
            CollisionType.NONE,
            List.of(),
            List.of(),
            List.of(),
            List.of()
    );

    public final CollisionType collisionType;
    public final List<BlockState> blockStates;
    public final List<Entity> collidingEntities;
    public final List<BlockPos> impactedPositions;
    public final List<BlockHitResult> impactedHits;

    public enum CollisionType {
        NONE(0), SOLID(1), WATER(2), ENTITY(3), SLIME(4), HONEY(5), BED(6);

        /** Higher priority wins when several block types are hit in one tick. */
        public final int priority;

        CollisionType(int priority) {
            this.priority = priority;
        }
    }

    public Collision(CollisionType collisionType,
                     List<BlockState> blockStates,
                     List<Entity> collidingEntities,
                     List<BlockPos> impactedPositions,
                     List<BlockHitResult> impactedHits) {
        this.collisionType = collisionType;
        this.blockStates = blockStates;
        this.collidingEntities = collidingEntities;
        this.impactedPositions = impactedPositions;
        this.impactedHits = impactedHits;
    }

    public boolean fake() {
        return collisionType == CollisionType.NONE;
    }

    public boolean bouncy() {
        return collisionType == CollisionType.SLIME || collisionType == CollisionType.BED;
    }
}
