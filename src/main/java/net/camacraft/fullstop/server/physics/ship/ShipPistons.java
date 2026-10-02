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
 *   block in front of its head comes back one block the same way, with the head from its first moment (Camafy 0.1.19
 *   gives a moving piston no physics shape, so no stale head is in the way); a ship too heavy to pull is left, the
 *   piston retracting as from any immovable block.</li>
 *   <li><b>A piston on a ship pushes its ship off what it pushes against</b> ({@code pistonShipReaction}): a
 *   head extending into the terrain - or pushing a block of the ship into it - shoves the ship a block the other way
 *   (it pushed itself off), and a sticky head retracting from the terrain pulls the ship back toward it, setting it
 *   down {@link #PULL_SHORT_TERRAIN} above the ground. Against another ship, the other ship is pushed (as by a world
 *   piston).</li>
 *   <li><b>Slime springs</b> ({@code slimeBounce}): a slime block a piston pushes into the terrain or a ship (on a
 *   ship's piston, or a world piston's) sends the ship off at {@code slimeRestitution} of the head's speed instead of
 *   stopping it a block on: a slime launcher.</li>
 *   <li><b>Stopping</b>: a move ends with a stop that takes out all its speed, and the ship is held there until the blocks
 *   the piston moved (the head, what it pushed, a retracting base: moving blocks with no physics shape until then) are
 *   solid. A ship's own sticky piston pulling it down onto the ground had a base with no shape under it, and its pull,
 *   stopped short of the speed it had caught up with, went on through the ground.</li>
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
    /** The head's travel time (s): vanilla moves it linearly, a block over {@link #EXTENSION_TICKS} ticks. */
    static final double STROKE_SECONDS = EXTENSION_TICKS * 0.05;
    /**
     * The longest (ticks) a ship takes to its block (see {@link #glide}); it normally arrives with the head, in its two
     * ticks, and floor friction or something in the way can't make it creep on for ever. Also how long the rest of a
     * group rides along ({@link #RIDE_ALONG_TICKS} more).
     */
    static final int GLIDE_TICKS = 10;
    /** The same in physics steps (Valkyrien Skies steps at 60 Hz). */
    static final int GLIDE_STEPS = GLIDE_TICKS * 3;
    /**
     * The fastest a ship may go along the piston while catching up with where the head says it should be (m/s): a
     * pull's first physics steps can still find the retracted head in Valkyrien Skies' picture of the world, which
     * learns the head is gone only with the server tick's block updates.
     */
    static final double CATCH_UP_MPS = 2.5 * PUSH_MPS;
    /** Close enough (blocks): the ship has arrived. A glide stops at most this short of its block, never past it. */
    static final double ARRIVED = 0.02;
    /**
     * How far past its block a pushed ship is set down (blocks). The head (or, for a ship's own piston, the head against
     * the wall it pushes off) turns solid when the move ends, and a hull left even a few millimetres inside it was kicked
     * away by Valkyrien Skies (Krunch Classic: 5 cm further). A pull needs none: it stops at or short of its block, never
     * inside the piston base.
     */
    static final double PUSH_CLEARANCE = 0.025;
    /**
     * How far short of a block a pull aims (blocks): a ship flush against the head comes to rest that far from the piston
     * base, never inside it (the base turns solid when the move ends, and Valkyrien Skies kicked a raft left a few
     * millimetres inside it a quarter of a block away); one a push set down {@link #PUSH_CLEARANCE} past its block comes
     * back to 0.035 short of where it started.
     */
    static final double PULL_SHORT = 0.01;
    /**
     * How far short of the terrain a ship's own sticky piston pulls the ship (blocks). Its piston base is a moving block
     * with no physics shape while it retracts, and the ground can't be kicked out of the way: a hull that ended even a
     * centimetre into the ground before the base turned solid was kicked by Valkyrien Skies, or (the owner's report) went
     * on through the ground. It is set down this far above it and drops the rest once the base is solid.
     */
    static final double PULL_SHORT_TERRAIN = 0.05;
    /**
     * The largest change of speed (m/s) a stop or the hold after it may ask for in one step. The catching-up steps are
     * limited to {@link #CATCH_UP_MPS}; a stop is not: a ship that caught up at speed and could only be slowed by that much
     * kept the rest when the glide ended - a pull toward the terrain went on through the ground.
     */
    static final double STOP_MPS = 4.0 * CATCH_UP_MPS;
    /**
     * After the stop the ship is held where it stopped (its speed along the piston as before the move) until every block
     * the piston moved has turned solid (the head, the blocks it pushed, a retracting base): until then those are moving
     * blocks with no physics shape, and a ship left to gravity sank into them, or with them into the ground. These are the
     * physics steps it is held after the server has the blocks solid (Valkyrien Skies takes the change at its next frame),
     * the steps a glide no block waits for is held, and the longest any is held (four seconds).
     */
    static final int SETTLE_AFTER_SOLID_STEPS = 4, SETTLE_UNHELD_STEPS = 3, SETTLE_MAX_STEPS = 240;
    /**
     * A ship a piston is moving meets what it is pushed against, or the head, at about the head's speed: contacts of
     * such a ship no faster than this (m/s) are the push itself, never a crash (no blocks broken, no hit), until
     * the move is over.
     */
    public static final double PUSH_CONTACT_MPS = CATCH_UP_MPS + PUSH_MPS;

    /**
     * A ship gliding one block along a piston. Steered on Valkyrien Skies' physics thread every step ({@link #glide})
     * from the velocity and position that step has: from the server tick, a correction made on a stale frame was made
     * again on the next tick, and a pull carried its raft eighteen blocks. Created by the server thread; from then on
     * only the physics thread touches it.
     */
    private static final class Glide {
        final double dx, dy, dz;
        int steps;
        /** Physics time (s) since the glide began: where the head is. */
        double elapsed;
        /** The travel the last step was planned to give (blocks along), and the steps that got less than half of it. */
        double asked = Double.NaN;
        int heldBack;
        double lastTravelled;
        /** The last step's speed along the piston beyond what the ship had before (m/s), its length (s), and the head's speed in it. */
        double lastRel = Double.NaN, lastDt, lastHeadSpeed;
        /**
         * How much of a step's change of speed shows in that same step's travel ({@link #glide}): 1 when a step moves at
         * the speed it ends with (PhysX), about half when the backend sub-steps (Krunch Classic: 0.525 at its 20
         * sub-steps). Measured as the glide goes; it starts from what the last glide measured.
         */
        double response = 1.0;
        /**
         * The stop has been asked for: the next step reads where the ship came to rest. The server thread reads it too:
         * from here on the blocks the piston moved may turn solid ({@link #holdsMovingBlock}).
         */
        volatile boolean stopping;
        /** Stopped and held where it stopped until the blocks the piston moved are solid ({@link #SETTLE_AFTER_SOLID_STEPS}). */
        boolean settling;
        int settleSteps;
        int stepsAfterSolid;
        /**
         * Extra speed (m/s) along the move the ship leaves with instead of stopping: slime between the piston and what it
         * pushes springs the ship away (restitution times the head's speed), a slime launcher.
         */
        final double launch;
        /** The level whose piston moves the ship, and the cells (the piston's coordinates) whose blocks it waits on. Server thread. */
        final ServerLevel level;
        final List<BlockPos> cells = new ArrayList<>();
        /** A hold waits for this glide (server thread sets it). */
        volatile boolean held;
        /** Every block the glide waits on has turned solid (server thread sets it, the physics thread reads it). */
        volatile boolean solid;
        /** The last step was held back (a contact, not the backend, decided how far it went). */
        boolean lastHeld;
        /**
         * Where the glide measures from: a fixed point of the hull (ship space) and where it was in the world when the glide
         * began. Not the centre of mass, which moves when a block is added or removed mid-stroke (the head itself, the
         * redstone that powers the piston).
         */
        @org.jetbrains.annotations.Nullable
        org.joml.Vector3d anchor;
        @org.jetbrains.annotations.Nullable
        org.joml.Vector3d from;
        double before;

        /** How far the ship goes (blocks): a block, or its share of one when two ships push off each other. */
        final double distance;

        Glide(ServerLevel level, Vec3 direction, double distance, double launch) {
            this.level = level;
            this.dx = direction.x;
            this.dy = direction.y;
            this.dz = direction.z;
            this.distance = distance;
            this.launch = launch;
        }
    }

    /**
     * How a finished glide went (tests, debugging): the physics time it took and its steps, the distance it covered
     * along the piston (blocks), whether it arrived (or ran out of steps), how many of its steps found the ship held
     * back (it moved less than half of what the step asked: something in the way), and the backend's response it measured
     * (1 PhysX, about 0.5 Krunch Classic: see {@link #glide}).
     */
    public record GlideReport(long shipId, double seconds, int steps, double travelled, boolean arrived, int heldBackSteps,
                              double response) {}

    /** The backend's response as the last glide measured it: where the next glide starts (one backend per server). */
    private static volatile double lastResponse = 1.0;
    /** A step's change of speed (m/s) big enough to read the backend's response from. */
    private static final double RESPONSE_MIN_CHANGE = 1.0;
    /** The least response believed (a step that shows almost none of its change is a measurement gone wrong). */
    private static final double MIN_RESPONSE = 0.3;

    private static final Map<Long, GlideReport> LAST_GLIDES = new java.util.concurrent.ConcurrentHashMap<>();

    /** The last finished glide of {@code shipId}, or null. */
    @org.jetbrains.annotations.Nullable
    public static GlideReport lastGlide(long shipId) {
        return LAST_GLIDES.get(shipId);
    }

    /** The glides under way, by ship id (any level: ship ids are unique). */
    private static final Map<Long, Glide> GLIDES = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * A move that starts some ticks after its piston fires. None does now: pulls waited for the retracting head to clear
     * the cell while Valkyrien Skies took a moving piston for a solid block (Camafy 0.1.19 gives it no physics shape).
     */
    private record Waiting(long shipId, Vec3 direction, double distance, long startTick) {}

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
     * Valkyrien Skies' physics thread, every step, every ship: a gliding ship moves with the head. The head moves a block
     * in {@link #STROKE_SECONDS}, linearly (vanilla's progress), and stops dead: each step the ship is given the head's
     * speed in that step, plus what closes any distance it fell behind by (up to {@link #CATCH_UP_MPS}), never more than
     * it can still stop from short of the block, and never backward (a ship ahead of the head slows down, it is not pushed
     * back toward what the piston pushes against). Then it is stopped (given back what it had along the piston before)
     * so that it comes to rest exactly a block on, and the next step reads where it did.
     *
     * <p><b>Backends.</b> PhysX moves a step at the speed the step ends with; Krunch Classic, VS's default, sub-steps (20
     * by default), so a step's change of speed shows only about half way in that step's own travel ({@link
     * Glide#response}, measured as the glide goes). Holding the head's speed from the first step keeps the ship half a
     * step's travel behind the head on Krunch, and the stop, asked for that half step early, coasts the rest: it lands
     * on the block on either backend. Steering the position each step instead zigzagged the speed on Krunch, and a stop
     * asked for at the block coasted a tenth of a block past it: a pull then ended inside the piston base, which Valkyrien
     * Skies kicked the raft back out of, half a block, as the base turned solid.</p>
     *
     * <p>Travel is measured at a fixed point of the hull ({@link Glide#anchor}). The mass is the physical body's
     * ({@code PhysBody.mass()} is already the scaled ship's).</p>
     */
    private static void glide(net.camacraft.camafy.compat.vs.PhysBody body, double dt) {
        Glide g = GLIDES.get(body.id());
        if (g == null) return;
        if (body.isStatic() || !(dt > 0.0)) {
            GLIDES.remove(body.id(), g);
            return;
        }
        org.joml.Vector3d v = body.velocity(new org.joml.Vector3d());
        double mass = body.mass();
        if (!(mass > 0.0) || !Double.isFinite(mass)) {
            GLIDES.remove(body.id(), g);
            return;
        }
        double along = v.x * g.dx + v.y * g.dy + v.z * g.dz;
        if (g.anchor == null) {
            org.joml.Vector3d p = body.position(new org.joml.Vector3d());
            g.from = new org.joml.Vector3d(p);
            g.anchor = body.toShip(new org.joml.Vector3d(p));
            g.before = along;
            g.response = lastResponse;
        }
        org.joml.Vector3d at = body.toWorld(new org.joml.Vector3d(g.anchor));
        double travelled = (at.x - g.from.x) * g.dx + (at.y - g.from.y) * g.dy + (at.z - g.from.z) * g.dz;
        if (!Double.isFinite(travelled)) {
            GLIDES.remove(body.id(), g);
            return;
        }
        double rel = along - g.before;
        double prevDt = g.lastDt, prevHeadSpeed = g.lastHeadSpeed;
        if (!Double.isNaN(g.lastRel) && prevDt > 0.0) {
            double moved = travelled - g.lastTravelled;
            boolean held = !Double.isNaN(g.asked) && g.asked > 1.0e-3 && moved < 0.5 * g.asked;
            if (held) g.heldBack++;
            // how much of the last step's change of speed showed in its own travel: never read from a step something held
            // back, or the one after it (a pull's first steps meet the head Valkyrien Skies' world still has, until the
            // game's next frame reaches it, and a contact, not the backend, decides how far those go)
            double changed = rel - g.lastRel;
            boolean readable = !held && !g.lastHeld;
            g.lastHeld = held;
            if (readable && Math.abs(changed) > RESPONSE_MIN_CHANGE) {
                double share = (moved / prevDt - g.lastRel) / changed;
                if (Double.isFinite(share)) {
                    g.response = Math.max(MIN_RESPONSE, Math.min(1.0, share));
                    lastResponse = g.response;
                }
            }
        }
        g.lastTravelled = travelled;
        g.lastRel = rel;
        g.lastDt = dt;
        if (g.settling || g.stopping) {
            if (!g.settling) {
                // stopped last step: this is where the move ended. Launched off slime, it flies on: nothing to hold.
                if (g.launch > 0.0) {
                    finish(body, g, travelled);
                    return;
                }
                g.settling = true;
            }
            g.settleSteps++;
            if (g.solid) g.stepsAfterSolid++;
            boolean done = g.held ? g.solid && g.stepsAfterSolid >= SETTLE_AFTER_SOLID_STEPS : g.settleSteps >= SETTLE_UNHELD_STEPS;
            if (done || g.settleSteps >= SETTLE_MAX_STEPS) {
                finish(body, g, travelled);
                return;
            }
            // held where it stopped, its speed along the piston as before the move (against gravity and anything else),
            // until what the piston moved is solid: a ship let go onto a moving block with no physics shape sank into it
            push(body, g, mass, g.before - along, dt, STOP_MPS);
            return;
        }
        double headWas = Math.min(1.0, g.elapsed / STROKE_SECONDS) * g.distance;
        g.elapsed += dt;
        double headSpeed = (Math.min(1.0, g.elapsed / STROKE_SECONDS) * g.distance - headWas) / dt;
        g.lastHeadSpeed = headSpeed;
        // the part of a step's change of speed that only shows in the next step's travel
        double lag = 1.0 - g.response;
        double remaining = g.distance - travelled;
        double wanted;
        // stopped now, the ship still goes this far in this step
        if (remaining - lag * Math.max(0.0, rel) * dt <= ARRIVED || ++g.steps > GLIDE_STEPS) {
            // the stop: the speed it had before, or (slime) that much faster away
            wanted = g.before + g.launch;
            g.stopping = true;
            g.asked = Double.NaN;
        } else {
            // where the ship would be had it moved with the head: half a step's travel behind it on a sub-stepping backend
            double behind = headWas - lag * prevHeadSpeed * prevDt - travelled;
            double rel1 = headSpeed + Math.max(-headSpeed, Math.min(CATCH_UP_MPS, behind / dt));
            // never more than it can still stop from short of the block (this step's travel and the next step's coast)
            rel1 = Math.max(0.0, Math.min(rel1, remaining / dt - lag * rel));
            wanted = g.before + rel1;
            g.asked = (lag * rel + g.response * rel1) * dt;
        }
        // a stop may take out all the speed the catching up gave (a ship held back by a stale head catches up fast), the
        // steps on the way are limited to the catching up
        push(body, g, mass, wanted - along, dt, g.stopping ? STOP_MPS : CATCH_UP_MPS);
    }

    /** Changes the ship's speed along the glide by {@code change} (m/s, within {@code limit}) over this step. */
    private static void push(net.camacraft.camafy.compat.vs.PhysBody body, Glide g, double mass, double change, double dt, double limit) {
        change = Math.max(-limit, Math.min(limit, change));
        if (Math.abs(change) <= 1.0e-4) return;
        org.joml.Vector3d force = new org.joml.Vector3d(g.dx, g.dy, g.dz).mul(mass * change / dt);
        if (Double.isFinite(force.x) && Double.isFinite(force.y) && Double.isFinite(force.z)) body.applyForce(force);
    }

    /** The glide is over: where the move ended. */
    private static void finish(net.camacraft.camafy.compat.vs.PhysBody body, Glide g, double travelled) {
        GLIDES.remove(body.id(), g);
        LAST_GLIDES.put(body.id(), new GlideReport(body.id(), g.elapsed, g.steps, travelled,
                Math.abs(g.distance - travelled) <= ARRIVED * 5.0, g.heldBack, g.response));
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

    /**
     * Piston heads, and the blocks they push, that wait (still moving, without a physics shape) for the ships they push
     * to glide clear before they turn solid: their cells per level (the piston's own coordinates: a ship's piston's are
     * in its shipyard), with those ships and the tick the wait gives up at. See {@link #holdsMovingBlock}.
     */
    private record Hold(long[] ships, long until) {}

    private static final Map<ServerLevel, Map<BlockPos, Hold>> HOLDS = new WeakHashMap<>();
    /** The longest (ticks) a head waits for its ship: a glide that never ends can't freeze a piston. */
    static final int HOLD_TICKS = 40;
    /** The ships the piston event being handled set gliding (server thread). */
    private static final List<Long> STARTED = new ArrayList<>();

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
        STARTED.clear();
        try {
            if (own == null) {
                worldPiston(level, event, pos, facing, extend, now);
            } else if (SERVER.pistonShipReaction.get()) {
                shipPiston(level, event, own, pos, facing, extend, now);
            }
            if (!STARTED.isEmpty() && !event.isCanceled()) {
                if (extend) {
                    holdHead(level, event, pos, facing, now);
                } else {
                    holdBase(level, pos, facing, now);
                }
            }
        } finally {
            STARTED.clear();
        }
    }

    /**
     * An extending head that pushes a ship (or pushes its own ship off something) waits for the ship: its cell and the
     * cells of the blocks it pushes are held moving until every ship the push set gliding has stopped where it ends (or
     * {@link #HOLD_TICKS} passed), and the ship is held there until they are solid.
     */
    private static void holdHead(ServerLevel level, PistonEvent.Pre event, BlockPos pos, Direction facing, long now) {
        List<BlockPos> cells = new ArrayList<>();
        cells.add(pos.relative(facing).immutable());
        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver != null && resolver.resolve()) {
            for (BlockPos pushed : resolver.getToPush()) cells.add(pushed.relative(facing).immutable());
        }
        hold(level, cells, now);
    }

    /**
     * A sticky head retracting with a ship in tow waits for it the same way: the piston's base (a moving block while the
     * head goes back into it) and the head's cell stay moving, without a physics shape, until every ship the pull set
     * gliding has stopped short of the base (or {@link #HOLD_TICKS} passed). A base that turned solid while the ship was
     * still closing on it stopped the ship dead against it and Valkyrien Skies kicked it back, a quarter of a block.
     */
    private static void holdBase(ServerLevel level, BlockPos pos, Direction facing, long now) {
        hold(level, List.of(pos.immutable(), pos.relative(facing).immutable()), now);
    }

    /** Holds {@code cells} for the ships the piston event being handled set gliding, and tells those glides to wait on them. */
    private static void hold(ServerLevel level, List<BlockPos> cells, long now) {
        long[] ships = STARTED.stream().mapToLong(Long::longValue).distinct().toArray();
        Map<BlockPos, Hold> holds = HOLDS.computeIfAbsent(level, l -> new HashMap<>());
        for (BlockPos cell : cells) {
            Hold was = holds.get(cell);
            holds.put(cell, new Hold(was == null ? ships : java.util.stream.LongStream.concat(
                    java.util.Arrays.stream(was.ships()), java.util.Arrays.stream(ships)).distinct().toArray(), now + HOLD_TICKS));
        }
        for (long id : ships) {
            Glide g = GLIDES.get(id);
            if (g == null) continue;
            g.cells.addAll(cells);
            g.held = true;
        }
    }

    /**
     * Mixin hook ({@code PistonMovingBlockEntity.tick}): whether the moving block at {@code pos} waits this tick, still
     * moving, for a ship it pushes (or a retracting base, for a ship it pulls) to glide clear. A moving piston has no
     * physics shape (Camafy), the finished block has: turned solid inside the ship's hull, or inside the wall a ship's own
     * piston pushes against, it flung the ship in any direction. That happened whenever the server ran ticks back to back (catching up after a lag spike) and
     * finished the two-tick stroke before a single physics step had moved the ship. It waits until the ship's stop has been
     * asked for (the ship is where it ends; it is held there until the block is solid, {@link #glide}). Server side only.
     */
    public static boolean holdsMovingBlock(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return false;
        Map<BlockPos, Hold> holds = HOLDS.get(server);
        if (holds == null || holds.isEmpty()) return false;
        Hold hold = holds.get(pos);
        if (hold == null) return false;
        if (server.getGameTime() <= hold.until()) {
            for (long ship : hold.ships()) {
                Glide g = GLIDES.get(ship);
                if (g != null && !g.stopping) return true;
            }
        }
        holds.remove(pos);
        return false;
    }

    /**
     * The cells a piston's move puts something in, in the piston's coordinates, each with the block that ends up there:
     * extending, the head's cell (nothing: the head) and each pushed block's next cell; a sticky head retracting, the cell
     * in front of it, which it lets go of (or pulls along).
     */
    private record Reach(List<BlockPos> cells, List<BlockState> movers) {}

    private static Reach reach(ServerLevel level, PistonEvent.Pre event, BlockPos pos, Direction facing, boolean extend) {
        List<BlockPos> cells = new ArrayList<>();
        List<BlockState> movers = new ArrayList<>();
        if (extend) {
            cells.add(pos.relative(facing).immutable());
            movers.add(null);
            PistonStructureResolver resolver = event.getStructureHelper();
            if (resolver != null && resolver.resolve()) {
                for (BlockPos pushed : resolver.getToPush()) {
                    cells.add(pushed.relative(facing).immutable());
                    movers.add(level.getBlockState(pushed));
                }
            }
        } else {
            cells.add(pos.relative(facing, 2).immutable());
            movers.add(null);
        }
        return new Reach(cells, movers);
    }

    /**
     * Whether the block moving into a cell is slime ({@code fullstop:bouncy}, with {@code slimeBounce} on): pushed into
     * something, it springs back off it instead of stopping (a slime launcher), at {@code slimeRestitution} of the head's
     * speed.
     */
    private static boolean bouncy(@org.jetbrains.annotations.Nullable BlockState mover) {
        return mover != null && SERVER.slimeBounce.get() && mover.is(net.camacraft.fullstop.common.physics.rules.FullStopTags.BOUNCY);
    }

    /** The speed (m/s) a slime launch sends a ship off with: the head's, given back at the slime's restitution. */
    private static double slimeLaunch() {
        return SERVER.slimeRestitution.get() * PUSH_MPS;
    }

    /** A piston in the world: the cells its head (and what it pushes) will occupy, and the ships in them. */
    private static void worldPiston(ServerLevel level, PistonEvent.Pre event, BlockPos pos, Direction facing, boolean extend, long now) {
        Reach reach = reach(level, event, pos, facing, extend);
        Vec3 direction = Vec3.atLowerCornerOf(facing.getNormal());
        // a sticky head coming back: whatever touches the block in front of it comes too
        if (!extend) direction = direction.scale(-1.0);
        AABB all = new AABB(reach.cells().get(0));
        for (BlockPos cell : reach.cells()) all = all.minmax(new AABB(cell));
        for (ShipRef ship : Ships.intersecting(level, all.inflate(0.5))) {
            boolean touched = false;
            boolean slime = false;
            for (int i = 0; i < reach.cells().size(); i++) {
                if (!ship.touchesBlock(new AABB(reach.cells().get(i)))) continue;
                touched = true;
                if (extend && bouncy(reach.movers().get(i))) slime = true;
            }
            if (!touched) continue;
            int pistons = worldPistonsOn(level, ship, facing, extend);
            // a pull starts with the head, not after it: the glide follows the head's stroke and catches up if Valkyrien
            // Skies' world still has the head in the way for the rest of this tick
            move(level, event, ship, direction, pistons, now, !extend, 1.0, slime ? slimeLaunch() : 0.0, PULL_SHORT,
                    (extend ? slime ? "launched off slime" : "pushed" : "pulled") + " by the piston at " + pos.toShortString());
        }
    }

    /**
     * A piston that is part of a ship: where its head, and what it pushes, go in the world, and what is there. The
     * terrain can't move, so the piston's own ship does; another ship is pushed as by a world piston. A slime block it
     * pushes into either springs its ship (or both ships) off it.
     */
    private static void shipPiston(ServerLevel level, PistonEvent.Pre event, ShipRef own, BlockPos pos, Direction facing, boolean extend, long now) {
        Vec3 worldDir = own.dirToWorld(Vec3.atLowerCornerOf(facing.getNormal()));
        if (worldDir == null) return;
        Reach reach = reach(level, event, pos, facing, extend);
        List<AABB> boxes = new ArrayList<>();
        boolean terrain = false;
        boolean slimeOnTerrain = false;
        for (int i = 0; i < reach.cells().size(); i++) {
            AABB worldBox = own.shipBoxToWorld(new AABB(reach.cells().get(i)));
            boxes.add(worldBox);
            if (worldBox == null || !terrainIn(level, worldBox)) continue;
            terrain = true;
            if (extend && bouncy(reach.movers().get(i))) slimeOnTerrain = true;
        }
        if (terrain) {
            // pushing against the terrain (with its head or with what it pushes) shoves the ship back; a sticky head
            // leaving it pulls the ship after it, down onto it, short of it by PULL_SHORT_TERRAIN
            Vec3 direction = extend ? worldDir.scale(-1.0) : worldDir;
            int pistons = shipPistonsAgainstTerrain(level, own, facing, extend);
            // its own head goes back into it as the ship comes forward: nothing in the way, no wait
            move(level, event, own, direction, pistons, now, !extend, 1.0, slimeOnTerrain ? slimeLaunch() : 0.0, PULL_SHORT_TERRAIN,
                    (extend ? slimeOnTerrain ? "springing off" : "pushing off" : "pulling toward") + " the terrain with its piston at "
                            + pos.toShortString());
            return;
        }
        Map<Long, Boolean> others = new java.util.LinkedHashMap<>();
        Map<Long, ShipRef> refs = new HashMap<>();
        for (int i = 0; i < boxes.size(); i++) {
            AABB worldBox = boxes.get(i);
            if (worldBox == null) continue;
            for (ShipRef other : Ships.intersecting(level, worldBox.inflate(0.5))) {
                if (other.id() == own.id() || !other.touchesBlock(worldBox)) continue;
                // a ship held to this one by joints is part of the same machine: the joint, not the piston, holds it
                if (Ships.joined(level, own.id(), other.id())) continue;
                refs.put(other.id(), other);
                others.merge(other.id(), extend && bouncy(reach.movers().get(i)), Boolean::logicalOr);
            }
        }
        for (Map.Entry<Long, Boolean> entry : others.entrySet()) {
            pushApart(level, event, own, refs.get(entry.getKey()), worldDir, extend, now, pos, entry.getValue());
        }
    }

    /**
     * A ship's piston against another ship: the two push off each other (or, a sticky head retracting, pull together)
     * by a block between them, each its share by the other's mass - two equal ships half a block each, a raft against a
     * big ship nearly the whole block, a pinned ship none (the other the whole block). The piston can move a pair whose
     * reduced mass (what the push has to accelerate) it could move alone; a heavier pair doesn't extend. Slime between
     * them springs them apart: they part at the slime's restitution of the head's speed, each its share.
     */
    private static void pushApart(ServerLevel level, PistonEvent.Pre event, ShipRef own, ShipRef other, Vec3 forward, boolean extend,
                                  long now, BlockPos pos, boolean slime) {
        double ma = own.isStatic() ? Double.POSITIVE_INFINITY : own.physicalMass();
        double mb = other.isStatic() ? Double.POSITIVE_INFINITY : other.physicalMass();
        boolean aMoves = Double.isFinite(ma) && ma > 0.0;
        boolean bMoves = Double.isFinite(mb) && mb > 0.0;
        if (!aMoves && !bMoves) {
            if (extend) event.setCanceled(true);
            debug("ships {} and {} are both pinned: the piston can't move them", own.id(), other.id());
            return;
        }
        double reduced = !aMoves ? mb : !bMoves ? ma : ma * mb / (ma + mb);
        double capacity = SERVER.pistonPushMassKg.get();
        if (reduced > capacity) {
            if (extend) event.setCanceled(true);
            debug("ships {} and {} ({} kg between them) are too heavy for the piston ({} kg)", own.id(), other.id(), Math.round(reduced), Math.round(capacity));
            return;
        }
        // the other ship's share of the block: all of it against a pinned ship, else the lighter goes further
        double shareOther = !aMoves ? 1.0 : !bMoves ? 0.0 : ma / (ma + mb);
        double shareOwn = 1.0 - shareOther;
        Vec3 otherWay = extend ? forward : forward.scale(-1.0);
        String what = (extend ? slime ? "sprung off" : "pushed off" : "pulled together with") + " ship %d by %s piston at " + pos.toShortString();
        double launch = extend && slime ? slimeLaunch() : 0.0;
        if (shareOther > 1.0e-3) {
            move(level, event, other, otherWay, 1, now, !extend, shareOther, launch * shareOther, PULL_SHORT,
                    String.format(java.util.Locale.ROOT, what, own.id(), "ship " + own.id() + "'s"));
        }
        if (shareOwn > 1.0e-3) {
            move(level, event, own, otherWay.scale(-1.0), 1, now, !extend, shareOwn, launch * shareOwn, PULL_SHORT,
                    String.format(java.util.Locale.ROOT, what, other.id(), "its own"));
        }
    }

    /**
     * Moves {@code ship} {@code distance} blocks (a block, or its share of one when two ships push off each other) along
     * {@code direction} with {@code pistons} pistons behind the push - or cancels the piston if they can't move it. One
     * block per ship per tick: the rest of a group changes nothing. A push ends {@link #PUSH_CLEARANCE} past its block, a
     * pull {@code pullShort} short of it; a push off slime leaves the ship with {@code launch} m/s more along the move.
     */
    private static void move(ServerLevel level, PistonEvent.Pre event, ShipRef ship, Vec3 direction, int pistons, long now,
                             boolean pull, double distance, double launch, double pullShort, String what) {
        int delay = 0;
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
            // the rest of a group: it extends with the move already under way and pushes nothing more (its head waits
            // for the ship like the first one's)
            if (GLIDES.containsKey(ship.id())) STARTED.add(ship.id());
            return;
        }
        double capacity = Math.max(1, pistons) * SERVER.pistonPushMassKg.get();
        // a share of a block (two ships pushing off each other) was weighed as a pair already
        if (distance >= 1.0 && mass > capacity) {
            if (!pull) event.setCanceled(true);
            debug("ship {} ({} kg) is too heavy for {} piston(s) ({} kg): {}", ship.id(), Math.round(mass),
                    Math.max(1, pistons), Math.round(capacity), pull ? "the piston comes back without it" : "the piston doesn't extend");
            return;
        }
        moved.put(ship.id(), new Moving(direction, now + delay + GLIDE_TICKS + RIDE_ALONG_TICKS));
        ensureHook();
        distance = pull ? Math.max(0.0, distance - pullShort) : distance + PUSH_CLEARANCE;
        if (delay <= 0) {
            GLIDES.put(ship.id(), new Glide(level, direction, distance, Math.max(0.0, launch)));
            STARTED.add(ship.id());
        } else {
            WAITING.computeIfAbsent(level, l -> new ArrayList<>()).add(new Waiting(ship.id(), direction, distance, now + delay));
        }
        debug("ship {} ({} kg) {}: {} block(s) along {} with {} piston(s)", ship.id(), Math.round(mass), what,
                String.format(java.util.Locale.ROOT, "%.2f", distance), direction, Math.max(1, pistons));
    }

    /**
     * Whether a piston is moving {@code shipId} right now (pushing it, pulling it, or shoving it off what its own piston
     * pushed against): until the move and its ride-along window are over. Its contacts up to {@link #PUSH_CONTACT_MPS}
     * are the push, not a crash: the impact rules ({@code ShipTerrainImpacts}, {@code ShipHullClash},
     * {@code ShipEntityImpacts}) and the deck momentum leave them alone.
     */
    public static boolean moving(ServerLevel level, long shipId, long now) {
        if (GLIDES.containsKey(shipId)) return true;
        Map<Long, Moving> moved = MOVED.get(level);
        if (moved == null) return false;
        Moving move = moved.get(shipId);
        return move != null && move.until() >= now;
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
                if (ship != null && !ship.isStatic()) GLIDES.put(w.shipId(), new Glide(level, w.direction(), w.distance(), 0.0));
            }
        }
        // a stopped glide's blocks are solid once none of them is a moving block any more: then it may let the ship go
        for (Glide g : GLIDES.values()) {
            if (g.level != level || !g.held || g.solid || !g.stopping) continue;
            boolean moving = false;
            for (BlockPos cell : g.cells) {
                if (level.getBlockState(cell).is(Blocks.MOVING_PISTON)) {
                    moving = true;
                    break;
                }
            }
            if (!moving) g.solid = true;
        }
        Map<BlockPos, Hold> holds = HOLDS.get(level);
        // a hold on a cell that never held a moving block (a retracting head's, gone at once) is forgotten when it runs out
        if (holds != null) holds.values().removeIf(hold -> hold.until() < now);
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
        HOLDS.remove(level);
    }

    private static void debug(String message, Object... args) {
        if (SERVER.debugShipCollisionLog.get()) LOGGER.info("[FullStop] piston: " + message, args);
    }
}
