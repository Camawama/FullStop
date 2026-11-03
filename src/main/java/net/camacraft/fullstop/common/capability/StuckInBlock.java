package net.camacraft.fullstop.common.capability;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Duck interface added to {@link net.minecraft.world.entity.Entity} by EntityStuckInBlockMixin: when a block last held the
 * entity through vanilla's {@code Entity.makeStuckInBlock} (powder snow, cobwebs, sweet berry bushes, and any mod's block
 * that calls it), by the entity's own tick count, and which block it was. Read by
 * {@link net.camacraft.fullstop.common.physics.rules.StuckBlocks}: the hold is a cushion, not an impact (2026-10-31).
 */
public interface StuckInBlock {

    /** The entity's {@code tickCount} when a block last held it, or {@link Integer#MIN_VALUE} if none ever did on this side. */
    int fullstop$stuckTick();

    /** The block that last held it, or null. */
    BlockState fullstop$stuckState();
}
