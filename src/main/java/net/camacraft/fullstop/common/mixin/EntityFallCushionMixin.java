package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.capability.FallCushion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes a fall cushion ({@link FallCushion}) at every move: vanilla's {@code checkFallDamage} runs on each move of a body
 * (for a player on the server, at each move packet through {@code doCheckFallDamage}), and at its head the fall distance
 * is still what a mod set, also on the move that lands and resets it to 0. Full Stop's own reading once a tick, at the end
 * of the level tick, could fall between a mod setting the cushion and a move clearing it, or more than a few ticks before
 * a landing a player's client sent late. Plain Mixin on a vanilla class (no MixinExtras): an {@code @Inject} at HEAD,
 * which stacks with other mods' injections and cancels nothing.
 */
@Mixin(Entity.class)
public abstract class EntityFallCushionMixin implements FallCushion {

    @Shadow
    public float fallDistance;

    @Unique
    private boolean fullstop$fallCushioned;

    @Unique
    private int fullstop$cushionGroundTicks;

    @Inject(method = "checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"))
    private void fullstop$noteFallCushion(double y, boolean onGround, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (fallDistance < 0.0F) {
            fullstop$fallCushioned = true;
            fullstop$cushionGroundTicks = 0;
        }
    }

    @Override
    public boolean fullstop$fallCushioned() {
        return fullstop$fallCushioned;
    }

    @Override
    public void fullstop$setFallCushioned(boolean cushioned) {
        fullstop$fallCushioned = cushioned;
    }

    @Override
    public int fullstop$cushionGroundTicks() {
        return fullstop$cushionGroundTicks;
    }

    @Override
    public void fullstop$setCushionGroundTicks(int ticks) {
        fullstop$cushionGroundTicks = ticks;
    }
}
