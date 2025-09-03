package net.camacraft.fullstop.common.data;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class Collision {
    public static final Collision NONE = new Collision(CollisionType.NONE, 0.0, 0.0, null, null); //todo add a field for the blockType, use for particles and sounds
    public final List<Entity> collidingEntities;

    public enum CollisionType {
        // do not reorder, ordinal is used for priority.
        NONE, SLIME, HONEY, SOLID, ENTITY,
    }

    public final CollisionType collisionType;
    public final double highestYLevel;
    public final double lowestYLevel;
    public final ArrayList<BlockState> blockStates;
    double damage = 0;

    public Collision(CollisionType collisionType, double highestYLevel, double lowestYLevel, ArrayList<BlockState> blockStates, List<Entity> collidingEntities) {
        this.highestYLevel = highestYLevel;
        this.lowestYLevel = lowestYLevel;
        this.collisionType = collisionType;
        this.blockStates = blockStates;
        this.collidingEntities = collidingEntities;
    }

    public boolean fake() {
        return collisionType == CollisionType.NONE;
    }
    public boolean sticky() {
        return collisionType == CollisionType.SLIME || collisionType == CollisionType.HONEY;
    }





    //TODO All of the information useful to pass around for the collision related functions like spawn particles, colliding kinetically, bouncing, etc
}
