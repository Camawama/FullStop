package net.camacraft.fullstop.common.compat.create.mixin;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionImpacts;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create's own train run-over damage stands down while FullStop judges trains as contraption hits
 * ({@code contraptionImpacts} with {@code contraptionsReplaceTrainDamage}): nothing is hurt twice. On
 * both sides (a player's client reports its own run-over to the server in Create).
 */
@Mixin(value = ContraptionCollider.class, remap = false)
public abstract class TrainDamageMixin {

    @Inject(method = "handleDamageFromTrain", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void fullstop$trainsAreContraptionHits(Level world, AbstractContraptionEntity contraptionEntity, Vec3 contraptionMotion,
                                                          Entity entity, Vec3 entityMotion,
                                                          @org.spongepowered.asm.mixin.injection.Coerce Object playerType,
                                                          CallbackInfoReturnable<Vec3> cir) {
        if (ContraptionImpacts.replacesTrainDamage()) cir.setReturnValue(entityMotion);
    }
}
