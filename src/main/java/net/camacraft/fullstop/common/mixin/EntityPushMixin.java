package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses vanilla entity pushing between an ex-rider and their ex-vehicle
 * for the dismount grace period.
 *
 * Vanilla push applies a purely LATERAL force along the center-to-center line.
 * That never mattered before because a dismounted boat was always stationary —
 * but with dismount momentum, the ejected rider and the boat travel side by
 * side at the same speed for many consecutive ticks, and the accumulated
 * vanilla push visibly veered the boat off to the left/right (and nudged the
 * player). FullStop's own entity-collision system already exempts the pair
 * (ServerCollisionDetector.recentRideExchange); this extends the same grace to
 * the vanilla push, on both logical sides.
 */
@Mixin(Entity.class)
public abstract class EntityPushMixin {

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void fullstop$skipRecentRidePair(Entity other, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;

        FullStopCapability selfCap = FullStopCapability.grabCapability(self);
        if (selfCap != null && selfCap.recentlyExchangedWith(other)) {
            ci.cancel();
            return;
        }

        FullStopCapability otherCap = FullStopCapability.grabCapability(other);
        if (otherCap != null && otherCap.recentlyExchangedWith(self)) {
            ci.cancel();
        }
    }
}
