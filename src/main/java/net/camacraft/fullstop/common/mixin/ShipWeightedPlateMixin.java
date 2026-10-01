package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.server.physics.ship.ShipRedstoneContacts;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A weighted pressure plate (gold, iron) counts the mass of the Valkyrien Skies hulls resting on it
 * ({@link ShipRedstoneContacts#weightedSignal}): whichever is more, the entities on it or the ships.
 */
@Mixin(WeightedPressurePlateBlock.class)
public abstract class ShipWeightedPlateMixin {

    @Shadow
    @Final
    private int maxWeight;

    @Inject(method = "getSignalStrength", at = @At("RETURN"), cancellable = true)
    private void fullstop$shipsWeigh(Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!(level instanceof ServerLevel server)) return;
        int ships = ShipRedstoneContacts.weightedSignal(server, pos, maxWeight);
        if (ships > cir.getReturnValueI()) cir.setReturnValue(ships);
    }
}
