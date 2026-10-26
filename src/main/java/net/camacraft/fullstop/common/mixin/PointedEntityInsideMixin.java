package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.physics.rules.PointedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A body inside a passable pointed block's space (Supplementaries' bamboo spikes: only a base plate collides, the points
 * stand in the rest of the cell) is hurt by Full Stop for the speed it met the point at ({@link PointedBlocks#entityInside}),
 * before the block's own handling. Runs for every block every body overlaps every tick, so each state keeps whether
 * it is a passable point (until the rules or tags change) and every other block returns at once.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class PointedEntityInsideMixin {

    /** The passable rule of this state, read at {@link #fullstop$pointedEpoch}; null when it has none. */
    @Unique
    private PointedBlocks.Rule fullstop$pointed;

    /** {@link PointedBlocks#epoch()} + 1 when {@link #fullstop$pointed} was read; 0 before. */
    @Unique
    private int fullstop$pointedEpoch;

    @Inject(method = "entityInside", at = @At("HEAD"))
    private void fullstop$pointedInside(Level level, BlockPos pos, Entity entity, CallbackInfo ci) {
        int stamp = PointedBlocks.epoch() + 1;
        PointedBlocks.Rule rule;
        if (fullstop$pointedEpoch == stamp) {
            rule = fullstop$pointed;
        } else {
            PointedBlocks.Rule found = PointedBlocks.ruleFor((BlockState) (Object) this);
            rule = found != null && found.passable() ? found : null;
            fullstop$pointed = rule;
            fullstop$pointedEpoch = stamp;
        }
        if (rule != null) PointedBlocks.entityInside(rule, (BlockState) (Object) this, level, pos, entity);
    }
}
