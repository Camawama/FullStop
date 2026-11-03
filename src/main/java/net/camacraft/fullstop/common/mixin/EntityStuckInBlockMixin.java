package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.capability.StuckInBlock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers when a block last held the entity ({@link StuckInBlock}). Vanilla's {@code makeStuckInBlock} is what powder
 * snow, cobwebs and sweet berry bushes (and other mods' blocks of that kind) call from {@code entityInside}: it resets the
 * fall distance and sets the multiplier that the next {@code move} applies before zeroing the entity's velocity. Both sides
 * run it (the client for its own player, the server for every body and for a player's move as the move packet is checked),
 * so the mark is there wherever Full Stop measures. Plain Mixin on a vanilla class (no MixinExtras).
 */
@Mixin(Entity.class)
public abstract class EntityStuckInBlockMixin implements StuckInBlock {

    @Shadow
    public int tickCount;

    @Unique
    private int fullstop$stuckTick = Integer.MIN_VALUE;

    @Unique
    private BlockState fullstop$stuckState;

    @Inject(method = "makeStuckInBlock", at = @At("HEAD"))
    private void fullstop$heldByBlock(BlockState state, Vec3 motionMultiplier, CallbackInfo ci) {
        fullstop$stuckTick = tickCount;
        fullstop$stuckState = state;
    }

    @Override
    public int fullstop$stuckTick() {
        return fullstop$stuckTick;
    }

    @Override
    public BlockState fullstop$stuckState() {
        return fullstop$stuckState;
    }
}
