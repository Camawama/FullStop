package net.camacraft.fullstop.client.physics;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector;
import net.camacraft.fullstop.common.physics.math.BounceMath;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import static net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability;

/**
 * Physics reactions for the vehicle the LOCAL player drives, run on the
 * driver's client — the side that OWNS the vehicle's motion.
 *
 * A player-driven vehicle is client-authoritative: its positions reach the
 * server already clipped, so the server detects wall contact a tick late (the
 * pre-impact velocity is already spent) and anything it writes back races the
 * client's own simulation and the vanilla motion sync. That race was the whole
 * tail of ridden-boat bugs: weak bounces, bounce-then-dead-stop, lost mount
 * momentum. Running the reaction here means the one-tick-lookahead raycast
 * sees fresh positions (full-speed PRE-contact bounces, like server-simulated
 * entities get), and the applied velocity is used by the very next vehicle
 * tick with nothing in between. The server's BounceHandler skips player-driven
 * vehicles; damage, sounds, and particles stay server-side.
 */
public final class ClientVehiclePhysics {

    private ClientVehiclePhysics() {
    }

    // ------------------------------------------------------------------
    // Momentum seeds across mount/dismount control handoffs.
    //
    // A single velocity application at the handoff kept losing to LATE vanilla
    // sync on the client: dismounting makes the server send the player an
    // absolute position packet whose handler overwrites the player's motion,
    // and the boarding window has equivalent transient overwrites. The seed is
    // therefore captured at the mount event (from the vehicle's locally
    // observed movement) and RE-APPLIED for a few consecutive client ticks —
    // it outlives any one-shot overwrite, and everything runs on the side that
    // owns the motion, so there is nothing left to race.
    // ------------------------------------------------------------------

    private static Vec3 vehicleSeed = null;
    private static int vehicleSeedId = -1;
    private static int vehicleSeedTicks = 0;

    private static Vec3 riderSeed = null;
    private static int riderSeedTicks = 0;

    /** Called from the client-side mount event for the LOCAL player only. */
    public static void onLocalMountChange(boolean mounting, Entity vehicle, Vec3 observedMotion) {
        if (mounting) {
            if (observedMotion.lengthSqr() > 1.0e-6) {
                vehicleSeed = observedMotion;
                vehicleSeedId = vehicle.getId();
                vehicleSeedTicks = 5;
            }
        } else {
            if (observedMotion.lengthSqr() > 1.0e-4) {
                riderSeed = observedMotion;
                riderSeedTicks = 3;
            }
        }
    }

    /** Re-applies pending handoff momentum; runs every client tick before the physics. */
    private static void tickSeeds(LocalPlayer player) {
        if (vehicleSeed != null) {
            Entity vehicle = player.getVehicle();
            if (vehicle != null && vehicle.getId() == vehicleSeedId
                    && vehicle.getControllingPassenger() == player) {
                vehicle.setDeltaMovement(vehicleSeed);
            }
            if (--vehicleSeedTicks <= 0 || vehicle == null) {
                vehicleSeed = null;
            }
        }

        if (riderSeed != null) {
            if (!player.isPassenger()) {
                player.setDeltaMovement(riderSeed);
            }
            if (--riderSeedTicks <= 0) {
                riderSeed = null;
            }
        }
    }

    /** Called once per client tick with the local player; no-op unless they drive a vehicle. */
    public static void tickDrivenVehicle(LocalPlayer player) {
        tickSeeds(player);

        Entity vehicle = player.getVehicle();
        if (vehicle == null || vehicle.getControllingPassenger() != player) return;

        FullStopCapability cap = grabCapability(vehicle);
        if (cap == null) return;

        // The vehicle's client-side capability measures the authoritative
        // positions — same pattern the debug renderer uses.
        if (vehicle.tickCount != cap.getLastTick()) {
            cap.tick(vehicle);
            cap.setLastTick(vehicle.tickCount);
        }

        Collision collision = CommonCollisionDetector.detectBlocks(vehicle, cap);
        if (collision.fake()) return;

        if (collision.collisionType == Collision.CollisionType.WATER) {
            waterSkip(vehicle, cap, collision);
        } else {
            bounce(player, vehicle, cap, collision);
        }
    }

    /** Same gates and math as the server BounceHandler, applied where it sticks. */
    private static void bounce(LocalPlayer player, Entity vehicle, FullStopCapability cap, Collision collision) {
        if (!collision.bouncy() && collision.collisionType != Collision.CollisionType.HONEY) return;
        if (!cap.canBounce()) return;

        Vec3 preV = cap.getPreImpactScaledVelocity();
        Vec3 normal = BounceMath.mostOpposedNormal(collision.impactedHits, preV);
        if (normal == null) return;
        if (!BounceMath.isDirectImpact(preV, normal)) return;
        if (cap.getCurrentScaledVelocity().dot(normal) > 0.5) return;

        Vec3 newV = BounceMath.bounceVelocity(preV, normal, collision.collisionType);
        if (newV == null) return;

        vehicle.setDeltaMovement(newV.scale(0.05));
        vehicle.hasImpulse = true;
        cap.setBounceCooldown(1); // matches BounceHandler's reduced refractory

        // The VEHICLE turns into the rebound — the ricochet equivalent of the
        // on-foot camera rotation; its heading streams to the server with the
        // regular vehicle move packets. The rider's head follows BY THE SAME
        // DELTA so they keep facing the same way relative to the boat — but
        // SMOOTHLY, through the capability's gradual camera correction (the
        // same easing the on-foot bounce camera uses; a raw setYRot snapped).
        if (collision.bouncy() && newV.horizontalDistance() > 3.0) {
            float oldYaw = vehicle.getYRot();
            float newYaw = (float) Math.toDegrees(Math.atan2(-newV.x, newV.z));
            vehicle.setYRot(newYaw);
            // Snap the boat's render interpolation too: a near-180° yaw lerp
            // makes the model visibly spin the long way around for a frame.
            vehicle.yRotO = newYaw;

            float yawDelta = Mth.wrapDegrees(newYaw - oldYaw);
            FullStopCapability playerCap = grabCapability(player);
            if (playerCap != null) {
                playerCap.setTargetAngle(player.getYRot() + yawDelta);
            }
        }
    }

    /** Client-side twin of the server water skip (see BounceHandler.handleWaterSkip). */
    private static void waterSkip(Entity vehicle, FullStopCapability cap, Collision collision) {
        Vec3 preV = cap.getPreImpactScaledVelocity();
        if (preV.lengthSqr() < 10.0 * 10.0) return;
        if (collision.impactedHits.isEmpty()) return;

        // Angle from vertical: 0° = straight-down dive (plunges), ~90° = skimming.
        double angle = Math.toDegrees(Math.acos(preV.normalize().dot(new Vec3(0, -1, 0))));
        if (angle < 65) return;
        if (preV.y >= 0) return;

        Vec3 newV = new Vec3(preV.x * 0.9, -preV.y * 0.6, preV.z * 0.9);
        vehicle.setDeltaMovement(newV.scale(0.05));
        vehicle.hasImpulse = true;
        cap.setWaterSkipCooldown(10);
    }
}
