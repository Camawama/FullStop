package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.rules.EquipmentRules;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.camacraft.fullstop.common.sound.FSSoundPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class WaterSlowdownMixin {

    @Unique
    private static final String SLOSH_CD_TAG = "fullstop_slosh_cd";

    @Inject(method = "travel", at = @At("HEAD"))
    private void modifyWaterTravel(Vec3 travelVector, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity.isDeadOrDying() || entity.isRemoved() || entity.isSpectator()) return;

        FullStopCapability cap = FullStopCapability.grabCapability(entity);
        if (cap != null && cap.getWaterSkipCooldown() > 0) {
            return;
        }

        if (!entity.isInWater()
                || entity.isAutoSpinAttack()
                || entity.getMobType() == MobType.WATER
                || EquipmentRules.hasDolphinsGrace(entity)
                || EquipmentRules.hasDepthStrider(entity)) {
            return;
        }

        Vec3 v = entity.getDeltaMovement();

        // Server-side players never write move packets back into deltaMovement,
        // so gate/scale the slosh SOUND by the capability's measured velocity —
        // otherwise observers essentially never heard other players slosh. The
        // slowdown itself stays on deltaMovement (players get it client-side).
        Vec3 soundV = v;
        if (entity instanceof Player && !entity.level().isClientSide() && cap != null) {
            Vec3 capV = cap.getCurrentNativeVelocity();
            if (capV.lengthSqr() > soundV.lengthSqr()) soundV = capV;
        }
        double horizontalSpeed = soundV.horizontalDistance();
        if (horizontalSpeed < 0.01) return;

        double oldSpeed = v.length();

        Vec3 direction = v.normalize();
        double c = 0.2;
        double newSpeed = oldSpeed / (1.0 + oldSpeed * c);

        entity.setDeltaMovement(direction.scale(newSpeed));

        int cd = cap != null ? cap.getSloshCooldown() : entity.getPersistentData().getInt(SLOSH_CD_TAG);
        if (cd > 0) {
            if (cap != null) {
                cap.setSloshCooldown(cd - 1);
            } else {
                entity.getPersistentData().putInt(SLOSH_CD_TAG, cd - 1);
            }
            return;
        }

        double maxSpeed = 0.45;
        double speedT = Mth.clamp(horizontalSpeed / maxSpeed, 0.0, 1.0);

        double mass = EntityStackUtils.getEntityMass(entity);
        double massRef = 0.7;
        double massT = Mth.clamp(
                Math.log1p(Math.max(0.0, mass / massRef)) / Math.log1p(6.0),
                0.0,
                1.0
        );

        double impact = Mth.clamp(speedT * (0.75 + 0.50 * massT), 0.0, 1.0);

        float volume = (float) Mth.clamp(0.05 + impact * 0.95 + massT * 0.15, 0.05, 1.4);
        float pitch  = (float) Mth.clamp(1.30 - impact * 0.55 - massT * 0.25, 0.55, 1.35);

        if (entity.isEyeInFluid(FluidTags.WATER)) {
            volume *= 0.5f;
        }

        if (entity.level().isClientSide()) {
            FSSoundPlayer.playSoundClient(entity, FSSoundPlayer.sound(FSSoundPlayer.WATER_SLOSH_ID), SoundSource.PLAYERS, volume, pitch);
        } else {
            if (entity instanceof Player player) {
                entity.level().playSound(player, entity.getX(), entity.getY(), entity.getZ(), FSSoundPlayer.sound(FSSoundPlayer.WATER_SLOSH_ID), SoundSource.PLAYERS, volume, pitch);
            } else {
                FSSoundPlayer.playSoundServer(entity, FSSoundPlayer.sound(FSSoundPlayer.WATER_SLOSH_ID), SoundSource.PLAYERS, volume, pitch);
            }
        }

        // Capability-held cooldown (transient); persistentData is only the
        // fallback — it gets written to the entity's saved NBT, which junked
        // world saves with a bookkeeping int.
        int nextCd = Mth.clamp((int) Math.round(12 - impact * 10), 2, 14);
        if (cap != null) {
            cap.setSloshCooldown(nextCd);
        } else {
            entity.getPersistentData().putInt(SLOSH_CD_TAG, nextCd);
        }
    }
}
