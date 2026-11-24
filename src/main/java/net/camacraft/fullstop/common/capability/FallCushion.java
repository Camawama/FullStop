package net.camacraft.fullstop.common.capability;

/**
 * Duck interface added to {@link net.minecraft.world.entity.Entity} by EntityFallCushionMixin: whether the body is on a
 * fall cushion another mod gave it (a fall distance below 0, seen at any move or tick of the fall), and how many ticks it
 * has stood on the ground since. Kept and cleared by {@link net.camacraft.fullstop.common.physics.rules.HarmlessFalls}.
 */
public interface FallCushion {

    boolean fullstop$fallCushioned();

    void fullstop$setFallCushioned(boolean cushioned);

    /** Ticks the cushioned body has stood on the ground with a fall distance of 0 (its landing done). */
    int fullstop$cushionGroundTicks();

    void fullstop$setCushionGroundTicks(int ticks);
}
