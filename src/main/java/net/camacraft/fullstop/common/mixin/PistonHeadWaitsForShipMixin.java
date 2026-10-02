package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.server.physics.ship.ShipPistons;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A piston head (or a block a piston pushes) that a Valkyrien Skies ship has to make room for stays moving, server side,
 * until the ship has glided clear ({@link ShipPistons#holdsMovingBlock}); so does a sticky piston's retracting base until
 * the ship it pulls has come to rest short of it. A moving piston has no physics shape (Camafy
 * 0.1.19), the finished head has one: a head that turned solid inside the ship's hull, or inside the wall the ship's own
 * piston pushes against, flung the ship in any direction. That happened whenever the server ran its ticks back to back
 * (catching up after a lag spike), finishing the two-tick stroke before a single physics step had moved the ship.
 * Nothing changes when the ship keeps up with the head; the client draws the head as always.
 */
@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonHeadWaitsForShipMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private static void fullstop$waitForTheShip(Level level, BlockPos pos, BlockState state, PistonMovingBlockEntity entity,
                                                CallbackInfo ci) {
        if (ShipPistons.holdsMovingBlock(level, pos)) ci.cancel();
    }
}
