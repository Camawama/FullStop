package net.camacraft.fullstop.common.compat.create.mixin;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionStress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A contraption's hits load the block that drives it ({@code contraptionImpactStress}): the extra impact is added
 * where Create asks a kinetic block entity for its stress impact, so every way Create sums a network's stress
 * (joining a network, a re-sum after a speed change, FullStop's own update) carries it. Not on
 * {@code lastStressApplied}: what Create saves stays the block's own impact.
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class KineticStressMixin {

    @Inject(method = "calculateStressApplied", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void fullstop$impactLoad(CallbackInfoReturnable<Float> cir) {
        float extra = ContraptionStress.extraPerRpm((KineticBlockEntity) (Object) this);
        if (extra != 0.0f) cir.setReturnValue(cir.getReturnValueF() + extra);
    }
}
