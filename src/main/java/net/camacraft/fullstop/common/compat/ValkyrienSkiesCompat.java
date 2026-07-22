package net.camacraft.fullstop.common.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

/**
 * Direct Valkyrien Skies API touchpoint. This class imports VS types, so it must
 * only ever be loaded when VS is present — every call site goes through
 * {@link ShipCompat}, which short-circuits before referencing this class when the
 * {@code valkyrienskies} mod is absent. Do not reference it anywhere else.
 */
final class ValkyrienSkiesCompat {

    private ValkyrienSkiesCompat() {
    }

    /**
     * The world-space motion (m/s) a VS ship imposed on this entity last tick by
     * carrying/rotating it. FullStop derives velocity from world position deltas,
     * which on a moving ship is dominated by the ship's motion — subtracting this
     * leaves the entity's velocity relative to the ship (the only motion that
     * should ever count as a kinetic impact). {@link Vec3#ZERO} when the entity
     * isn't riding ship physics.
     */
    static Vec3 shipDragVelocityMps(Entity entity) {
        if (!(entity instanceof IEntityDraggingInformationProvider provider)) return Vec3.ZERO;
        EntityDraggingInformation info = provider.getDraggingInformation();
        if (info == null || !info.isEntityBeingDraggedByAShip()) return Vec3.ZERO;
        Vector3dc added = info.getAddedMovementLastTick();
        if (added == null) return Vec3.ZERO;
        return new Vec3(added.x(), added.y(), added.z()).scale(20);
    }

    /**
     * True on the tick an entity boarded or left a ship, which snaps its world
     * position discontinuously — that jump must be swallowed like a teleport,
     * not read as a violent movement.
     */
    static boolean changedShipLastTick(Entity entity) {
        if (!(entity instanceof IEntityDraggingInformationProvider provider)) return false;
        EntityDraggingInformation info = provider.getDraggingInformation();
        return info != null && info.getChangedShipLastTick();
    }

    /**
     * True when the position is in VS's shipyard region, i.e. the block belongs to
     * a ship. FullStop's raycasts reach ship blocks through VS's clip mixin, but
     * VS resolves ship–entity contact with its own constraint solver and never
     * sets the vanilla horizontalCollision/verticalCollision flags — so anything
     * gated on those flags must treat a shipyard hit as its own evidence.
     */
    static boolean isShipBlock(Level level, BlockPos pos) {
        return VSGameUtilsKt.isBlockInShipyard(level, pos);
    }

    /** True when any loaded ship's world-space AABB intersects the box. */
    static boolean anyShipsIntersecting(Level level, AABB box) {
        return VSGameUtilsKt.getShipsIntersecting(level, box).iterator().hasNext();
    }
}
