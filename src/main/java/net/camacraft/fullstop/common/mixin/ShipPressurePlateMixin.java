package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.server.physics.ship.ShipRedstoneContacts;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PressurePlateBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A plain pressure plate (wood, stone) is fully pressed while a Valkyrien Skies hull rests on it ({@link ShipRedstoneContacts}). */
@Mixin(PressurePlateBlock.class)
public abstract class ShipPressurePlateMixin {

    @Inject(method = "getSignalStrength", at = @At("RETURN"), cancellable = true)
    private void fullstop$shipsPress(Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() < 15 && level instanceof ServerLevel server && ShipRedstoneContacts.plateMassKg(server, pos) > 0.0) {
            cir.setReturnValue(15);
        }
    }
}
