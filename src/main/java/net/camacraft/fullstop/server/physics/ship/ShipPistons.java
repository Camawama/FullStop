package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.vs.ShipForces;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Pistons and Valkyrien Skies ships ({@code pistonsPushShips}). VS doesn't let a piston move a ship: its head
 * extends into the hull and the ship is slowly shoved out again by VS's contact resolution. Here a piston moves a
 * ship the way it moves a block:
 * <ul>
 *   <li><b>A world piston extending into a hull pushes the ship one block</b> the way its head goes, over the two
 *   ticks the head takes ({@link #EXTENSION_TICKS}): it glides there on Valkyrien Skies' physics thread ({@link #glide}),
 *   the head's speed ({@link #PUSH_MPS}) at first and slowing as it closes on the block, and is given back what it had
 *   along the piston once there, so the hull moves with the head and stops with it, exactly a block on. The blocks the piston pushes count as the head: a ship
 *   in their path is pushed too.</li>
 *   <li><b>A sticky piston pulls it back</b> ({@code pistonStickyPulls}): when it retracts, a ship touching the
 *   block in front of its head comes back one block the same way, starting as the head clears the cell (VS takes a
 *   retracting head for a solid block until it is gone); a ship too heavy to pull is left, the piston retracting as
 *   from any immovable block.</li>
 *   <li><b>A piston on a ship pushes its ship off what it pushes against</b> ({@code pistonShipReaction}): a
 *   head extending into the terrain shoves the ship a block the other way (it pushed itself off), and a sticky
 *   head retracting from the terrain pulls the ship a block toward it. Against another ship, the other ship is
 *   pushed (as by a world piston).</li>
 *   <li><b>Mass</b> ({@code pistonPushMassKg}, 2 t a piston): pistons pushing the same ship the same way add up,
 *   and a group that can't move its ship doesn't extend at all (the event is cancelled), as a piston against any
 *   immovable block. A pinned (static) ship is immovable.</li>
 * </ul>
 * All through Forge's {@link PistonEvent.Pre}, which fires before the piston moves anything, and Camafy's hull
 * tests for where the head goes.
 */
public final class ShipPistons {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** The head's speed (m/s): one block over the extension's two ticks. */
    static final double PUSH_MPS = 10.0;
    /** How long (ticks) a piston head takes to extend or retract. */
    static final int EXTENSION_TICKS = 2;
    /**
     * The longest (ticks) a ship glides to its block (see {@link #glide}); it normally arrives in the head's own two or
     * three ticks, and floor friction or another ship in the way can't make it creep on for ever. Also how long the rest
     * of a group rides along ({@link #RIDE_ALONG_TICKS} more).
     */
    static final int GLIDE_TICKS = 10;
    /** The same in physics steps (Valkyrien Skies steps at 60 Hz). */
    static final int GLIDE_STEPS = GLIDE_TICKS * 3;
    /** How fast (per second) the glide closes the distance left: at most {@link #PUSH_MPS}. */
    static final double GLIDE_GAIN = 12.0;
    /** Close enough (blocks): the ship has arrived. */
    static final double ARRIVED = 0.03;

    /**
     * A ship gliding one block along a piston. Steered on Valkyrien Skies' physics thread every step ({@link #glide})
     * from the velocity and position that step has: from the server tick, a correction made on a stale frame was made
     * again on the next tick, and a pull carried its raft eighteen blocks. Created by the server thread; from then on
     * only the physics thread touches it.
     */
    private static final class Glide {
        final double dx, dy, dz;
        int steps;
        @org.jetbrains.annotations.Nullable
        org.joml.Vector3d from;
        double before;

        Glide(Vec3 direction) {
            this.dx = direction.x;
            this.dy = direction.y;
            this.dz = direction.z;
        }
    }

    /** The glides under way, by ship id (any level: ship ids are unique). */
    private static final Map<Long, Glide> GLIDES = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * A pull waiting for its retracting head to clear the cell the ship moves into: Valkyrien Skies takes a moving
     * piston head for a solid block until it is gone, and a ship pulled into it stops dead.
     */
    private record Waiting(long shipId, Vec3 direction, long startTick) {}

    private static final Map<ServerLevel, List<Waiting>> WAITING = new WeakHashMap<>();
    private static final net.minecraft.resources.ResourceLocation GLIDE_HOOK =
            new net.minecraft.resources.ResourceLocation(net.camacraft.fullstop.FullStop.MODID, "piston_glide");
    private static boolean hooked;

    private static synchronized void ensureHook() {
        if (hooked) return;
        ShipForces.registerHook(GLIDE_HOOK, ShipPistons::glide);
        hooked = true;
    }

    /**
     * Valkyrien Skies' physics thread, every step, every ship: a gliding ship is given, this step, the speed along the
     * piston that brings it to exactly one block - the head's speed at first, slowing as it closes - and, once there (or
     * out of time), what it had along it before the push, so it stops with the head. Never more than {@link #PUSH_MPS}
     * of change in a step.
     */
    private static void glide(net.camacraft.camafy.compat.vs.PhysBody body, double dt) {
        Glide g = GLIDES.get(body.id());
        if (g == null) return;
        if (body.isStatic() || !(dt > 0.0)) {
            GLIDES.remove(body.id(), g);
            return;
        }
        org.joml.Vector3d p = body.position(new org.joml.Vector3d());
        org.joml.Vector3d v = body.velocity(new org.joml.Vector3d());
        double scale = body.scale();
        double mass = body.mass() * (scale > 0.0 && Double.isFinite(scale) ? scale * scale * scale : 1.0);
        if (!(mass > 0.0) || !Double.isFinite(mass)) {
            GLIDES.remove(body.id(), g);
            return;
        }
        double along = v.x * g.dx + v.y * g.dy + v.z * g.dz;
        if (g.from == null) {
            g.from = new org.joml.Vector3d(p);
            g.before = along;
        }
        double left = 1.0 - ((p.x - g.from.x) * g.dx + (p.y - g.from.y) * g.dy + (p.z - g.from.z) * g.dz);
        boolean done = left <= ARRIVED || ++g.steps > GLIDE_STEPS;
        double wanted = done ? g.before : g.before + Math.min(PUSH_MPS, Math.max(0.0, left) * GLIDE_GAIN);
        double change = Math.max(-PUSH_MPS, Math.min(PUSH_MPS, wanted - along));
        if (Math.abs(change) > 1.0e-4) {
            org.joml.Vector3d force = new org.joml.Vector3d(g.dx, g.dy, g.dz).mul(mass * change / dt);
            if (Double.isFinite(force.x) && Double.isFinite(force.y) && Double.isFinite(force.z)) body.applyForce(force);
        }
        if (done) GLIDES.remove(body.id(), g);
    }
    /**
     * A ship being moved by a piston: the way, and until when the rest of a group rides along. Pistons of a group
     * don't all extend in the same tick (a neighbour's update wakes the others a tick or two later); they move the
     * ship one block together, not a block each.
     */
    private record Moving(Vec3 direction, long until) {}

    /** The ships being moved, per level (ship id -> its move). */
    private static final Map<ServerLevel, Map<Long, Moving>> MOVED = new WeakHashMap<>();
    /** How long (ticks) after a head stops the rest of its group still rides along with the move. */
    static final int RIDE_ALONG_TICKS = 6;

    private ShipPistons() {
    }

    @SubscribeEvent
    public static void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!ShipCompat.active() || !SERVER.pistonsPushShips.get() || !Ships.available()) return;
        BlockPos pos = event.getPos();
        Direction facing = event.getDirection();
        if (facing == null) return;
        boolean extend = event.getPistonMoveType() == PistonEvent.PistonMoveType.EXTEND;
        BlockState state = level.getBlockState(pos);
        boolean sticky = state.is(Blocks.STICKY_PISTON);
        if (!extend && (!sticky || !SERVER.pistonStickyPulls.get())) return;
        long now = level.getGameTime();
        ShipRef own = Ships.managing(level, pos);
        if (own == null) {
            worldPiston(level, event, pos, facing, extend, now);
        } else if (SERVER.pistonShipReaction.get()) {
            shipPiston(level, event, own, pos, facing, extend, now);
        }
    }

    /** A piston in the world: the cells its head (and what it pushes) will occupy, and the ships in them. */
    private static void worldPiston(ServerLevel level, PistonEvent.Pre event, BlockPos pos, Direction facing, boolean extend, long now) {
        List<AABB> cells = new ArrayList<>();
        Vec3 direction = Vec3.atLowerCornerOf(facing.getNormal());
        if (extend) {
            cells.add(new AABB(pos.relative(facing)));
            PistonStructureResolver resolver = event.getStructureHelper();
            if (resolver != null && resolver.resolve()) {
                for (BlockPos pushed : resolver.getToPush()) cells.add(new AABB(pushed.relative(facing)));
            }
        } else {
            // a sticky head coming back: whatever touches the block in front of it comes too
            cells.add(new AABB(pos.relative(facing, 2)));
            direction = direction.scale(-1.0);
        }
        AABB all = cells.get(0);
        for (AABB cell : cells) all = all.minmax(cell);
        for (ShipRef ship : Ships.intersecting(level, all.inflate(0.5))) {
            boolean touched = false;
            for (AABB cell : cells) {
                if (ship.touchesBlock(cell)) {
                    touched = true;
                    break;
                }
            }
            if (!touched) continue;
            int pistons = worldPistonsOn(level, ship, facing, extend);
            move(level, event, ship, direction, pistons, now, extend ? 0 : EXTENSION_TICKS, !extend,
                    (extend ? "pushed" : "pulled") + " by the piston at " + pos.toShortString());
        }
    }

    /**
     * A piston that is part of a ship: where its head goes in the world, and what is there. The terrain can't
     * move, so the piston's own ship does; another ship is pushed as by a world piston.
     */
    private static void shipPiston(ServerLevel level, PistonEvent.Pre event, ShipRef own, BlockPos pos, Direction facing, boolean extend, long now) {
        BlockPos cell = extend ? pos.relative(facing) : pos.relative(facing, 2);
        AABB worldBox = own.shipBoxToWorld(new AABB(cell));
        Vec3 worldDir = own.dirToWorld(Vec3.atLowerCornerOf(facing.getNormal()));
        if (worldBox == null || worldDir == null) return;
        if (terrainIn(level, worldBox)) {
            // pushing against the terrain shoves the ship back; a sticky head leaving it pulls the ship after it
            Vec3 direction = extend ? worldDir.scale(-1.0) : worldDir;
            int pistons = shipPistonsAgainstTerrain(level, own, facing, extend);
            // its own head goes back into it as the ship comes forward: nothing in the way, no wait
            move(level, event, own, direction, pistons, now, 0, !extend,
                    (extend ? "pushing off" : "pulling toward") + " the terrain with its piston at " + pos.toShortString());
            return;
        }
        for (ShipRef other : Ships.intersecting(level, worldBox.inflate(0.5))) {
            if (other.id() == own.id() || !other.touchesBlock(worldBox)) continue;
            Vec3 direction = extend ? worldDir : worldDir.scale(-1.0);
            move(level, event, other, direction, 1, now, extend ? 0 : EXTENSION_TICKS, !extend,
                    (extend ? "pushed" : "pulled") + " by ship " + own.id() + "'s piston at " + pos.toShortString());
        }
    }

    /**
     * Moves {@code ship} one block along {@code direction} with {@code pistons} pistons behind the push - or cancels
     * the piston if they can't move it. One block per ship per tick: the rest of a group changes nothing.
     */
    private static void move(ServerLevel level, PistonEvent.Pre event, ShipRef ship, Vec3 direction, int pistons, long now, int delay,
                             boolean pull, String what) {
        double mass = ship.physicalMass();
        if (ship.isStatic() || !(mass > 0.0) || !Double.isFinite(mass)) {
            // a push can't go through an immovable ship; a pull just comes back without it, as a sticky piston
            // leaves an immovable block where it is
            if (!pull) event.setCanceled(true);
            debug("ship {} is pinned: the piston can't move it", ship.id());
            return;
        }
        Map<Long, Moving> moved = MOVED.computeIfAbsent(level, l -> new HashMap<>());
        Moving under = moved.get(ship.id());
        if (under != null && under.until() >= now && under.direction().dot(direction) > 0.99) {
            // the rest of a group: it extends with the move already under way and pushes nothing more
            return;
        }
        double capacity = Math.max(1, pistons) * SERVER.pistonPushMassKg.get();
        if (mass > capacity) {
            if (!pull) event.setCanceled(true);
            debug("ship {} ({} kg) is too heavy for {} piston(s) ({} kg): {}", ship.id(), Math.round(mass),
                    Math.max(1, pistons), Math.round(capacity), pull ? "the piston comes back without it" : "the piston doesn't extend");
            return;
        }
        moved.put(ship.id(), new Moving(direction, now + delay + GLIDE_TICKS + RIDE_ALONG_TICKS));
        ensureHook();
        if (delay <= 0) {
            GLIDES.put(ship.id(), new Glide(direction));
        } else {
            WAITING.computeIfAbsent(level, l -> new ArrayList<>()).add(new Waiting(ship.id(), direction, now + delay));
        }
        debug("ship {} ({} kg) {}: one block along {} with {} piston(s)", ship.id(), Math.round(mass), what, direction, Math.max(1, pistons));
    }

    /** A pull whose head has cleared starts gliding; the bookkeeping of moves under way is cleared as they end. */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        List<Waiting> waiting = WAITING.get(level);
        if (waiting != null && !waiting.isEmpty()) {
            Iterator<Waiting> it = waiting.iterator();
            while (it.hasNext()) {
                Waiting w = it.next();
                if (w.startTick() > now) continue;
                it.remove();
                ShipRef ship = Ships.byId(level, w.shipId());
                if (ship != null && !ship.isStatic()) GLIDES.put(w.shipId(), new Glide(w.direction()));
            }
        }
        Map<Long, Moving> moved = MOVED.get(level);
        if (moved != null) moved.values().removeIf(move -> move.until() < now);
    }

    /**
     * How many pistons in the world are pushing (or, retracting, pulling) {@code ship} along {@code facing}: the
     * piston bases facing that way within a block of the hull whose head cell touches it and that are powered or
     * extended. They extend together, so they move the ship together.
     */
    private static int worldPistonsOn(ServerLevel level, ShipRef ship, Direction facing, boolean extend) {
        AABB bounds = ship.worldAabb();
        if (bounds == null) return 1;
        AABB around = bounds.inflate(1.5);
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(around.minX, around.minY, around.minZ),
                BlockPos.containing(around.maxX, around.maxY, around.maxZ))) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof PistonBaseBlock) || state.getValue(BlockStateProperties.FACING) != facing) continue;
            if (!extend && !state.is(Blocks.STICKY_PISTON)) continue;
            if (Ships.inShipyard(level, pos)) continue;
            boolean active = state.getValue(BlockStateProperties.EXTENDED) || level.hasNeighborSignal(pos) || level.hasNeighborSignal(pos.above());
            if (!active) continue;
            AABB cell = new AABB(extend ? pos.relative(facing) : pos.relative(facing, 2));
            if (ship.touchesBlock(cell)) count++;
        }
        return Math.max(1, count);
    }

    /** How many of {@code ship}'s own pistons facing {@code facing} (ship space) are pushing against the terrain. */
    private static int shipPistonsAgainstTerrain(ServerLevel level, ShipRef ship, Direction facing, boolean extend) {
        int[] count = {0};
        ship.forEachBlock(state -> state.getBlock() instanceof PistonBaseBlock && state.getValue(BlockStateProperties.FACING) == facing,
                (pos, state) -> {
                    if (!extend && !state.is(Blocks.STICKY_PISTON)) return;
                    boolean active = state.getValue(BlockStateProperties.EXTENDED) || level.hasNeighborSignal(pos) || level.hasNeighborSignal(pos.above());
                    if (!active) return;
                    AABB box = ship.shipBoxToWorld(new AABB(extend ? pos.relative(facing) : pos.relative(facing, 2)));
                    if (box != null && terrainIn(level, box)) count[0]++;
                });
        return Math.max(1, count[0]);
    }

    /** Whether any solid world block (not a ship's) has collision inside {@code worldBox}. */
    private static boolean terrainIn(ServerLevel level, AABB worldBox) {
        AABB box = worldBox.deflate(0.05);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (Ships.inShipyard(level, pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (!state.getCollisionShape(level, pos).isEmpty()) return true;
        }
        return false;
    }

    /** Forgets the ships of a level that is gone (tests). */
    static void clear(ServerLevel level) {
        WAITING.remove(level);
        MOVED.remove(level);
    }

    private static void debug(String message, Object... args) {
        if (SERVER.debugShipCollisionLog.get()) LOGGER.info("[FullStop] piston: " + message, args);
    }
}
