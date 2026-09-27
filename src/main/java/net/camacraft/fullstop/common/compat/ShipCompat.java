package net.camacraft.fullstop.common.compat;

import net.camacraft.camafy.compat.ModPresence;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * FullStop's switch for physics mods that move entities in the world frame
 * (Valkyrien Skies ships). The ship queries themselves are Camafy's
 * ({@link Ships}, {@link ShipEntities}), which name no VS types and answer as if
 * there were no ships when VS is absent; all this class adds is the
 * {@code enableValkyrienSkiesCompat} server config, which turns it all off at
 * runtime — with it off (or VS absent) FullStop behaves exactly as if VS were
 * not installed, which is the control environment for separating FullStop bugs
 * from VS interactions.
 *
 * <p>Why this exists: FullStop measures velocity from world-space position
 * deltas. A VS ship repositions the entities it carries every tick, so a rider
 * standing still reads as moving at the ship's speed, and ship rotation flips the
 * per-axis velocity sign — which the stopping-force math counts as a violent
 * bounce. Both manufacture phantom kinetic damage. Removing the ship-imposed
 * motion makes FullStop see only the rider's own (ship-relative) velocity.
 */
public final class ShipCompat {

    /**
     * The retired WeightRS mod. Its ship-weight physics lives in FullStop now; while its old jar
     * is still installed FullStop's copy stays off, so nothing weighs on a ship twice.
     */
    public static final boolean WEIGHTRS_PRESENT = ModPresence.isLoaded("weightrs");

    private ShipCompat() {
    }

    /** VS is installed and working, and {@code enableValkyrienSkiesCompat} is on. */
    public static boolean active() {
        return Ships.available()
                && FullStopConfig.SERVER_SPEC.isLoaded()
                && FullStopConfig.SERVER.enableValkyrienSkiesCompat.get();
    }

    /** Ship weight (standing forces, jump and landing impulses) runs: see the "Ship Weight" config. */
    public static boolean weightActive() {
        return active() && FullStopConfig.SERVER.enableShipWeight.get() && !WEIGHTRS_PRESENT;
    }

    /** Ships hit entities and terrain: see the "Ship Collisions" config. */
    public static boolean collisionsActive() {
        return active() && FullStopConfig.SERVER.enableShipCollisions.get();
    }

    /**
     * The world-space motion (m/s) a VS ship imposed on this entity last tick by
     * carrying/rotating it, to subtract from measured velocity: what is left is
     * the entity's velocity relative to the ship, the only motion that should
     * ever count as a kinetic impact. ZERO when no ship is carrying it.
     */
    public static Vec3 shipDragVelocityMps(Entity entity) {
        if (!active()) return Vec3.ZERO;
        return ShipEntities.carryMps(entity);
    }

    /** True on the tick the entity boarded/left a ship (a position discontinuity). */
    public static boolean justChangedShip(Entity entity) {
        if (!active()) return false;
        return ShipEntities.changedShipLastTick(entity);
    }

    /**
     * True when any VS ship's world AABB intersects the given box. Used by the
     * collision detector to decide (a) whether a swept volume that contains no
     * world blocks could still hit ship blocks, and (b) whether rays must go
     * through the ship-aware Level.clip instead of the fast vanilla traversal.
     */
    public static boolean anyShipsNearby(Level level, AABB box) {
        if (!active()) return false;
        return Ships.anyIntersecting(level, box);
    }

    /**
     * True when the block position belongs to a VS ship (shipyard region).
     * FullStop's raycasts reach ship blocks through VS's clip mixin, but VS
     * resolves ship–entity contact with its own constraint solver and never sets
     * the vanilla horizontalCollision/verticalCollision flags — so anything gated
     * on those flags must treat a shipyard hit as its own evidence.
     */
    public static boolean isShipBlock(Level level, BlockPos pos) {
        if (!active()) return false;
        return Ships.inShipyard(level, pos);
    }

    /**
     * A world-space box taken into the shipyard coordinates of the ship that
     * owns {@code shipPos} (the axis-aligned bounds of its transformed corners,
     * so conservative on rotated ships), or null when no ship owns the
     * position. Ship block hits come back from the raycast addressed in
     * shipyard coordinates, so any geometric test between the entity's box and
     * a hit ship block has to happen in that space.
     */
    @Nullable
    public static AABB worldToShipBox(Level level, BlockPos shipPos, AABB worldBox) {
        if (!active()) return null;
        return Ships.worldBoxToShip(level, shipPos, worldBox);
    }

    /**
     * True when any of the raycast hits landed on ship blocks. Ship contacts never
     * set the vanilla collision flags (VS resolves them in its own solver), so a
     * shipyard hit stands in for that evidence in the damage gate.
     */
    public static boolean anyShipBlock(Level level, List<BlockPos> positions) {
        if (!active() || positions.isEmpty()) return false;
        for (BlockPos pos : positions) {
            if (Ships.inShipyard(level, pos)) return true;
        }
        return false;
    }
}
