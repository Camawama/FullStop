package net.camacraft.fullstop.common.mixin;

import net.minecraft.world.level.block.ButtonBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import org.jetbrains.annotations.Nullable;

/**
 * Whether a button is one vanilla lets arrows press: wooden (and bamboo, cherry...) buttons say yes, stone and
 * polished blackstone ones no. FullStop keeps a projectile it flings at a button to the same rule.
 */
@Mixin(ButtonBlock.class)
public interface ButtonBlockAccessor {
    @Accessor("arrowsCanPress")
    boolean fullstop$arrowsCanPress();

    /** The button's own click ({@code pressed}: on, else off), as vanilla plays it for a player's use or an arrow's hit. */
    @Invoker("playSound")
    void fullstop$playSound(@Nullable Player player, LevelAccessor level, BlockPos pos, boolean pressed);
}
