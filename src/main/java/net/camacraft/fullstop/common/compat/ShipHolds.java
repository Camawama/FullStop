package net.camacraft.fullstop.common.compat;

import net.camacraft.camafy.compat.vs.ShipEntities;
import net.minecraft.world.entity.Entity;

/**
 * Valkyrien Skies holding a body in place. A body whose box touches a ship the game hasn't finished loading for it (a
 * ship made a moment ago, one whose chunks are still on their way, a ship the server hasn't told this player about
 * yet) is not moved at all: Valkyrien Skies cancels every {@code Entity.move} of it, on both sides, and its server
 * sets a player who moves anyway back where it was ("moved while colliding with unloaded ships!"). Nothing else in the
 * game stops for it: gravity goes on adding to the body's velocity.
 *
 * <p>Asked through Camafy ({@code ShipEntities.isCollidingWithUnloadedShips}): the same test VS runs, so it follows
 * Camafy 0.1.36's {@code valkyrienSkies.loadingShipsHoldOnlyAtTheirBlocks} (a body is held only at the ship's own
 * blocks, no longer anywhere in the chunk round a new ship). Without Valkyrien Skies the answer is no.
 */
public final class ShipHolds {

    private ShipHolds() {
    }

    /** Whether Valkyrien Skies is refusing to move {@code entity} this tick (it touches a ship not loaded for it). */
    public static boolean heldForUnloadedShip(Entity entity) {
        if (!ShipCompat.active()) return false;
        return ShipEntities.isCollidingWithUnloadedShips(entity);
    }
}
