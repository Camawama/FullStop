package net.camacraft.fullstop.common.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.camacraft.fullstop.server.physics.interaction.BreakEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Leaves out a destroyed block's break effect (level event 2001: its sound and dust on every client) while FullStop
 * breaks it quietly ({@link BreakEffects}: past the tick's ration of effects during mass breaking). Everything else
 * {@code destroyBlock} does, and every other mod's hooks on it, run as before.
 */
@Mixin(Level.class)
public abstract class LevelDestroyBlockEffectMixin {

    @WrapOperation(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;levelEvent(ILnet/minecraft/core/BlockPos;I)V"))
    private void fullstop$quietBreak(Level level, int type, BlockPos pos, int data, Operation<Void> original) {
        if (!BreakEffects.quiet()) original.call(level, type, pos, data);
    }
}
