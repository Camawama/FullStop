package net.camacraft.fullstop.common.compat.create.mixin;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.camacraft.fullstop.common.compat.create.kinetic.KineticPresence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every turning kinetic block entity notes its level as it ticks ({@link KineticPresence}), so the gear, water-wheel
 * and flywheel hazard check ({@code KineticHazards}) can stand down for every body in a level where nothing turns.
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class KineticTickMixin {

    @Inject(method = "tick", at = @At("HEAD"), require = 0, remap = false)
    private void fullstop$noteTurning(CallbackInfo ci) {
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        if (self.getLevel() != null && self.getSpeed() != 0.0f) KineticPresence.note(self.getLevel());
    }
}
