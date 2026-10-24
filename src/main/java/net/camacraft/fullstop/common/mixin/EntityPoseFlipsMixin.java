package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.capability.PoseFlips;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts the entity's pose changes ({@link PoseFlips}). Every change of synched data reaches
 * {@code onSyncedDataUpdated} on its side: a set on the server (only when the value changes), the update packet on a
 * client. Getting into a bed, out of one, or being loaded asleep from a save all set the pose, so FullStopCapability's
 * bed check and its pose tracking read the locked pose and sleeping data only on a tick the count moved, instead of for
 * every body every tick (45 + 15 ms of every 30 s with 400 mobs, pack harness perf_pack, 2026-10-23).
 */
@Mixin(Entity.class)
public abstract class EntityPoseFlipsMixin implements PoseFlips {

    @Shadow
    @Final
    protected static EntityDataAccessor<Pose> DATA_POSE;

    @Unique
    private int fullstop$poseFlips;

    @Inject(method = "onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V", at = @At("HEAD"))
    private void fullstop$countPose(EntityDataAccessor<?> key, CallbackInfo ci) {
        if (DATA_POSE.equals(key)) fullstop$poseFlips++;
    }

    @Override
    public int fullstop$poseFlips() {
        return fullstop$poseFlips;
    }
}
