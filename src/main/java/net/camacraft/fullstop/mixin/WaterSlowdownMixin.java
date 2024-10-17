package net.camacraft.fullstop.mixin;

import net.camacraft.fullstop.capabilities.FullStopCapability;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.camacraft.fullstop.FullStop.logToChat;
import static net.camacraft.fullstop.capabilities.FullStopCapability.Provider.DELTAV_CAP;

@Mixin(LivingEntity.class)
public class WaterSlowdownMixin {

    // Adds realistic water drag (the faster you move in water the more it slows you down)
    @Inject(method = "travel", at = @At("HEAD"))
    private void modifyWaterTravel(Vec3 travelVector, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity.isDeadOrDying() || entity.isRemoved() || entity.isSpectator()) return;

        // Check if the entity is in water and cancel if they have Dolphin's Grace or Depth Strider Boots
        if (entity.isInWater() &&
                !entity.isAutoSpinAttack() &&
                !FullStopCapability.hasDolphinsGrace(entity) &&
                !FullStopCapability.hasDepthStrider(entity) &&
                !entity.isSpectator()) {
            Vec3 v = entity.getDeltaMovement();
            Vec3 direction = v.normalize();
            double speed = v.length();

            double c = 0.2;

            speed -= speed * speed * c;

            // Set the new velocity
            entity.setDeltaMovement(direction.scale(speed));
        }

        //logToChat(entity, entity.isAutoSpinAttack());
    }
}