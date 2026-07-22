package net.camacraft.fullstop.common.compat;

import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * Soft entry point for physics mods that move entities in the world frame
 * (Valkyrien Skies ships). No VS types are named here, so this class loads fine
 * without VS installed; the real API access in {@link ValkyrienSkiesCompat} is
 * only reached after the loaded check, so its VS classes are never linked in a
 * pack that lacks the mod. The {@code enableValkyrienSkiesCompat} server config
 * turns all of it off at runtime — with it off (or VS absent) FullStop behaves
 * exactly as if VS were not installed, which is the control environment for
 * separating FullStop bugs from VS interactions.
 *
 * <p>Why this exists: FullStop measures velocity from world-space position
 * deltas. A VS ship repositions the entities it carries every tick, so a rider
 * standing still reads as moving at the ship's speed, and ship rotation flips the
 * per-axis velocity sign — which the stopping-force math counts as a violent
 * bounce. Both manufacture phantom kinetic damage. Removing the ship-imposed
 * motion makes FullStop see only the rider's own (ship-relative) velocity.
 */
public final class ShipCompat {

    private static final boolean VS_LOADED = ModList.get().isLoaded("valkyrienskies");

    private ShipCompat() {
    }

    private static boolean active() {
        return VS_LOADED
                && FullStopConfig.SERVER_SPEC.isLoaded()
                && FullStopConfig.SERVER.enableValkyrienSkiesCompat.get();
    }

    /** Ship-imposed world motion (m/s) to subtract from measured velocity, or ZERO. */
    public static Vec3 shipDragVelocityMps(Entity entity) {
        if (!active()) return Vec3.ZERO;
        return ValkyrienSkiesCompat.shipDragVelocityMps(entity);
    }

    /** True on the tick the entity boarded/left a ship (a position discontinuity). */
    public static boolean justChangedShip(Entity entity) {
        if (!active()) return false;
        return ValkyrienSkiesCompat.changedShipLastTick(entity);
    }

    /**
     * True when any VS ship's world AABB intersects the given box. Used by the
     * collision detector to decide (a) whether a swept volume that contains no
     * world blocks could still hit ship blocks, and (b) whether rays must go
     * through the ship-aware Level.clip instead of the fast vanilla traversal.
     */
    public static boolean anyShipsNearby(Level level, AABB box) {
        if (!active()) return false;
        return ValkyrienSkiesCompat.anyShipsIntersecting(level, box);
    }

    /** True when the block position belongs to a VS ship (shipyard region). */
    public static boolean isShipBlock(Level level, BlockPos pos) {
        if (!active()) return false;
        return ValkyrienSkiesCompat.isShipBlock(level, pos);
    }

    /**
     * True when any of the raycast hits landed on ship blocks. Ship contacts never
     * set the vanilla collision flags (VS resolves them in its own solver), so a
     * shipyard hit stands in for that evidence in the damage gate.
     */
    public static boolean anyShipBlock(Level level, List<BlockPos> positions) {
        if (!active() || positions.isEmpty()) return false;
        for (BlockPos pos : positions) {
            if (ValkyrienSkiesCompat.isShipBlock(level, pos)) return true;
        }
        return false;
    }
}
