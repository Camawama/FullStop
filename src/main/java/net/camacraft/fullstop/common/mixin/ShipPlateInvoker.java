package net.camacraft.fullstop.common.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A pressure plate's own press check, for a ship settling on it (as an entity stepping on it runs it). */
@Mixin(BasePressurePlateBlock.class)
public interface ShipPlateInvoker {

    @Invoker("checkPressed")
    void fullstop$checkPressed(@Nullable Entity entity, Level level, BlockPos pos, BlockState state, int currentSignal);
}
