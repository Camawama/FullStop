package net.camacraft.fullstop.common.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FullStop replaces vanilla fly-into-wall damage with its own kinetic wall
 * impact. @WrapOperation (not @Redirect) so other mods hooking the same hurt
 * call still compose with this one.
 */
@Mixin(LivingEntity.class)
public class ElytraDamageCanceler {
    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean cancelElytraDamage(LivingEntity instance, DamageSource source, float amount, Operation<Boolean> original) {
        if (source.is(DamageTypes.FLY_INTO_WALL)) {
            return false;
        }
        return original.call(instance, source, amount);
    }
}