package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.capability.FullStopCapabilityCache;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Caches the resolved FullStop capability on the entity itself, replacing a
 * Forge CapabilityDispatcher walk per {@code grabCapability} call (which the
 * physics loop makes for every entity every tick) with a field read. The cache
 * is dropped if the LazyOptional is invalidated (entity removal), and entities
 * get fresh instances on respawn/dimension change, so it can never go stale.
 */
@Mixin(Entity.class)
public abstract class EntityCapabilityCacheMixin implements FullStopCapabilityCache {

    // Volatile, and written cached-then-resolved: collision-shape queries (which
    // reach grabCapability via PhaseableBlockMixin) can run off the main thread,
    // and a reader must never see resolved == true with a torn cached value.
    // A racing double-resolve is idempotent.
    @Unique
    @Nullable
    private volatile FullStopCapability fullstop$cached;

    @Unique
    private volatile boolean fullstop$resolved;

    @Override
    @Nullable
    public FullStopCapability fullstop$getCapability() {
        if (!fullstop$resolved) {
            LazyOptional<FullStopCapability> optional =
                    ((Entity) (Object) this).getCapability(FullStopCapability.Provider.DELTAV_CAP);
            fullstop$cached = optional.orElse(null);
            fullstop$resolved = true;
            optional.addListener(o -> {
                fullstop$cached = null;
                fullstop$resolved = false;
            });
        }
        return fullstop$cached;
    }
}
