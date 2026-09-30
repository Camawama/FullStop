package net.camacraft.fullstop.common.mixin;

import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Whether an arrow (or trident) is stuck in a block ({@code AbstractArrow.inGround}, protected):
 * only one still in flight can meet another projectile in the air (see ProjectileCollisions).
 */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {

    @Accessor("inGround")
    boolean fullstop$inGround();
}
