package net.camacraft.fullstop.common.compat;

import net.camacraft.camafy.compat.ModPresence;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.physics.ShipSweptContact;
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

    /**
     * Whether Valkyrien Skies is carrying the entity with {@code ship} right now: it stands on the
     * ship's deck, or left it within the last second (VS goes on moving it with the ship for 25
     * ticks after it last stood on it). Whatever that ship's hull does, VS moves such an entity
     * with it: nothing FullStop's ship code does may treat it as struck by that ship.
     */
    public static boolean carriedBy(Entity entity, @Nullable net.camacraft.camafy.compat.vs.ShipRef ship) {
        if (ship == null || !active() || !ShipEntities.isCarried(entity)) return false;
        return ship.equals(ShipEntities.standingOn(entity, CARRY_TICKS));
    }

    /** The ship VS is carrying the entity with right now (see {@link #carriedBy}), or null. */
    @Nullable
    public static net.camacraft.camafy.compat.vs.ShipRef carryingShip(Entity entity) {
        if (!active() || !ShipEntities.isCarried(entity)) return null;
        return ShipEntities.standingOn(entity, CARRY_TICKS);
    }

    /** VS carries an entity with a ship for this many ticks after it last stood on it. */
    public static final int CARRY_TICKS = 25;

    /**
     * Whether an entity moving at {@code worldVelocityMps} (its own velocity plus any carry, m/s)
     * really meets the ship that owns {@code shipPos} now or within the next tick: its box, moved
     * with its motion RELATIVE to the ship (an arc round a turning ship), comes within touching
     * distance of one of the ship's blocks (see {@link ShipSweptContact}). A ray cast a tick of
     * the entity's travel ahead hits whatever is in its way now; against a hull that moves away,
     * or a rotor's arm passing, that is no collision.
     */
    public static boolean meetsShip(Level level, Entity entity, BlockPos shipPos, Vec3 worldVelocityMps) {
        if (!active()) return true;
        net.camacraft.camafy.compat.vs.ShipRef ship = Ships.managing(level, shipPos);
        if (ship == null) return true;
        AABB bounds = ship.worldAabb();
        ShipSweptContact.Relative motion = ShipSweptContact.relativeTo(ship, worldVelocityMps);
        if (bounds == null || motion == null) return true;
        return !Double.isNaN(ShipSweptContact.firstTouch(ship::touchesBlock, bounds, motion, entity.getBoundingBox(),
                0.0, TICK_SECONDS));
    }

    private static final double TICK_SECONDS = 0.05;

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
     * The world-space outward normal of a block face: turned by the ship that owns {@code pos}
     * (raycast hits on ship blocks report their face in ship space), else the face's own.
     */
    public static Vec3 worldFaceNormal(Level level, BlockPos pos, net.minecraft.core.Direction face) {
        if (active()) {
            net.camacraft.camafy.compat.vs.ShipRef ship = Ships.managing(level, pos);
            Vec3 normal = ship != null ? ship.faceNormal(face) : null;
            if (normal != null) return normal;
        }
        return Vec3.atLowerCornerOf(face.getNormal());
    }

    /** True when every one of the positions is a ship block (also for none at all). */
    public static boolean onlyShipBlocks(Level level, List<BlockPos> positions) {
        if (!active()) return positions.isEmpty();
        for (BlockPos pos : positions) {
            if (!Ships.inShipyard(level, pos)) return false;
        }
        return true;
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
