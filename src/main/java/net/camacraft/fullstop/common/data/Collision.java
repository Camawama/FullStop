package net.camacraft.fullstop.common.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Collision {
    // Update NONE to include an empty list for the new positions parameter
    public static final Collision NONE = new Collision(
            CollisionType.NONE,
            0.0,
            0.0,
            new ArrayList<>(),
            Collections.emptyList(),
            new ArrayList<>()
    );

    public final CollisionType collisionType;
    public final double highestYLevel;
    public final double lowestYLevel;
    public final ArrayList<BlockState> blockStates;
    public final List<Entity> collidingEntities;

    // New field to store the specific block positions hit
    public final List<BlockPos> impactedPositions;

    double damage = 0;

    public enum CollisionType {
        // do not reorder, ordinal is used for priority.
        NONE, SLIME, HONEY, SOLID, ENTITY,
    }

    // Updated constructor to accept 'impactedPositions'
    public Collision(CollisionType collisionType,
                     double highestYLevel,
                     double lowestYLevel,
                     ArrayList<BlockState> blockStates,
                     List<Entity> collidingEntities,
                     List<BlockPos> impactedPositions) {
        this.highestYLevel = highestYLevel;
        this.lowestYLevel = lowestYLevel;
        this.collisionType = collisionType;
        this.blockStates = blockStates;
        this.collidingEntities = collidingEntities;
        this.impactedPositions = impactedPositions;
    }

    public boolean fake() {
        return collisionType == CollisionType.NONE;
    }

    public boolean sticky() {
        return collisionType == CollisionType.SLIME || collisionType == CollisionType.HONEY;
    }
}