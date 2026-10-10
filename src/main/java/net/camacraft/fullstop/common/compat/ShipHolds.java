package net.camacraft.fullstop.common.compat;

import net.minecraft.world.entity.Entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Valkyrien Skies holding a body in place. A body whose box touches a ship the game hasn't finished loading for it (a
 * ship made a moment ago, one whose chunks are still on their way, a ship the server hasn't told this player about
 * yet) is not moved at all: Valkyrien Skies cancels every {@code Entity.move} of it, on both sides, and its server
 * sets a player who moves anyway back where it was ("moved while colliding with unloaded ships!"). Nothing else in the
 * game stops for it: gravity goes on adding to the body's velocity.
 *
 * <p>Asked of Valkyrien Skies by name ({@code EntityShipCollisionUtils.isCollidingWithUnloadedShips}): Camafy has no
 * call for it yet (one belongs in its {@code ShipEntities}); without Valkyrien Skies 2.4 the answer is no.
 */
public final class ShipHolds {

    private static final MethodHandle CHECK = find();

    private ShipHolds() {
    }

    private static MethodHandle find() {
        try {
            Class<?> utils = Class.forName("org.valkyrienskies.mod.common.util.EntityShipCollisionUtils");
            return MethodHandles.publicLookup().findStatic(utils, "isCollidingWithUnloadedShips",
                    MethodType.methodType(boolean.class, Entity.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Whether Valkyrien Skies is refusing to move {@code entity} this tick (it touches a ship not loaded for it). */
    public static boolean heldForUnloadedShip(Entity entity) {
        if (CHECK == null || !ShipCompat.active()) return false;
        try {
            return (boolean) CHECK.invoke(entity);
        } catch (Throwable t) {
            return false;
        }
    }
}
