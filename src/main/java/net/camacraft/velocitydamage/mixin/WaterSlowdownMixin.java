package net.camacraft.velocitydamage.mixin;

import net.camacraft.velocitydamage.VelocityDamage;
import net.camacraft.velocitydamage.capabilities.FullStopCapability;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.camacraft.velocitydamage.capabilities.FullStopCapability.Provider.DELTAV_CAP;

@Mixin(LivingEntity.class)
public class WaterSlowdownMixin {

    @Inject(method = "travel", at = @At("HEAD"))
    private void modifyWaterTravel(Vec3 travelVector, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity.isDeadOrDying() || entity.isRemoved()) return;
        FullStopCapability riptiding = entity.getCapability(DELTAV_CAP).orElseThrow(IllegalStateException::new);

        // Check if the entity is in water
        if (entity.isInWater() && !riptiding.recentlyRiptiding()) {
            Vec3 v = entity.getDeltaMovement();

            double c = 0.2;

            double newX = Math.signum(v.x) * (Math.abs(v.x) - (v.x * v.x * c));
            double newY = Math.signum(v.y) * (Math.abs(v.y) - (v.y * v.y * c));
            double newZ = Math.signum(v.z) * (Math.abs(v.z) - (v.z * v.z * c));

            // Set the new velocity
            entity.setDeltaMovement(newX, newY, newZ);
        }
    }
}