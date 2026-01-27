package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.physics.Physics;
import net.camacraft.fullstop.common.sound.SoundPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class WaterSlowdownMixin {

    private static final String SLOSH_CD_TAG = "fullstop_slosh_cd";

    @Inject(method = "travel", at = @At("HEAD"))
    private void modifyWaterTravel(Vec3 travelVector, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity.isDeadOrDying() || entity.isRemoved() || entity.isSpectator()) return;

        // Check if the entity is in water and cancel water drag if they have Dolphin's Grace or Depth Strider Boots
        // Also cancel if using Riptide (AutoSpinAttack)
        if (entity.isInWater() &&
                !entity.isAutoSpinAttack() &&
                !FullStopCapability.hasDolphinsGrace(entity) &&
                !FullStopCapability.hasDepthStrider(entity) &&
                !entity.isSpectator()) {
            Vec3 v = entity.getDeltaMovement();
            Vec3 direction = v.normalize();
            double speed = v.length();
        if (!entity.isInWater()
                || entity.isAutoSpinAttack()
                || FullStopCapability.hasDolphinsGrace(entity)
                || FullStopCapability.hasDepthStrider(entity)) {
            return;
        }

        Vec3 v = entity.getDeltaMovement();
        double horizontalSpeed = Math.sqrt(v.x * v.x + v.z * v.z);
        if (horizontalSpeed < 0.01) return;

        double oldSpeed = v.length();
        if (oldSpeed < 1.0e-6) return;

        Vec3 direction = v.normalize();
        double c = 0.2;
        double newSpeed = oldSpeed - oldSpeed * oldSpeed * c;
        if (newSpeed < 0) newSpeed = 0;

        entity.setDeltaMovement(direction.scale(newSpeed));

        int cd = entity.getPersistentData().getInt(SLOSH_CD_TAG);
        if (cd > 0) {
            entity.getPersistentData().putInt(SLOSH_CD_TAG, cd - 1);
            return;
        }

        double maxSpeed = 0.45;
        double speedT = clamp(horizontalSpeed / maxSpeed, 0.0, 1.0);

        double mass = Physics.getEntityMass(entity);
        double massRef = 0.7;
        double massT = clamp(
                Math.log1p(Math.max(0.0, mass / massRef)) / Math.log1p(6.0),
                0.0,
                1.0
        );

        double impact = clamp(speedT * (0.75 + 0.50 * massT), 0.0, 1.0);

        float volume = (float) clamp(0.05 + impact * 0.95 + massT * 0.15, 0.05, 1.4);
        float pitch  = (float) clamp(1.30 - impact * 0.55 - massT * 0.25, 0.55, 1.35);

        SoundPlayer.playWaterSlosh(entity, volume, pitch);

        int nextCd = (int) Math.round(12 - impact * 10);
        entity.getPersistentData().putInt(SLOSH_CD_TAG, clampInt(nextCd, 2, 14));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
