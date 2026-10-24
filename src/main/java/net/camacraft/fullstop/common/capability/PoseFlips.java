package net.camacraft.fullstop.common.capability;

/**
 * Duck interface added to {@link net.minecraft.world.entity.Entity} by EntityPoseFlipsMixin: how many times the
 * entity's pose has been set to a new value, on this side. A pose is synched data, read through a lock; the count is a
 * plain field, so the per-tick checks that only care whether the pose changed (getting into or out of a bed, a carried
 * body crouching) read the pose itself only on the ticks it did (2026-10-23).
 */
public interface PoseFlips {

    int fullstop$poseFlips();
}
