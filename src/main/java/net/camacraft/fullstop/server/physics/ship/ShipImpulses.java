package net.camacraft.fullstop.server.physics.ship;

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
import net.minecraft.world.phys.Vec3;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * One-shot pushes FullStop gives ships: the momentum an entity loses landing on (or slamming
 * into) a deck, and the reactions to ship hits. Every one goes through {@link #applyCapped}, so
 * {@code maxForceNewtons} bounds them all.
 */
public final class ShipImpulses {

    /**
     * One VS physics step at its default 60 Hz. An impulse is delivered as a force over one step,
     * so {@code maxForceNewtons} caps an impulse at {@code cap * step}.
     */
    static final double PHYS_STEP_SECONDS = 1.0 / 60.0;

    /** Same window as the damage top-up: one landing is detected on up to two ticks. */
    private static final int IMPULSE_TOP_UP_TICKS = 5;

    private ShipImpulses() {
    }

    /**
     * A landing measured by FullStop's impact pipeline (called right after collision detection,
     * whoever owns the entity's own impact physics - this is a force ON the ship). The ship gets
     * the momentum the entity lost, {@code kg * (v_before - v_now)} with velocities already
     * relative to the ship; a two-tick landing tops up to its full momentum instead of pushing
     * twice.
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

        Vec3 lost = fullstop.getPreImpactScaledVelocity().subtract(fullstop.getCurrentScaledVelocity());
        double total = kg * lost.length() * SERVER.fallImpactMultiplier.get();
        double topUp = total - fullstop.getRecentShipImpulse(entity.tickCount);
        if (!(topUp > 0.0) || lost.lengthSqr() < 1.0e-8) return;

        // Ship hits come back from the raycast with a world-space location.
        Vec3 at = collision.impactedHits.get(shipHit).getLocation();
        applyCapped(level, ship, lost.normalize().scale(topUp), at);
        fullstop.markShipImpulse(entity.tickCount, IMPULSE_TOP_UP_TICKS, total);
    }

    /**
     * Queues an impulse (newton-seconds, world axes) at a world point, capped so that, delivered
     * over one physics step, it never exceeds {@code maxForceNewtons}.
     *
     * @return the impulse actually queued (zero when nothing was)
     */
    public static Vec3 applyCapped(ServerLevel level, ShipRef ship, Vec3 impulse, Vec3 worldPoint) {
        double magnitude = impulse.length();
        if (!(magnitude > 0.0) || !Double.isFinite(magnitude)) return Vec3.ZERO;
        double cap = SERVER.maxForceNewtons.get() * PHYS_STEP_SECONDS;
        Vec3 capped = magnitude > cap ? impulse.scale(cap / magnitude) : impulse;
        return ShipForces.applyImpulse(level, ship.id(), capped, worldPoint) ? capped : Vec3.ZERO;
    }
}
