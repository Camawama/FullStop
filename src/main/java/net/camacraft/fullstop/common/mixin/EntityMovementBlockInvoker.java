package net.camacraft.fullstop.common.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The block whose friction a body's travel takes ({@code Entity.getBlockPosBelowThatAffectsMyMovement}, protected), as the
 * body's own travel sees it (a Valkyrien Skies deck's block included): for {@code SlimeFlight}.
 */
@Mixin(Entity.class)
public interface EntityMovementBlockInvoker {

    @Invoker("getBlockPosBelowThatAffectsMyMovement")
    BlockPos fullstop$blockBelowForMovement();
}
