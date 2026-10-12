package net.camacraft.fullstop.common.physics;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The flight off a slime bounce keeps its sideways speed (the owner, 2026-10-10: bouncing off a tilted ship's slime
 * "there is far too much horizontal dampening from the bounce (maybe from entity drag?)").
 *
 * <p>It is the drag. A living body in the air keeps 0.91 of its sideways motion each tick and 0.98 of its vertical
 * ({@code LivingEntity.travel}): air control for walking, five times the drag on what a bounce throws sideways. Off
 * flat slime a bounce is straight up and loses nothing to it; off a slime face that leans, or a slime wall, the rebound
 * is partly sideways and that part was gone in a few ticks (a third of it in four), so a bounce off a tilted deck went
 * up and dropped back almost where it came from. From the bounce until the body touches anything (the ground, a wall,
 * water) or flies, the sideways speed the bounce gave it ({@link #start}) is carried on with the vertical drag: each
 * tick after the body's travel it gets back what the walking drag took from that part
 * ({@code slimeBounceKeepsMomentum}). Its own air control is untouched, and so is everything else it does in the air.</p>
 */
public final class SlimeFlight {

    /** Vanilla's air drag on a living body: sideways and vertical. */
    private static final double SIDEWAYS_DRAG = 0.91, VERTICAL_DRAG = 0.98;
    /** Less than this sideways (blocks per tick) isn't worth carrying. */
    private static final double MIN_BPT = 0.02;
    /** A flight is carried at most this long (ticks). */
    private static final int MAX_TICKS = 200;

    private static final class Flight {
        /** The sideways velocity (world, blocks per tick) the bounce gave, decaying as vertical motion does. */
        Vec3 carried;
        int ticks;
        /** The sideways drag this tick's travel applies: 0.91 in the air, the block's friction times 0.91 on the ground. */
        double drag = SIDEWAYS_DRAG;

        Flight(Vec3 carried) {
            this.carried = carried;
        }
    }

    private static final Map<Entity, Flight> FLIGHTS = Collections.synchronizedMap(new WeakHashMap<>());

    private SlimeFlight() {
    }

    private static boolean enabled() {
        return FullStopConfig.SERVER_SPEC.isLoaded() && FullStopConfig.SERVER.slimeBounceKeepsMomentum.get();
    }

    /**
     * A bounce off slime just gave {@code entity} the velocity {@code relativeOut} (world, blocks per tick, against the
     * face it bounced off): its sideways part (across the body's own up) is carried from here. Called on the side that
     * moves the body (the local player's client, the server for everything else).
     */
    public static void start(Entity entity, Vec3 relativeOut) {
        if (!(entity instanceof LivingEntity) || !enabled()) return;
        GravityFrame frame = FullStopCapability.currentFrame(entity);
        Vec3 sideways = frame.horizontalPart(relativeOut);
        if (sideways.length() < MIN_BPT) {
            FLIGHTS.remove(entity);
            return;
        }
        FLIGHTS.put(entity, new Flight(sideways));
    }

    /** The sideways velocity a flight still carries (blocks per tick), or null with none; for the harness. */
    @Nullable
    public static Vec3 carried(Entity entity) {
        Flight flight = FLIGHTS.get(entity);
        return flight == null ? null : flight.carried;
    }

    /**
     * Before {@code LivingEntity.travel}: the sideways drag it will apply. The tick after a bounce the body is still on
     * the ground as far as vanilla knows (the bounce's move landed it), so that travel takes the slime's ground friction
     * (0.8 x 0.91), not the air's: a fifth of the bounce's sideways speed gone in one tick.
     */
    @SuppressWarnings("deprecation")
    public static void beforeTravel(LivingEntity entity) {
        if (FLIGHTS.isEmpty()) return;
        Flight flight = FLIGHTS.get(entity);
        if (flight == null) return;
        if (entity.onGround()) {
            net.minecraft.core.BlockPos below = ((net.camacraft.fullstop.common.mixin.EntityMovementBlockInvoker) entity).fullstop$blockBelowForMovement();
            flight.drag = entity.level().getBlockState(below).getFriction(entity.level(), below, entity) * SIDEWAYS_DRAG;
        } else {
            flight.drag = SIDEWAYS_DRAG;
        }
    }

    /** After {@code LivingEntity.travel}: give back what the walking drag took from the bounce's sideways speed. */
    public static void afterTravel(LivingEntity entity) {
        if (FLIGHTS.isEmpty()) return;
        Flight flight = FLIGHTS.get(entity);
        if (flight == null) return;
        // the bounce's own tick (the body is still on the face it bounced off) is carried; after that, any touch ends it
        boolean touched = flight.ticks > 0 && (entity.onGround() || entity.horizontalCollision || entity.verticalCollision);
        boolean flying = entity.isFallFlying() || entity instanceof Player player && player.getAbilities().flying;
        if (!enabled() || touched || flying || entity.isInWater() || entity.isInLava() || entity.isPassenger()
                || entity.isNoGravity() || ++flight.ticks > MAX_TICKS || flight.carried.length() < MIN_BPT) {
            FLIGHTS.remove(entity);
            return;
        }
        GravityFrame frame = FullStopCapability.currentFrame(entity);
        Vec3 world = Gravity.getWorldVelocity(entity, frame);
        // the drag took (1 - drag) of the carried part this tick; the vertical drag would have taken (1 - 0.98)
        Vec3 back = flight.carried.scale(VERTICAL_DRAG - flight.drag);
        Gravity.setWorldVelocity(entity, world.add(back), frame);
        flight.carried = flight.carried.scale(VERTICAL_DRAG);
    }

    /** Ends {@code entity}'s flight (a teleport, a new launch). */
    public static void end(Entity entity) {
        FLIGHTS.remove(entity);
    }
}
