package net.camacraft.fullstop.common.physics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Makes Valkyrien Skies hulls solid at any speed ({@code solidHulls}).
 *
 * <p><b>Why entities went through hulls.</b> VS 2.4.11 resolves an entity's move against a ship
 * inside {@code Entity.move}, around the {@code collide} call, with its own polygon resolver
 * (vs-core's {@code EntityPolygonCollider}). It finds the time of contact correctly, but then
 * handles each ship block on its own and answers the part of the move left after contact with the
 * SMALLEST correction that clears that one block, on any axis. Once more than about a block of
 * travel is left after touching (the exact figure depends on how far the entity's box overlaps
 * the block row across the move), a nudge sideways or down (0.4 blocks for a cow against a wall,
 * measured) is smaller than stopping, so the entity is nudged and goes on at full speed: a player
 * flying at 22 m/s from flush against a hull, a mob thrown at 40 m/s from anywhere, went straight
 * through a one-block wall. Measured in the game tests by calling VS's resolver directly, and with
 * FullStop entirely out of the way (a NoAI cow moved by {@code Entity.move}).
 *
 * <p><b>The fix.</b> A move that long near a ship is handed to that same chain (VS's resolver,
 * then the world's own collision) in steps of at most {@link #STEP} blocks, the entity's box
 * advanced after each: every step leaves too little travel after contact for a nudge to win, so
 * the hull stops the entity like any wall, and everything VS does for a slow move (sliding along
 * the hull, stepping up on a deck, standing registration) still happens. Short moves, moves far
 * from any ship, a rider's own moves on the deck VS carries it with (under {@link #RIDER_LENGTH}),
 * noclip entities and bodies Gravity Unbound collides as a capsule (it handles ships itself) are
 * left exactly as they were.
 *
 * <p>Runs wherever the entity is simulated: the server for mobs, the player's own client for the
 * player (FullStop must be installed there too). Belongs in Camafy's ship layer eventually, so
 * every mod's entities get it; it only uses Camafy's ship queries.
 */
public final class ShipHullSweep {

    /** Longest step handed to VS's resolver (blocks). VS stops a step this short at the hull every time. */
    public static final double STEP = 0.25;
    /** Moves up to this long (blocks) go through unchanged: VS resolves them itself. */
    private static final double MIN_LENGTH = 0.3;
    /** At most this many steps (a 16-block move); anything longer is split that finely anyway. */
    private static final int MAX_STEPS = 64;
    /** How far around the swept box a ship must be to matter (VS gathers ship blocks 0.5 around players). */
    private static final double NEAR = 0.5;
    /**
     * The longest move (blocks in one call, 100 = 2000 m/s at 20 ticks a second, Full Stop's {@code MAX_PLAUSIBLE_MPS}) that
     * is collided at all. Anything longer is no motion an entity can have: it is not swept, no collision box is built for
     * it (vanilla's {@code collide} alone walks every block cell of the box the move spans, and a box from the world to a
     * ship's shipyard, 28 million blocks off, never ends), and it doesn't move the entity. 2026-10-30: the owner's client
     * froze for good on joining a save where the player sat on a ship's seat in the Great Unknown ("I restarted the game
     * and attempted to boot into that world again but the game instantly froze"): the render thread was in this class's
     * {@code resolve}, in {@code BlockCollisions}, asking the client chunk cache for chunk after chunk.
     */
    public static final double MAX_MOVE = 100.0;
    private static final java.util.concurrent.atomic.AtomicLong REFUSED = new java.util.concurrent.atomic.AtomicLong();
    /**
     * A rider VS carries with a ship is left to VS for moves up to this long (blocks): its own
     * steps, jumps and falls on a deck are well inside what VS's resolver gets right (it only
     * failed with about a block of travel left after contact), and its riding - standing
     * registration, the carry, turning with a rotor - stays exactly VS's.
     */
    private static final double RIDER_LENGTH = 0.9;

    private ShipHullSweep() {
    }

    /**
     * Receives the local player's hull shoves on its client (set by FullStop's client setup):
     * where a hull running into the player is noticed and answered (ClientShipContacts).
     */
    public interface ShoveListener {
        /**
         * @param movement      the move the player asked for this tick (blocks)
         * @param result        what it got (blocks), a hull's shove included
         * @param before        its box before the move
         * @param carriedBefore the ship VS was carrying it with before the move ({@code ShipRef.NO_ID}: none)
         */
        void onShove(Entity self, Vec3 movement, Vec3 result, AABB before, long carriedBefore);
    }

    @Nullable
    public static volatile ShoveListener clientShoves = null;

    /**
     * The collision result for {@code movement}: {@code original} (VS's ship resolver, then the
     * world's) called once, or step by step for a long move near a ship. A result that goes
     * further than the move asked, or back the other way, sideways, is a hull that ran into the
     * entity - VS resolves that by moving it out - and is recorded as the entity's shove this tick
     * (not its own motion), and for the local player handed to {@link #clientShoves}.
     */
    public static Vec3 collide(Entity self, Vec3 movement, Operation<Vec3> original) {
        if (implausible(movement)) return refuse(self, movement);
        ShoveListener listener = self instanceof Player && self.level().isClientSide ? clientShoves : null;
        long carriedBefore = ShipRef.NO_ID;
        if (listener != null) {
            ShipRef carrying = ShipCompat.carryingShip(self);
            if (carrying != null) carriedBefore = carrying.id();
        }
        AABB start = self.getBoundingBox();
        Vec3 result = resolve(self, movement, original, start);
        Vec3 shove = shove(movement, result);
        if (shove != null && ShipCompat.active() && FullStopCapability.currentFrame(self).isDefault()
                && Ships.anyIntersecting(self.level(), start.expandTowards(result).inflate(NEAR))) {
            // A server-side player's move replays its client's (which already met the hull): only
            // what its client saw counts, reported with its motion.
            if (!(self instanceof ServerPlayer)) {
                FullStopCapability cap = FullStopCapability.grabCapability(self);
                if (cap != null) cap.recordShipPush(self, shove);
            }
            if (listener != null) listener.onShove(self, movement, result, start, carriedBefore);
        }
        return result;
    }

    private static Vec3 resolve(Entity self, Vec3 movement, Operation<Vec3> original, AABB start) {
        int steps = steps(self, movement);
        if (steps <= 1) return original.call(self, movement);
        if (self instanceof Player && FullStopConfig.SERVER.debugShipContactLog.get()) {
            org.slf4j.LoggerFactory.getLogger(ShipHullSweep.class).info("[FullStop] {}{}: a {}-block move near a ship resolved in {} "
                            + "steps (solidHulls)", self.getName().getString(), self.level().isClientSide ? " (client)" : "",
                    String.format(java.util.Locale.ROOT, "%.2f", movement.length()), steps);
        }
        Vec3 step = movement.scale(1.0 / steps);
        Vec3 total = Vec3.ZERO;
        StringBuilder trace = self instanceof Player && FullStopConfig.SERVER.debugShipContactLog.get() ? new StringBuilder() : null;
        try {
            for (int i = 0; i < steps; i++) {
                Vec3 moved = original.call(self, step);
                total = total.add(moved);
                if (trace != null) trace.append(String.format(java.util.Locale.ROOT, " (%.2f,%.2f,%.2f)", moved.x, moved.y, moved.z));
                self.setBoundingBox(start.move(total));
                // blocked every way: the same step would be blocked again
                if (moved.lengthSqr() < 1.0e-14) break;
            }
        } finally {
            // Entity.move sets the position (and the box from it) right after this returns
            self.setBoundingBox(start);
        }
        if (trace != null && total.lengthSqr() < 0.25 * movement.lengthSqr()) {
            org.slf4j.LoggerFactory.getLogger(ShipHullSweep.class).info("[FullStop] {}{}: the move ({}) was cut to ({}); steps:{}",
                    self.getName().getString(), self.level().isClientSide ? " (client)" : "",
                    String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", movement.x, movement.y, movement.z),
                    String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", total.x, total.y, total.z), trace);
        }
        return total;
    }

    /** A move no entity can make in one tick: longer than {@link #MAX_MOVE}, or not finite. */
    public static boolean implausible(Vec3 movement) {
        double lengthSqr = movement.lengthSqr();
        return !(lengthSqr <= MAX_MOVE * MAX_MOVE);
    }

    /**
     * An implausible move: no sweep, no collision, no movement, and the entity's own velocity dropped so the next tick
     * doesn't ask for it again. Logged (at most once a minute) with what asked for it.
     */
    private static Vec3 refuse(Entity self, Vec3 movement) {
        long n = REFUSED.incrementAndGet();
        if (n == 1 || n % 1200 == 0) {
            org.slf4j.LoggerFactory.getLogger(ShipHullSweep.class).warn("[FullStop] {}{} asked to move ({}) in one tick from ({}), riding {}: "
                            + "longer than {} blocks, so it doesn't move and its velocity is dropped ({} such moves so far)",
                    self.getName().getString(), self.level().isClientSide ? " (client)" : "",
                    String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", movement.x, movement.y, movement.z),
                    String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", self.getX(), self.getY(), self.getZ()),
                    self.getVehicle() == null ? "nothing" : self.getVehicle().getType().toShortString(), (int) MAX_MOVE, n);
        }
        self.setDeltaMovement(Vec3.ZERO);
        return Vec3.ZERO;
    }

    /** Implausible moves refused so far (the harness reads it). */
    public static long refusedMoves() {
        return REFUSED.get();
    }

    /**
     * The horizontal part of {@code result} that no collision of the move asked for could have
     * produced (blocks), or null: a world block only ever cuts a move short, axis by axis (and a
     * step-up lifts it), while a hull that moved into the entity pushes it further than it asked,
     * or back the way it came.
     */
    @Nullable
    public static Vec3 shove(Vec3 movement, Vec3 result) {
        double x = excess(movement.x, result.x);
        double z = excess(movement.z, result.z);
        if (x * x + z * z < SHOVE_MIN * SHOVE_MIN) return null;
        return new Vec3(x, 0.0, z);
    }

    /** Smallest shove worth recording (blocks per tick: 2 mm, 0.04 m/s). */
    private static final double SHOVE_MIN = 0.002;

    private static double excess(double wanted, double got) {
        if (Math.abs(got) < 1.0e-7) return 0.0;
        if (Math.abs(wanted) < 1.0e-7 || Math.signum(got) != Math.signum(wanted)) return got;
        return Math.abs(got) > Math.abs(wanted) ? got - wanted : 0.0;
    }

    /** How many steps {@code movement} is resolved in: 1 unless it is long and a ship is near. */
    public static int steps(Entity self, Vec3 movement) {
        double lengthSqr = movement.lengthSqr();
        if (lengthSqr <= MIN_LENGTH * MIN_LENGTH || !Double.isFinite(lengthSqr) || self.noPhysics) return 1;
        if (!FullStopConfig.SERVER_SPEC.isLoaded() || !FullStopConfig.SERVER.solidHulls.get() || !ShipCompat.active()) {
            return 1;
        }
        if (lengthSqr < RIDER_LENGTH * RIDER_LENGTH && ShipEntities.isCarried(self)) return 1;
        if (!Ships.anyIntersecting(self.level(), self.getBoundingBox().expandTowards(movement).inflate(NEAR))) return 1;
        // Gravity Unbound's capsule collides with ships itself (VS's resolver is skipped for it)
        if (!FullStopCapability.currentFrame(self).isDefault()) return 1;
        return Math.min(MAX_STEPS, (int) Math.ceil(Math.sqrt(lengthSqr) / STEP));
    }
}
