package net.camacraft.fullstop.common.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets a mob keep wandering while something rides it, as long as nobody is
 * actually STEERING it.
 *
 * Vanilla's stroll goal refuses to run for any vehicle, which froze mobs
 * mounted through FullStop's land-on-a-mob feature: a cow with a player on its
 * back stood still (look goals have no such check, so it still looked around).
 * The vehicle check is replaced with "has a controlling passenger" — saddled
 * steerables (horses, pigs, striders) behave exactly as before, jockey chains
 * are unaffected (their goals are disabled by control flags anyway), and an
 * un-steered mount walks off with its unwanted passenger.
 */
@Mixin(RandomStrollGoal.class)
public abstract class RandomStrollGoalMixin {

    @Shadow
    @Final
    protected PathfinderMob mob;

    @ModifyExpressionValue(
            method = {"canUse", "canContinueToUse"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/PathfinderMob;isVehicle()Z"))
    private boolean fullstop$strollUnlessSteered(boolean isVehicle) {
        return isVehicle && this.mob.getControllingPassenger() != null;
    }
}
