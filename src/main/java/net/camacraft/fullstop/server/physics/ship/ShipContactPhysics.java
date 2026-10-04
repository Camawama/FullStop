package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.scale.EntityScale;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.physics.EntityStack;
import net.camacraft.camafy.weight.Weights;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * The one two-body collision law between an entity and a Valkyrien Skies hull, shared by both
 * directions - a ship driving into an entity ({@link ShipEntityImpacts}) and an entity running,
 * flying or falling into a ship ({@link ShipEntityHits}) - and by a rider landing on or jumping
 * off its deck ({@link ShipImpulses}, {@link ShipWeightHandler}). The same contact therefore
 * comes out the same whichever side of it is detected.
 *
 * <p>Along the contact normal only (no friction), with {@code a} the speed at which the two close
 * (the entity's velocity relative to the hull's material at the contact point), {@code m} the
 * entity's mass and {@code M} the ship's effective mass at that point ({@link ShipInertia}):
 * <pre>
 *   mu        = m M / (m + M)                  reduced mass
 *   speedLost = a M / (m + M)                  what the entity loses bringing the closing speed to 0
 *   kick      = (1 + e) speedLost              the entity's whole change of velocity
 *   impulse   = m kick = (1 + e) mu a          newton-seconds, entity away from the hull, ship the other way
 * </pre>
 * {@code speedLost} is what hurts (the kinetic-damage model of a wall impact with that speed).
 * A heavy entity against a light ship barely slows and hardly hurts; the ship takes the momentum.
 * Against a huge ship {@code M} dwarfs {@code m} and it is a wall.
 *
 * <p>Restitution {@code e} is {@code entityRestitution}, except for a hull meeting the entity
 * from below (a landing on a deck, or a deck rising under an entity): that is inelastic, so the
 * entity ends up moving with the deck instead of bouncing on it. Only where something pulls the
 * entity ({@link #fromBelow}): in zero gravity nothing lands or stands on a deck, and a hull
 * meeting a floating body from any side hits it like a wall would.
 */
public final class ShipContactPhysics {

    /** A hull whose normal leans this close to the entity's up (cos, about 45 degrees) is under it. */
    public static final double FROM_BELOW = 0.7;

    private ShipContactPhysics() {
    }

    /**
     * Whether a hull with outward normal {@code normal} (toward the body) meets it from below: a
     * landing on the deck or a deck rising under it, which carries the body instead of hitting
     * it. Never for a weightless body (VS Genesis space, an Ad Astra orbit, a zero-gravity
     * field): nothing stands on a deck there, so a deck rising into a floating body hits it
     * like any other hull.
     */
    public static boolean fromBelow(Vec3 normal, GravityFrame frame, boolean weightless) {
        return !weightless && frame.vertical(normal) > FROM_BELOW;
    }

    /**
     * The share of {@code hitLift} a body knocked off its feet gets: its gravity, capped at 1.
     * The lift only gets it clear of ground friction; in zero gravity there is none to beat and
     * the lift would carry it off for good, and on the Moon a full one would float it six times
     * as long.
     */
    public static double liftShare(Entity body) {
        return Math.min(1.0, Gravity.strength(body));
    }

    /**
     * One contact's outcome.
     *
     * @param approach  closing speed (m/s)
     * @param speedLost m/s the entity loses stopping against the hull (damage)
     * @param kick      m/s the entity's velocity changes along the normal, away from the hull
     * @param impulse   N s the ship receives, against the normal, at the contact
     * @param entityKg  the entity's (ride stack's) mass
     * @param shipKg    the ship's effective mass at the contact, +infinity for an immovable hull
     */
    public record Exchange(double approach, double restitution, double speedLost, double kick, double impulse,
                           double entityKg, double shipKg) {
    }

    /** The collision along {@code normal} (unit, pointing from the hull toward the entity). */
    public static Exchange resolve(double approach, double entityKg, double shipKg, double restitution) {
        double m = Math.max(entityKg, 1.0e-3);
        double share = Double.isInfinite(shipKg) || !(shipKg > 0.0) ? 1.0 : shipKg / (shipKg + m);
        double speedLost = Math.max(0.0, approach) * share;
        double kick = (1.0 + restitution) * speedLost;
        return new Exchange(approach, restitution, speedLost, kick, m * kick, m, shipKg);
    }

    /** {@link #resolve} for a contact at {@code at} (world) with normal {@code normal}, the ship's mass taken there. */
    public static Exchange resolve(ShipRef ship, double shipMassKg, Vec3 at, Vec3 normal, double approach, double entityKg,
                                   GravityFrame frame) {
        return resolve(ship, shipMassKg, at, normal, approach, entityKg, frame, false);
    }

    /** {@link #resolve(ShipRef, double, Vec3, Vec3, double, double, GravityFrame)} for a body that may be weightless. */
    public static Exchange resolve(ShipRef ship, double shipMassKg, Vec3 at, Vec3 normal, double approach, double entityKg,
                                   GravityFrame frame, boolean weightless) {
        double effective = ShipInertia.effectiveMassKg(ship, shipMassKg, at, normal);
        return resolve(approach, entityKg, effective, restitution(normal, frame, weightless));
    }

    /**
     * A hull striking a body, as the body's own side judges it (the local player's client, which
     * doesn't know the ship's mass: taken as far heavier than the body). Null when it isn't a hit:
     * the hull closes on the body slower than {@code minSpeed}, the body brings more of the
     * closing speed than the hull (it ran into the hull), or the hull rises under it (a deck to
     * stand on).
     *
     * @param own      the body's own velocity before the hull met it (m/s, world)
     * @param hull     the hull's velocity at the contact (m/s, world)
     * @param normal   the hull face's outward normal (toward the body)
     * @param grounded the body stood on the ground (a sideways kick lifts it off)
     * @param friction hullFriction
     * @param liftShare hitLift
     */
    @org.jetbrains.annotations.Nullable
    public static HullKick hullKick(Vec3 own, Vec3 hull, Vec3 normal, boolean grounded, GravityFrame frame, double minSpeed,
                                    double restitution, double friction, double liftShare) {
        return hullKick(own, hull, normal, grounded, frame, minSpeed, restitution, friction, liftShare, false);
    }

    /**
     * {@link #hullKick(Vec3, Vec3, Vec3, boolean, GravityFrame, double, double, double, double)} for a
     * body that may be weightless: then a hull from below hits it too (see {@link #fromBelow}).
     * {@code liftShare} is the caller's, already scaled by {@link #liftShare(Entity)}.
     */
    @org.jetbrains.annotations.Nullable
    public static HullKick hullKick(Vec3 own, Vec3 hull, Vec3 normal, boolean grounded, GravityFrame frame, double minSpeed,
                                    double restitution, double friction, double liftShare, boolean weightless) {
        double closing = hull.subtract(own).dot(normal);
        double hullIn = hull.dot(normal);
        if (closing < minSpeed || hullIn < Math.max(minSpeed, -own.dot(normal))) return null;
        if (fromBelow(normal, frame, weightless)) return null;
        double e = restitution;
        double kick = (1.0 + e) * closing;
        Vec3 relative = hull.subtract(own);
        Vec3 slip = relative.subtract(normal.scale(relative.dot(normal)));
        double slipSpeed = slip.length();
        double frictionDv = slipSpeed > 1.0e-6 ? Math.min(friction * kick, slipSpeed) : 0.0;
        Vec3 change = normal.scale(kick).add(frictionDv > 0.0 ? slip.scale(frictionDv / slipSpeed) : Vec3.ZERO);
        double lift = 0.0;
        if (grounded && Math.abs(frame.vertical(normal)) < FROM_BELOW) {
            Vec3 up = frame.up();
            lift = liftShare * change.subtract(up.scale(frame.vertical(change))).length();
            change = change.add(up.scale(lift));
        }
        return new HullKick(closing, hullIn, kick, frictionDv, lift, change);
    }

    /** {@link #hullKick}'s answer: closing speed, the hull's own speed into the body, and the body's velocity change. */
    public record HullKick(double closing, double hullIn, double kick, double friction, double lift, Vec3 change) {}

    /**
     * {@code change} shortened so the body leaves no faster than {@code maxHitLaunchSpeed} (or as fast as it
     * already went, if that was faster): the direction the hit sends it in is kept. The damage still follows the
     * hit's full speed. A 256 RPM rotor's arm moves at hundreds of m/s, and a player it launched that fast crossed
     * dozens of blocks a tick and brought the game down to a frame a second generating chunks. The limit is the
     * body's own: a player shrunk to 1/16 (VS Genesis's space) is held to 1/16 of it in the world.
     *
     * @param own the body's world velocity before the hit (m/s)
     */
    public static Vec3 capLaunch(Entity body, Vec3 own, Vec3 change) {
        double max = SERVER.maxHitLaunchSpeed.get() * EntityScale.motion(body);
        if (!(max > 0.0)) return change;
        Vec3 after = own.add(change);
        double speed = after.length();
        double limit = Math.max(max, own.length());
        if (!(speed > limit)) return change;
        return after.scale(limit / speed).subtract(own);
    }

    /**
     * Whether a hull closing on {@code body} at {@code closingMps} (world) only pushes it along: slower than
     * {@code gentlePushSpeed} at the body's own scale. A push doesn't bounce the body off the hull or lift it:
     * bounced, it flew ahead of a slow hull, stopped, and was caught and bounced again, a stutter every few ticks
     * where a moving wall simply shoves.
     */
    public static boolean gentle(Entity body, double closingMps) {
        return closingMps / EntityScale.motion(body) < SERVER.gentlePushSpeed.get();
    }

    /**
     * A body's world speed as its own size sees it (m/s): a player shrunk to 1/16 hit at 8 m/s is hit as a
     * full-size player is at 128. Pehkui moves a shrunk body by 1/16 of its velocity (Camafy's
     * {@link EntityScale#motion}), so all its own speeds are 1/16 as large in the world.
     */
    public static double atOwnScale(Entity body, double worldMps) {
        return worldMps / EntityScale.motion(body);
    }

    /** Restitution of a contact whose hull normal is {@code normal} (see the class notes). */
    public static double restitution(Vec3 normal, GravityFrame frame) {
        return restitution(normal, frame, false);
    }

    /** {@link #restitution(Vec3, GravityFrame)} for a body that may be weightless (no landings: see {@link #fromBelow}). */
    public static double restitution(Vec3 normal, GravityFrame frame, boolean weightless) {
        return fromBelow(normal, frame, weightless) ? 0.0 : SERVER.entityRestitution.get();
    }


    /**
     * {@link #restitution(Vec3, GravityFrame, boolean)} for a contact with the hull block {@code hull}: slime
     * ({@code fullstop:bouncy}) springs the body back at {@code slimeRestitution} instead, from every side - a slime
     * deck rising under a body bounces it as a slime floor does - and (see {@link ShipEntityImpacts}) never hurts it.
     */
    public static double restitution(Vec3 normal, GravityFrame frame, boolean weightless,
                                     @org.jetbrains.annotations.Nullable net.minecraft.world.level.block.state.BlockState hull) {
        if (ShipSlimeHoney.bouncy(hull)) return SERVER.slimeRestitution.get();
        double e = restitution(normal, frame, weightless);
        return softStriker(hull) ? e * SOFT_SPRING : e;
    }

    /** How much of {@code entityRestitution} a soft striking block springs back with ({@link #softStriker}). */
    public static final double SOFT_SPRING = 1.0 / 3.0;

    /**
     * Whether {@code block}, striking a body (a hull's block, a Create contraption's), is soft: wool, hay, leaves,
     * moss, sponge, dripleaves ({@code #fullstop:cushioning}, {@code #fullstop:soft_landing}), wool carpets and beds.
     * It deals only {@code softStrikeDamage} of the strike's damage ({@link #strikeDamageShare}) and springs back less
     * ({@link #SOFT_SPRING}); the body is still carried along at the block's speed.
     */
    public static boolean softStriker(@org.jetbrains.annotations.Nullable net.minecraft.world.level.block.state.BlockState block) {
        return block != null && (block.is(net.camacraft.fullstop.common.physics.rules.FullStopTags.CUSHIONING)
                || block.is(net.camacraft.fullstop.common.physics.rules.FullStopTags.SOFT_LANDING)
                || block.is(net.minecraft.tags.BlockTags.WOOL_CARPETS) || block.is(net.minecraft.tags.BlockTags.BEDS));
    }

    /** The share of a strike's damage {@code block} deals as the striking block: {@code softStrikeDamage} if soft, else 1. */
    public static double strikeDamageShare(@org.jetbrains.annotations.Nullable net.minecraft.world.level.block.state.BlockState block) {
        return softStriker(block) ? SERVER.softStrikeDamage.get() : 1.0;
    }

    /** The mass (kg) a ride stack hits and is hit with: every body in it, what they carry left out. */
    public static double bodyKg(Entity root) {
        return Math.max(EntityStack.sum(root, Weights::bodyMassKg), 1.0e-3);
    }

    /**
     * Whether this ride stack is something a hull can strike: a living body, a boat, cart or
     * falling block, anything solid that other entities collide with (a modded vehicle such as
     * an Automobility/MKW kart is a plain Entity that declares itself so) - or any vehicle
     * carrying a living rider.
     */
    public static boolean isBody(Entity root) {
        if (root instanceof LivingEntity || root instanceof Boat || root instanceof AbstractMinecart
                || root instanceof FallingBlockEntity || root.canBeCollidedWith()) {
            return true;
        }
        for (Entity passenger : root.getIndirectPassengers()) {
            if (passenger instanceof LivingEntity) return true;
        }
        return false;
    }
}
