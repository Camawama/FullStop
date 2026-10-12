package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.physics.SlimeFlight;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After a living body's travel: the flight off a slime bounce keeps its sideways speed ({@link SlimeFlight}). */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySlimeFlightMixin {

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
    private void fullstop$beforeSlimeFlightTravel(Vec3 input, CallbackInfo ci) {
        SlimeFlight.beforeTravel((LivingEntity) (Object) this);
    }

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("RETURN"))
    private void fullstop$carrySlimeFlight(Vec3 input, CallbackInfo ci) {
        SlimeFlight.afterTravel((LivingEntity) (Object) this);
    }
}
