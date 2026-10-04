package net.camacraft.fullstop.common.mixin;

import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.common.physics.PlayerLandingBounce;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SlimeBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Disables vanilla slime block bounce so FullStop's BounceHandler owns it, except a player's own landing, which its
 * client bounces here ({@link PlayerLandingBounce}).
 * Cancellable inject instead of @Overwrite for compatibility with other mods.
 */
@Mixin(SlimeBlock.class)
public abstract class SlimeBlockNoBounceMixin {

    @Inject(method = "updateEntityAfterFallOn", at = @At("HEAD"), cancellable = true)
    private void fullstop$suppressVanillaBounce(BlockGetter level, Entity entity, CallbackInfo ci) {
        // Entities FullStop never simulates (dropped items, XP orbs) keep their
        // vanilla bounce — suppressing it for them replaced it with nothing.
        if (DamageImmunityRules.unphysableIgnoringAi(entity)) return;
        // ...and so do entities whose bounces another mod owns (cached claims).
        if (PhysicsClaims.cachedClaimed(entity, PhysicsAspect.BOUNCE)) return;
        // A body moving away from the slime keeps its motion, as vanilla's bounce leaves it (it only acts on a fall):
        // a Valkyrien Skies deck adjusts the movement of a body leaving it, which reads as one more vertical collision,
        // and stopping it here took the upward motion of a bounce that had just started (the owner: on a ship a landing
        // on slime "sometimes just breaks my fall").
        if (entity.getDeltaMovement().y >= 0.0D) {
            ci.cancel();
            return;
        }
        // Only slime it really stands on (looked for along its own down): on a Valkyrien Skies ship vanilla's "block it is
        // on" can be the ship's slime ABOVE the body after it hit the ship's underside, and stopping (or bouncing) it here
        // undid Full Stop's bounce off that ceiling. Any other face is Full Stop's kinetic bounce's (BounceHandler), as
        // for world slime.
        PlayerLandingBounce.Surface surface = PlayerLandingBounce.underFeet(entity);
        if (surface == null) {
            ci.cancel();
            return;
        }
        // ...and the local player lands on its own client, right here (playerSlimeLandingsOnClient)
        if (PlayerLandingBounce.bounce(entity, surface)) {
            ci.cancel();
            return;
        }

        entity.setDeltaMovement(entity.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
        ci.cancel();
    }
}
