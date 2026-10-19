package net.camacraft.fullstop.server.physics.interaction;

import net.camacraft.camafy.physics.EntityStack;
import net.camacraft.camafy.physics.PhysicsOwnership;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Landing on a mob, a boat or a minecart rides it (Entity Riding: {@code enableEntityRiding}, on). A body that comes
 * down on top of one that nothing rides yet, between {@code entityRidingMinSpeed} and {@code entityRidingMaxSpeed},
 * mounts it instead of being a collision (no crush, no damage, no push): a player dropping onto a cow sits on its
 * back, and the cow panics and bolts ({@code entityRidingMountPanic}, the vanilla panic goal, which has no is-ridden
 * check; {@link RideStackHandler} then slows it by the load). Faster landings are the crush they always were.
 *
 * <p>Riders are players ({@code entityRidingMobRiders} lets any living body ride what it lands on: mobs stacking
 * on each other was chaos when the old collisions did that for every body). The rules the old collisions had are
 * kept: never while crouching ({@code entityRidingCrouchPrevents}), never a creative flyer, never a body already
 * riding, never onto something that is ridden or that is crouching, never heavier than a living mount (a boat or a
 * cart takes anyone), never onto one's own kind unless a baby (a piglet rides a pig, a pig doesn't), never within
 * the dismount cooldown
 * ({@code entityRidingDismountCooldownTicks}, during which the rider and its last mount don't collide either), never
 * a ring of riders. Added: never a body another mod holds outright (a physgun's grip, an MKW race:
 * {@link PhysicsOwnership#heldOutrightByOther}), never onto a player (vanilla lets no one ride a player), and only
 * onto a rideable minecart (not a chest or hopper cart). A body seated on a ship is never a body to the collisions
 * at all. The server's {@code startRiding} sends the mount to the rider's client as any mount.
 *
 * <p>Called from {@link BodyCollisions} once a tick, for the contact it found between the two bodies: this class
 * never looks for landings itself.
 */
public final class EntityRiding {

    private EntityRiding() {
    }

    /** The riding rules this tick, read once (BodyCollisions asks per body). */
    public record Rules(boolean enabled, boolean mobRiders, double minSpeed, double maxSpeed) {
        public static final Rules OFF = new Rules(false, false, Double.MAX_VALUE, 0.0);

        public static Rules read() {
            if (!FullStopConfig.SERVER_SPEC.isLoaded() || !SERVER.enableEntityRiding.get()) return OFF;
            return new Rules(true, SERVER.entityRidingMobRiders.get(), SERVER.entityRidingMinSpeed.get(),
                    SERVER.entityRidingMaxSpeed.get());
        }

        /** Whether this body (the root of its ride stack) may ride what it comes down on. */
        public boolean mayRide(Entity root) {
            if (!enabled) return false;
            if (root instanceof Player player) return !player.isSpectator();
            return mobRiders && root instanceof LivingEntity;
        }

        /** Whether a landing at this closing speed (m/s) is a mount rather than a crush. */
        public boolean mounts(double closing) {
            return enabled && closing >= minSpeed && closing <= maxSpeed;
        }
    }

    /** Whether bodies ride what they land on at all. */
    public static boolean enabled() {
        return FullStopConfig.SERVER_SPEC.isLoaded() && SERVER.enableEntityRiding.get();
    }

    /** How long after dismounting a body mounts nothing (ticks); the default when the server config isn't loaded. */
    public static int dismountCooldownTicks() {
        return FullStopConfig.SERVER_SPEC.isLoaded() ? SERVER.entityRidingDismountCooldownTicks.get() : 40;
    }

    /**
     * Mounts {@code rider} on {@code vehicle} when the rules allow it.
     *
     * @param riderKg   the rider's mass (its whole stack)
     * @param vehicleKg the mount's mass
     * @return whether it now rides it
     */
    public static boolean mount(Entity rider, Entity vehicle, double riderKg, double vehicleKg) {
        if (!canRide(rider, vehicle, riderKg, vehicleKg)) return false;
        if (!rider.startRiding(vehicle, true)) return false;
        if (SERVER.entityRidingMountPanic.get()) mountPanic(vehicle, rider);
        return true;
    }

    /** The old collisions' riding rules, with the mount's kind by config and nothing another mod holds. */
    public static boolean canRide(Entity rider, Entity vehicle, double riderKg, double vehicleKg) {
        if (rider == null || vehicle == null || rider == vehicle) return false;
        if (rider.level().isClientSide()) return false;
        if (!rider.isAlive() || !vehicle.isAlive()) return false;
        if (!rideable(vehicle)) return false;
        if (SERVER.entityRidingCrouchPrevents.get() && (rider.isCrouching() || rider.isShiftKeyDown())) return false;
        if (vehicle.isCrouching()) return false;
        if (rider instanceof Player player && (player.getAbilities().flying || player.isSpectator())) return false;
        if (rider.isPassenger()) return false;
        if (vehicle.getVehicle() == rider || vehicle.isVehicle()) return false;
        if (rider.isPassengerOfSameVehicle(vehicle)) return false;
        // a living mount carries nothing heavier than itself; a boat or cart takes anyone, as in vanilla
        if (vehicle instanceof LivingEntity && riderKg > vehicleKg) return false;
        // one's own kind only as a baby (a piglet on a pig), never a pig on a pig
        if (rider.getType() == vehicle.getType() && !(rider instanceof LivingEntity living && living.isBaby())) return false;
        FullStopCapability cap = FullStopCapability.grabCapability(rider);
        if (cap != null && cap.getDismountCooldown() > 0) return false;
        if (EntityStack.isInPassengerChain(rider, vehicle) || EntityStack.isInPassengerChain(vehicle, rider)) return false;
        // a body another mod holds outright (a physgun beam, a race) is nobody's mount or rider
        return !PhysicsOwnership.heldOutrightByOther(vehicle, PhysicsClaims.SELF)
                && !PhysicsOwnership.heldOutrightByOther(rider, PhysicsClaims.SELF);
    }

    /** What may be landed on and ridden: mobs, boats (chest boats too) and rideable minecarts, each by config. */
    public static boolean rideable(Entity vehicle) {
        if (vehicle instanceof Player) return false;
        if (vehicle instanceof LivingEntity) return SERVER.entityRidingMobs.get();
        if (vehicle instanceof Boat) return SERVER.entityRidingBoats.get();
        if (vehicle instanceof Minecart) return SERVER.entityRidingMinecarts.get();
        return false;
    }

    /**
     * A mob that gets landed on panics: something just dropped out of the sky onto its back. Only for these mounts,
     * never for saddled riding: setLastHurtByMob drives the vanilla panic goal (which has no is-ridden check, so it
     * runs while ridden), and the mixin that lets unsteered mounts wander keeps it mobile enough to bolt.
     */
    static void mountPanic(Entity vehicle, Entity rider) {
        if (vehicle instanceof Mob mob && rider instanceof LivingEntity living && !mob.level().isClientSide && !mob.isNoAi()) {
            mob.setLastHurtByMob(living);
        }
    }
}
