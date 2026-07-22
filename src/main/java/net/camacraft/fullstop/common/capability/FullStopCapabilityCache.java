package net.camacraft.fullstop.common.capability;

import org.jetbrains.annotations.Nullable;

/**
 * Duck interface added to {@link net.minecraft.world.entity.Entity} by
 * EntityCapabilityCacheMixin. Forge's capability dispatcher walk is too slow to
 * run for every entity every tick, so the resolved FullStop capability is cached
 * directly on the entity and dropped if its LazyOptional is ever invalidated.
 */
public interface FullStopCapabilityCache {

    @Nullable
    FullStopCapability fullstop$getCapability();
}
