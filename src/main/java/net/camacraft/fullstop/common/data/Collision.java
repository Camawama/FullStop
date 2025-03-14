package net.camacraft.fullstop.common.data;

public class Collision {
    public static final Collision NONE = new Collision(CollisionType.NONE, 0.0, 0.0);

    public enum CollisionType {
        // do not reorder, ordinal is used for priority.
        NONE, SLIME, HONEY, SOLID,
    }

    public final CollisionType collisionType;
    public final double highestYLevel;
    public final double lowestYLevel;
    double damage = 0;

    public Collision(CollisionType collisionType, double highestYLevel, double lowestYLevel) {
        this.highestYLevel = highestYLevel;
        this.lowestYLevel = lowestYLevel;
        this.collisionType = collisionType;
    }

    public boolean fake() {
        return collisionType == CollisionType.NONE;
    }
    public boolean sticky() {
        return collisionType == CollisionType.SLIME || collisionType == CollisionType.HONEY;
    }





    //TODO All of the information useful to pass around for the collision related functions like spawn particles, colliding kinetically, bouncing, etc
}
