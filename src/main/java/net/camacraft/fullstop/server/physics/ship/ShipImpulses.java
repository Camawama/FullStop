package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.vs.ShipForces;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.physics.VanillaFall;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.data.Collision;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * One-shot pushes FullStop gives ships: the momentum an entity delivers landing on (or slamming
 * into) a deck, and the reactions to ship hits. Every one goes through {@link #applyCapped}, so
 * {@code maxForceNewtons} bounds them all.
 *
 * <p>Landings and slams normally go through {@link ShipEntityHits} (a two-body collision);
 * {@link #onEntityImpact} is what is left when that is switched off or doesn't apply (a slime
 * hull, an entity another mod owns the contacts of). It follows the same physics: the closing
 * speed is the lander's velocity RELATIVE to the deck where it lands, the push is the two-body
 * impulse with the ship's effective mass there (never more than the lander was moving), and
 * nothing the ship did to its own rider comes back into it.
 */
public final class ShipImpulses {

    /**
     * One VS physics step at its default 60 Hz. An impulse is delivered as a force over one step,
     * so {@code maxForceNewtons} caps an impulse at {@code cap * step}.
     */
    static final double PHYS_STEP_SECONDS = 1.0 / 60.0;

    /** Same window as the damage top-up: one landing is detected on up to two ticks. */
    private static final int IMPULSE_TOP_UP_TICKS = 5;

    /**
     * How much faster than the closing speed one push may make the hull move at the point it pushes
     * (a perfectly elastic two-body bounce reverses the closing speed: a change of twice it).
     */
    static final double SPEED_CHANGE_PER_CLOSING = 2.0;
    /** The least change a push is always allowed (m/s): a slow contact still gets its small push. */
    static final double MIN_SPEED_CHANGE = 0.5;

    private ShipImpulses() {
    }

    /**
     * A landing measured by FullStop's impact pipeline, when {@link ShipEntityHits} didn't take the
     * contact (whoever owns the entity's own impact physics - this is a force ON the ship). The
     * ship gets the two-body impulse of the closing speed between the lander (world velocity
     * before the impact) and the deck's material at the contact point, along the deck's normal;
     * a two-tick landing tops up to its full impulse instead of pushing twice. A rider pushes its
     * own ship only by landing on it, and not at all while the ship's own throw is still in it.
     */
    public static void onEntityImpact(Entity entity, FullStopCapability fullstop, Collision collision) {
        if (collision.impactedPositions.isEmpty() || !(entity.level() instanceof ServerLevel level)) return;
        if (!ShipCompat.weightActive() || !SERVER.enableFallImpact.get()) return;
        // A rider's weight lands with its vehicle (the stack weight below counts it).
        if (entity.isPassenger()) return;
        if (!SERVER.includePlayers.get() && entity instanceof Player) return;

        double minSpeed = VanillaFall.impactSpeedForFallDistance(SERVER.minFallDistance.get());
        if (fullstop.getStoppingForce() < Math.max(minSpeed, 0.5)) return;

        int shipHit = -1;
        for (int i = 0; i < collision.impactedPositions.size() && i < collision.impactedHits.size(); i++) {
            if (ShipCompat.isShipBlock(level, collision.impactedPositions.get(i))) {
                shipHit = i;
                break;
            }
        }
        if (shipHit < 0) return;
        BlockPos shipPos = collision.impactedPositions.get(shipHit);
        ShipRef ship = Ships.managing(level, shipPos);
        if (ship == null) return;

        double kg = ShipWeightHandler.stackWeightKg(entity);
        if (kg < SERVER.minPhysicsWeightKg.get()) return;
        long now = level.getGameTime();
        if (fullstop.isShipFeedbackBlocked(ship.id(), now)) return;

        // Ship hits come back from the raycast with a world-space location, and their face in
        // ship space.
        BlockHitResult hit = collision.impactedHits.get(shipHit);
        Vec3 at = hit.getLocation();
        Vec3 normal = ship.faceNormal(hit.getDirection());
        Vec3 deck = ship.velocityAt(at);
        if (normal == null || deck == null) return;
        GravityFrame frame = fullstop.getFrame();
        boolean weightless = fullstop.isWeightless();
        boolean fromBelow = ShipContactPhysics.fromBelow(normal, frame, weightless);
        // a rider's run into its own ship's structure: the deck never paid for its running start
        if (!fromBelow && fullstop.stoodOnBefore(ship.id(), now, ShipEntityHits.RIDER_TICKS)) return;
        // standing on the deck is weight, not a landing (the deck moving under it isn't one either)
        if (fromBelow && fullstop.getGroundedTicks() > 2) return;
        double approach = -fullstop.getPreImpactWorldVelocity().subtract(deck).dot(normal);
        if (approach < Math.max(minSpeed, 0.5)) return;

        double shipKg = ship.isStatic() ? Double.POSITIVE_INFINITY : ship.physicalMass();
        if (!(shipKg > 0.0) || Double.isInfinite(shipKg)) return;
        ShipContactPhysics.Exchange exchange = ShipContactPhysics.resolve(ship, shipKg, at, normal, approach, kg, frame, weightless);
        double total = exchange.impulse() * SERVER.fallImpactMultiplier.get();
        double topUp = total - fullstop.getRecentShipImpulse(entity.tickCount);
        if (!(topUp > 0.0)) return;
        applyCapped(level, ship, normal.scale(-topUp), at, approach);
        fullstop.markShipImpulse(entity.tickCount, IMPULSE_TOP_UP_TICKS, total);
    }

    /**
     * Queues an impulse (newton-seconds, world axes) at a world point, capped so that, delivered
     * over one physics step, it never exceeds {@code maxForceNewtons}, and so that it never changes
     * the hull's speed at that point by more than the speed of sound (a push whose closing speed
     * isn't known: see the other overload for one that is).
     *
     * @return the impulse actually queued (zero when nothing was)
     */
    public static Vec3 applyCapped(ServerLevel level, ShipRef ship, Vec3 impulse, Vec3 worldPoint) {
        return applyCapped(level, ship, impulse, worldPoint, Double.NaN);
    }

    /**
     * As above, for a push that answers a collision at {@code closingSpeed} (m/s): the push may change the
     * hull's speed at the point by at most {@link #SPEED_CHANGE_PER_CLOSING} times that (never less than
     * {@link #MIN_SPEED_CHANGE}), and never by more than the speed of sound unless the collision itself was
     * that fast. The change is judged by the ship's EFFECTIVE mass at the point along the push
     * ({@link ShipInertia}: a push at the edge of a small ship mostly spins it), so a tiny ship - whose levers
     * and moments are scaled with it - can't be handed a push sized for a big one. This is what stopped a ship
     * at 0.25 from spinning itself up on every contact until it left the world.
     */
    public static Vec3 applyCapped(ServerLevel level, ShipRef ship, Vec3 impulse, Vec3 worldPoint, double closingSpeed) {
        double magnitude = impulse.length();
        if (!(magnitude > 0.0) || !Double.isFinite(magnitude)) return Vec3.ZERO;
        double mass = ship.physicalMass();
        if (!(mass > 0.0) || !Double.isFinite(mass)) return Vec3.ZERO;
        double sound = net.camacraft.fullstop.server.physics.SonicBooms.SOUND_MPS;
        double change;
        if (Double.isFinite(closingSpeed)) {
            double closing = Math.abs(closingSpeed);
            change = Math.max(MIN_SPEED_CHANGE, SPEED_CHANGE_PER_CLOSING * closing);
            if (closing < sound) change = Math.min(change, sound);
        } else {
            change = sound;
        }
        Vec3 direction = impulse.scale(1.0 / magnitude);
        double effective = worldPoint == null ? mass : ShipInertia.effectiveMassKg(ship, mass, worldPoint, direction);
        if (!(effective > 0.0) || !Double.isFinite(effective)) effective = mass;
        double cap = Math.min(SERVER.maxForceNewtons.get() * PHYS_STEP_SECONDS, effective * change);
        Vec3 capped = magnitude > cap ? impulse.scale(cap / magnitude) : impulse;
        return ShipForces.applyImpulse(level, ship.id(), capped, worldPoint) ? capped : Vec3.ZERO;
    }
}
