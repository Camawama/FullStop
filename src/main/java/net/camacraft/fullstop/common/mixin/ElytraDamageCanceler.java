package net.camacraft.fullstop.common.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LivingEntity.class)
public class ElytraDamageCanceler {
    @Redirect(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean cancelElytraDamage(LivingEntity instance, DamageSource source, float amount) {
        if (source.is(DamageTypes.FLY_INTO_WALL)) {
            return false;
        }
        return instance.hurt(source, amount);
    }
}