package net.camacraft.fullstop.server.physics.interaction;

import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/**
 * Physical consequences of carrying passengers, for mobs mounted through
 * FullStop's land-on-a-mob feature (or any other unsteered ride):
 *
 * <ul>
 *   <li><b>Weight slows the walk:</b> a transient movement-speed modifier
 *       scales the carrier by vehicleMass / (vehicleMass + passengerMass) —
 *       one player on a cow is ~0.6× speed; a taller stack is slower.</li>
 *   <li><b>Weight sinks in water:</b> a downward force proportional to the
 *       passenger/vehicle mass ratio fights the mob's float bobbing — a cow
 *       stacked on a cow pushes the lower one under.</li>
 * </ul>
 *
 * The mount-panic reaction ("it's scared you jumped on it") lives in
 * {@link EntityCollisionHandler#applyMountPanic} — it applies only to
 * FullStop's collision mounts, never to saddled riding.
 */
public final class RideStackHandler {

    private static final UUID RIDE_WEIGHT_SPEED_UUID = UUID.fromString("7f5c6e1d-2b8a-4a3e-9c41-d0f3a8b6c215");
    private static final String RIDE_WEIGHT_SPEED_NAME = "fullstop.ride_weight";

    /** Downward acceleration (blocks/tick²) per unit of passenger/vehicle mass ratio, in water. */
    private static final double WATER_SINK_PER_MASS_RATIO = 0.03;
    private static final double MAX_SINK_MASS_RATIO = 2.0;

    /** Passenger/vehicle mass ratio above which the load injures the carrier. */
    private static final double CRUSH_MASS_RATIO = 1.5;
    private static final int CRUSH_INTERVAL_TICKS = 40;
    private static final float CRUSH_DAMAGE_PER_RATIO = 2.0f;

    private RideStackHandler() {
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity living = event.getEntity();
        if (living.level().isClientSide) return;
        if (!(living instanceof Mob mob)) return;

        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);

        if (!mob.isVehicle()) {
            // Cheap for the common case: only touch the attribute map when a
            // leftover modifier must be cleared after the last rider left.
            if (speed != null && speed.getModifier(RIDE_WEIGHT_SPEED_UUID) != null) {
                speed.removeModifier(RIDE_WEIGHT_SPEED_UUID);
            }
            return;
        }

        double vehicleMass = Math.max(EntityStackUtils.getEntityMass(mob), 0.001);
        double passengerMass = 0;
        for (Entity passenger : EntityStackUtils.getPassengersRecursive(mob)) {
            passengerMass += EntityStackUtils.getEntityMass(passenger);
        }

        // Same legs, more mass: speed scales with the vehicle's share of the
        // total weight.
        if (speed != null) {
            double factor = vehicleMass / (vehicleMass + passengerMass);
            double desired = factor - 1.0; // MULTIPLY_TOTAL: 0.6× speed = -0.4
            AttributeModifier existing = speed.getModifier(RIDE_WEIGHT_SPEED_UUID);
            if (existing == null || Math.abs(existing.getAmount() - desired) > 1.0e-4) {
                if (existing != null) {
                    speed.removeModifier(RIDE_WEIGHT_SPEED_UUID);
                }
                speed.addTransientModifier(new AttributeModifier(RIDE_WEIGHT_SPEED_UUID,
                        RIDE_WEIGHT_SPEED_NAME, desired, AttributeModifier.Operation.MULTIPLY_TOTAL));
            }
        }

        // Loaded stacks ride low: counter the float bobbing proportionally to
        // how much weight sits on the swimmer.
        if (mob.isInWater()) {
            double sink = WATER_SINK_PER_MASS_RATIO
                    * Math.min(passengerMass / vehicleMass, MAX_SINK_MASS_RATIO);
            mob.setDeltaMovement(mob.getDeltaMovement().add(0, -sink, 0));
        }

        // Too much weight CRUSHES the carrier. Every mob in a stack evaluates
        // the mass above ITSELF, so the bottom of a tall tower suffers most
        // and a collapsing stack sheds from the bottom up. A single rider of
        // similar size (ratio ~1) is safe; roughly 1.5× the carrier's own mass
        // is where injury starts.
        double massRatio = passengerMass / vehicleMass;
        if (massRatio > CRUSH_MASS_RATIO && mob.tickCount % CRUSH_INTERVAL_TICKS == 0) {
            float crushDamage = (float) ((massRatio - CRUSH_MASS_RATIO) * CRUSH_DAMAGE_PER_RATIO);
            mob.hurt(mob.damageSources().cramming(), crushDamage);
        }
    }
}
