package net.camacraft.fullstop.common.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Collision {
    public static final Collision NONE = new Collision(
            CollisionType.NONE,
            0.0,
            0.0,
            new ArrayList<>(),
            Collections.emptyList(),
            new ArrayList<>(),
            new ArrayList<>()
    );

    public final CollisionType collisionType;
    public final double highestYLevel;
    public final double lowestYLevel;
    public final ArrayList<BlockState> blockStates;
    public final List<Entity> collidingEntities;

    public final List<BlockPos> impactedPositions;
    public final List<BlockHitResult> impactedHits;

    public enum CollisionType {
        // do not reorder, ordinal is used for priority.
        NONE, SOLID, ENTITY, SLIME, HONEY, BED,
    }

    public Collision(CollisionType collisionType,
                     double highestYLevel,
                     double lowestYLevel,
                     ArrayList<BlockState> blockStates,
                     List<Entity> collidingEntities,
                     List<BlockPos> impactedPositions,
                     List<BlockHitResult> impactedHits) {
        this.highestYLevel = highestYLevel;
        this.lowestYLevel = lowestYLevel;
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
        return collisionType == CollisionType.SLIME || collisionType == CollisionType.HONEY || collisionType == CollisionType.BED;
    }
}
