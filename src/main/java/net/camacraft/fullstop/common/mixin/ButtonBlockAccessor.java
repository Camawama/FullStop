package net.camacraft.fullstop.common.mixin;

import net.minecraft.world.level.block.ButtonBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Whether a button is one vanilla lets arrows press: wooden (and bamboo, cherry...) buttons say yes, stone and
 * polished blackstone ones no. FullStop keeps a projectile it flings at a button to the same rule.
 */
@Mixin(ButtonBlock.class)
public interface ButtonBlockAccessor {
    @Accessor("arrowsCanPress")
    boolean fullstop$arrowsCanPress();
}
