package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.server.physics.ship.ShipRedstoneContacts;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A button a Valkyrien Skies hull holds stays pressed ({@link ShipRedstoneContacts}). Vanilla's re-check when a
 * button's time is up looks for an arrow in it and otherwise lets it go; while a hull is on it, it is kept down and
 * checked again when its time is next up, as an arrow keeps a wooden one down.
 */
@Mixin(ButtonBlock.class)
public abstract class ShipButtonHoldMixin {

    @Inject(method = "checkPressed", at = @At("HEAD"), cancellable = true)
    private void fullstop$heldByAShip(BlockState state, Level level, BlockPos pos, CallbackInfo ci) {
        if (!(level instanceof ServerLevel server) || !ShipRedstoneContacts.holdsButton(server, pos)) return;
        ButtonBlock self = (ButtonBlock) (Object) this;
        if (!state.getValue(ButtonBlock.POWERED)) {
            self.press(state, level, pos);
        } else {
            level.scheduleTick(pos, self, ((ShipButtonAccessor) self).fullstop$ticksToStayPressed());
        }
        ci.cancel();
    }
}
