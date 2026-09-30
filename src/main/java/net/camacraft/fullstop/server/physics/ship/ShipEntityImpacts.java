package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.vs.ShipContact;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.physics.Collisions;
import net.camacraft.camafy.physics.EntityStack;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.network.ShipContactPacket;
import net.camacraft.fullstop.common.physics.ShipSweptContact;
import net.camacraft.fullstop.common.particle.CollisionParticleSpawner;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.camacraft.fullstop.common.sound.FSSoundPlayer;
import net.camacraft.fullstop.server.physics.BodyPush;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.camacraft.fullstop.server.physics.damage.KineticDamageApplier;
import net.camacraft.fullstop.server.physics.damage.KineticDamageCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability;

/**
 * A moving Valkyrien Skies ship hitting entities. VS settles ships against entities on the game
 * thread without any event (it only keeps entities out of the hull), so this scans, once per
 * level tick, the entities around each ship that moves fast enough to matter.
 *
 * <p>For each body in reach (a ride stack counts as one body, from its bottom - a boat, cart or a
 * modded vehicle such as an MKW kart with everyone on it): the hull contact
 * the ship drives into hardest; the hit only counts if the SHIP supplies most of the closing
 * speed (an entity running into a hull is FullStop's ordinary wall impact). Then, in SI units:
 * the entity's share of the approach speed {@code a} is {@code dv = a * m_ship / (m_ship + m)};
 * it is knocked away along the contact normal by {@code (1 + restitution) * dv} and takes the
 * kinetic damage of losing {@code dv} against the hull block (same thresholds, hardness and
 * floor as a wall impact); the ship gets the opposite impulse. That is {@link ShipContactPhysics},
 * the law {@link ShipEntityHits} applies when the entity does the hitting, with {@code m_ship} the
 * ship's effective mass at the point hit (a hull striking with its tip mostly turns), and a hull
 * coming up from below inelastic. Across the normal the hull's sliding drags the body along by
 * Coulomb friction ({@code hullFriction} times the normal kick at most, never past moving with the
 * hull there), and a body knocked sideways off the ground gets {@code hitLift} of that as lift: a
 * spinning paddle flings what it sweeps into instead of shoving it along the ground (VS itself
 * only moves entities out of hulls by position). The push happens even when the hit
 * kills, so the body flies off the hull instead of dropping at it, and a player's launch reaches
 * their client unclamped (vanilla's motion packet caps each axis at 3.9 blocks a tick, 78 m/s).
 * Within a hit's cooldown a hull that catches the body up again pushes it on the same way, but
 * hurts it no more.
 * Pinned against something that doesn't give, it isn't knocked away but crushed
 * ({@code pinnedDamageMultiplier}), again for as long as the hull presses (standing on the ground,
 * the ground only counts when the hull presses it down into it: {@link #pinProbe}).
 *
 * <p>At any speed: the check looks ahead as far as the next physics frame can carry the hull, and
 * back through what the hull crossed in the latest frame, so a fast hull whose frames come late
 * or in bursts never passes through an entity (measured to 200 m/s). It is a swept test in the
 * ship's own frame ({@link ShipSweptContact}): the entity's box follows its motion relative to the ship -
 * an arc round the spin axis of a turning ship - and only the ship's actual blocks count, met at
 * the face the box first reaches. The hull's velocity is only ever taken on the hull; extrapolated
 * out to an entity 20 blocks from a fast rotor it made hundreds of m/s out of nothing and struck
 * what the hull would never reach.
 *
 * <p>A slime hull (a block in {@code fullstop:bouncy}) hurts nothing it hits: it springs the body
 * away at {@code slimeRestitution} instead of {@code entityRestitution}, from every side, as an
 * entity hitting slime bounces off it unhurt (the owner's slime ships).
 *
 * <p>Never hit: entities riding this ship (seated, standing on its deck, or carried with it by VS at
 * all - VS goes on moving what just left a deck with the ship for a second), spectators, entities
 * tagged {@code fullstop:ship_immune} or {@code fullstop:physics_blacklist}, and bodies whose impacts
 * another mod owns (MKW Mobility's karts and their drivers in a race). Creative players are knocked
 * about like anyone else, never hurt.
 *
 * <p>A slow hull (under {@code gentlePushSpeed} at the body's own size) pushes rather than strikes:
 * whatever it will reach within the tick is given the share of its speed that has it end the tick
 * against the face, so a pushed body moves along with the face instead of being kicked ahead, slowing
 * and being caught again.
 *
 * <p>Players on foot are not judged here at all: their own client reports the hulls that run into
 * them ({@link #clientContact}). A player moves on its client, and the server's ship poses run
 * ahead of what that client drew - a fast rotor's arm is metres further round - so a hit judged
 * here met the player where it wasn't, or missed it while its client was shoved along by the hull.
 */
public final class ShipEntityImpacts {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** One hit per ship per entity per this many ticks (knockback, damage, sound). */
    private static final int HIT_COOLDOWN_TICKS = 10;
    /** A non-player sunk deeper than this into the hull is set back outside it. */
    private static final double EMBED_PUSH_OUT_DEPTH = 0.2;
    /** How far past the entity, along the push, something solid means it is pinned. */
    private static final double PIN_PROBE = 0.25;
    /**
     * A push pointing down into the ground by more than this (the cosine against the entity's
     * up; about 45 degrees) presses a grounded entity into it: that pin is real (crushed against
     * the ground). A shallower one only slides it along the ground.
     */
    private static final double PRESSED_INTO_GROUND = 0.7;
    /** How far below its feet solid ground makes an entity grounded for the pin check. */
    private static final double GROUND_PROBE = 0.05;
    /** A server tick (s): a slow hull pushes what it will reach within one. */
    private static final double TICK_SECONDS = 0.05;

    private ShipEntityImpacts() {
    }

    /** The per-level cap on entities checked against hulls this tick (maxHitChecksPerTick). */
    static final class Budget {
        private int left;

        Budget(int checks) {
            this.left = checks;
        }

        boolean take() {
            return left-- > 0;
        }
    }

    /** The last strike each entity got from a hull, for the debug log and the game tests. */
    private static final Map<Entity, Struck> LAST_STRUCK = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * One strike by a hull: when, by which ship, how fast the hull closed on the body (m/s), when
     * the swept test met it (seconds from the tick: ahead of the next physics frame, or back
     * through the latest one), the velocity change the body got (m/s, world: along the normal,
     * friction, lift), and whether it was a hit (hurt, sound, cooldown started) or only the hull
     * pushing on within a hit's cooldown.
     */
    public record Struck(long tick, long shipId, double approach, double atSeconds, Vec3 pushMps, boolean hit) {}

    /** The last strike {@code entity} got from a hull, or null. */
    @Nullable
    public static Struck lastStruck(Entity entity) {
        return LAST_STRUCK.get(entity);
    }

    /**
     * Where to look for entities this ship could hit this tick, or null when it can't hit any:
     * in grace, static, or no part of the hull moving at pushMinSpeed. However fast a ship spins,
     * its hull never leaves the sphere its farthest point turns in around the centre of mass, so
     * the reach of a spin is capped there; only the ship's travel stretches it.
     */
    @Nullable
    static AABB hitBox(ShipRef ship, ShipTracker.State state, AABB bounds, long now) {
        if (state.inGrace(now) || ship.isStatic()) return null;
        Vec3 com = state.position();
        double radius = 0.0; // the farthest the hull reaches from its centre of mass
        for (int corner = 0; corner < 8; corner++) {
            double dx = ((corner & 1) == 0 ? bounds.minX : bounds.maxX) - com.x;
            double dy = ((corner & 2) == 0 ? bounds.minY : bounds.maxY) - com.y;
            double dz = ((corner & 4) == 0 ? bounds.minZ : bounds.maxZ) - com.z;
            radius = Math.max(radius, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        double spin = state.angularVelocity().length();
        double fastest = state.velocity().length() + spin * radius;
        // (at the ship's size: a 1/16 ship in VS Genesis's space pushes the 1/16 bodies there as slowly)
        if (fastest < SERVER.pushMinSpeed.get() * Math.min(1.0, ship.scale())) return null;
        double ahead = state.sweepSeconds(now);
        double back = sinceLastFrame(state);
        AABB sphere = new AABB(com.x - radius, com.y - radius, com.z - radius, com.x + radius, com.y + radius, com.z + radius);
        AABB turned = bounds.inflate(spin * radius * (ahead + back)).intersect(sphere.minmax(bounds));
        Vec3 v = state.velocity();
        return turned.expandTowards(v.scale(ahead)).expandTowards(v.scale(-back)).inflate(1.0);
    }

    static void process(ServerLevel level, ShipRef ship, ShipTracker.State state, AABB bounds, AABB hitBox,
                        List<Entity> nearby, Budget budget, ShipPhysicsServer.Pilot pilot) {
        Set<Entity> bodies = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Entity entity : nearby) {
            if (!entity.getBoundingBox().intersects(hitBox)) continue;
            Entity root = entity.getRootVehicle();
            if (!bodies.add(root)) continue;
            if (!ShipContactPhysics.isBody(root)) continue;
            String why = excluded(ship, root);
            if (why == null && clientReports(root)) why = "its own client reports this ship's hits on it";
            if (why != null) {
                if ((root instanceof ServerPlayer && SERVER.debugShipContactLog.get()) || SERVER.debugShipCollisionLog.get()) {
                    logSkip(level, ship, root, why);
                }
                continue;
            }
            if (!budget.take()) return;
            hit(level, ship, state, bounds, root, pilot);
        }
    }

    /** Last tick each player's skip was logged, per ship (debugShipContactLog: once a second, only while touching). */
    private static final Map<Entity, long[]> SKIP_LOGGED = Collections.synchronizedMap(new WeakHashMap<>());

    private static void logSkip(ServerLevel level, ShipRef ship, Entity player, String why) {
        if (!ship.touchesBlock(player.getBoundingBox().inflate(0.5))) return;
        long now = level.getGameTime();
        long[] last = SKIP_LOGGED.computeIfAbsent(player, p -> new long[]{Long.MIN_VALUE, Long.MIN_VALUE});
        if (last[0] == ship.id() && now - last[1] < 20) return;
        last[0] = ship.id();
        last[1] = now;
        LOGGER.info("[FullStop] ship {} touches {} - the server doesn't judge that hit: {}", ship.id(),
                player.getName().getString(), why);
    }

    /** Why a body is never hit by this ship, or null when it may be. */
    @Nullable
    private static final String STANDING_ON = "standing on it";
    private static final String CARRIED = "carried with it by VS";

    private static String excluded(ShipRef ship, Entity root) {
        if (DamageImmunityRules.unphysableIgnoringAi(root)) return "unphysable"; // items, orbs, blacklist, dead
        if (root.isSpectator()) return "spectator";
        // creative players are knocked about like anyone else (never hurt: DamageImmunityRules)
        if (root.getType().is(FullStopTags.SHIP_IMMUNE)) return "ship_immune";
        // A seat that lives on this ship (its root sits in the ship's shipyard).
        if (ship.equals(Ships.managing(root))) return "a seat of this ship";
        // Riding this ship: standing on its deck (VS's own record, or on the ground with a block of
        // it right underfoot - not merely above some part of the hull, as a boat is when a bow
        // reaches under it), or seated on it - or carried with it by VS at all (it left the deck
        // less than a second ago: VS still moves it with the ship, so the ship can't strike it).
        // VS's record counts only with the ship under the body: VS also records a body the hull merely touched from the
        // side (a mob floating in zero gravity beside a spinning arm), and left out, the arm went through it while VS
        // pushed it out of the arm a little at a time. A body VS really carries moves with the hull and meets it
        // closing at nothing, which the swept test below answers anyway.
        boolean underfoot = ship.isUnderfoot(root);
        if ((underfoot && ship.equals(ShipEntities.standingOn(root, 5))) || (root.onGround() && underfoot)) return STANDING_ON;
        for (Entity member : EntityStack.selfAndPassengers(root)) {
            if (ship.equals(ShipEntities.mountedTo(member))) return "seated on it";
            if (ShipCompat.carriedBy(member, ship) && (underfoot || member instanceof Player)) return CARRIED;
            // Another mod owns this body's impacts (an MKW kart and its driver in a race, or in
            // its own scripted hops). A hull is not an entity: a CONTACT claim (entity-meets-entity,
            // which MKW keeps for free-roaming karts) doesn't keep ships off.
            if (PhysicsAspect.IMPACT.in(PhysicsClaims.live(member))) return "IMPACT claimed";
        }
        return null;
    }

    private static void hit(ServerLevel level, ShipRef ship, ShipTracker.State state, AABB bounds, Entity root,
                            ShipPhysicsServer.Pilot pilot) {
        long now = level.getGameTime();
        FullStopCapability cap = grabCapability(root);
        // Within a hit's cooldown the hull may still push (it caught up again), but hurts no more.
        boolean cooling = cap != null && cap.isShipHitOnCooldown(ship.id(), now);

        AABB box = root.getBoundingBox();
        GravityFrame frame = cap != null ? cap.getFrame() : FullStopCapability.currentFrame(root);
        // The entity's own velocity (m/s) plus any carry a ship gives it: world motion.
        Vec3 own = ownVelocity(root, frame);
        Vec3 entityV = own.add(ShipCompat.shipDragVelocityMps(root));

        // Where the entity meets the hull, if it does before the next physics frame (or did during
        // the latest one): its motion relative to the ship, followed in the ship's frame along
        // the arc a turning hull makes of it, against the hull's actual blocks.
        ShipSweptContact.Relative motion = new ShipSweptContact.Relative(state.position(), state.velocity(),
                state.angularVelocity(), entityV);
        ShipSweptContact.Meeting meeting = ShipSweptContact.sweep(ship, bounds, motion, box, -sinceLastFrame(state),
                state.sweepSeconds(now));
        if (meeting == null) return;
        double t = meeting.t();

        // The contact the hull drives into hardest (in the ship's frame as it is now).
        ShipContact best = null;
        Vec3 bestPoint = null;
        Vec3 bestRelative = null;
        double approach = 0.0;
        Vec3 near = meeting.box().getCenter();
        for (ShipContact contact : meeting.contacts()) {
            Vec3 point = ShipSweptContact.facePoint(ship, contact, near);
            if (point == null) continue;
            Vec3 relative = motion.hullRelative(point, t);
            double closing = relative.dot(contact.normal());
            if (closing > approach) {
                approach = closing;
                best = contact;
                bestPoint = point;
                bestRelative = relative;
            }
        }
        if (best == null) return;
        Vec3 normalNow = best.normal(); // from the hull toward the entity: the way it is pushed out

        // The ship must be doing the hitting: its own speed into the entity at least what the
        // entity brings (running into a hull is ShipEntityHits' business, or an ordinary wall).
        // pushMinSpeed at the body's own size (a 1/16 body is pushed by a hull 1/16 as fast).
        double hullIn = motion.hullVelocity(bestPoint, t).dot(normalNow);
        double pushMin = SERVER.pushMinSpeed.get() * net.camacraft.camafy.compat.scale.EntityScale.motion(root);
        if (hullIn < Math.max(pushMin, -motion.turn(entityV, -t).dot(normalNow))) return;

        // a slow hull only pushes: no bounce, no lift (see gentlePushSpeed)
        boolean gentle = ShipContactPhysics.gentle(root, approach);
        // Within a hit's cooldown only a hull that has actually caught the body up again strikes it on - not one that
        // would, going by a velocity that hasn't caught up with the push. A slow hull's push is a contact, not a strike:
        // the face reaching the body within this tick pushes it now by as much as the face will have come into it by the
        // tick's end (the body ends the tick against the face, and moves with it); later than that, it waits. Pushed
        // only once it had sunk in, a body the hull had given its speed slid ahead, slowed (on the ground to half its
        // speed a tick), and was caught and kicked again - a push in jerks (the owner's stutter).
        if (t > 0.0 && (gentle ? t > TICK_SECONDS : cooling)) return;
        double share = gentle && t > 0.0 ? 1.0 - t / TICK_SECONDS : 1.0;

        // The shared two-body law (ShipContactPhysics), with the ship's effective mass where it hit
        // (a slime hull springs the body back harder, and hurts it not at all: see hurt).
        Vec3 normal = motion.turn(normalNow, t); // the same normal in the world, as they meet
        double effective = ShipInertia.effectiveMassKg(ship, state.mass(), bestPoint, normalNow);
        boolean weightless = Gravity.isWeightless(root);
        BlockState hull = level.getBlockState(best.pos());
        ShipContactPhysics.Exchange exchange = ShipContactPhysics.resolve(approach, ShipContactPhysics.bodyKg(root), effective,
                gentle ? 0.0 : ShipContactPhysics.restitution(normal, frame, weightless, hull));
        double entityKg = exchange.entityKg();
        double shipKg = exchange.shipKg();
        double speedLost = exchange.speedLost();
        double kick = exchange.kick() * share;

        // Friction: the hull sliding past the body drags it along, at most hullFriction times the
        // normal kick (Coulomb), and no further than until the two move together there.
        Vec3 slip = bestRelative.subtract(normalNow.scale(bestRelative.dot(normalNow)));
        double slipSpeed = slip.length();
        double shipShare = Double.isInfinite(shipKg) || !(shipKg > 0.0) ? 1.0 : shipKg / (shipKg + entityKg);
        double frictionDv = slipSpeed > 1.0e-6 ? Math.min(SERVER.hullFriction.get() * kick, shipShare * slipSpeed) : 0.0;
        Vec3 slipDirNow = frictionDv > 0.0 ? slip.scale(1.0 / slipSpeed) : Vec3.ZERO;
        Vec3 change = normal.scale(kick).add(motion.turn(slipDirNow, t).scale(frictionDv));
        // A body knocked sideways off its feet leaves the ground (as vanilla's knockback lifts it):
        // on the ground, friction would skid it to a stop within a few ticks, and the hull, still
        // coming, would only shove it along. Scaled by its gravity: none in zero gravity, where
        // there is no ground friction to beat and a lift would carry it off for good.
        double lift = 0.0;
        if (!gentle && root.onGround() && Math.abs(frame.vertical(normal)) < ShipContactPhysics.FROM_BELOW) {
            Vec3 up = frame.up();
            double across = change.subtract(up.scale(frame.vertical(change))).length();
            lift = SERVER.hitLift.get() * ShipContactPhysics.liftShare(root) * across;
            change = change.add(up.scale(lift));
        }
        change = ShipContactPhysics.capLaunch(root, entityV, change);

        boolean pinned = isPinned(level, ship, root, box, normal, frame);
        if (pinned && cooling) return; // crushed, and already hurt for this hit

        boolean hurt = !cooling && hurt(root, cap, hull, normal, frame, speedLost, approach, pinned, pilot, ship.id());

        Vec3 pushed = Vec3.ZERO;
        // Not isAlive(): a hit that kills still throws the body (it keeps moving through its
        // death animation), and a dead body is never hit again (unphysable).
        if (!pinned && !root.isRemoved()) {
            // To whoever simulates the body (BodyPush): a driven vehicle's client, through Camafy's
            // Impulses (an MKW kart's own speed); a player unclamped; anything else here.
            pushed = change;
            if (!BodyPush.push(root, change.scale(0.05), frame, own)) pushed = Vec3.ZERO;
            else BodyPush.releaseStruck(root, ship.id());
            pushOutIfEmbedded(level, ship, root, normal);

            if (SERVER.reactionImpulses.get() && !pushed.equals(Vec3.ZERO)) {
                // the contact force back on the ship, in its frame as it is now: normal and friction
                Vec3 impulse = normalNow.scale(exchange.impulse() * share).add(slipDirNow.scale(entityKg * frictionDv));
                ShipImpulses.applyCapped(level, ship, impulse.scale(-1.0), bestPoint);
            }
        }

        if (!cooling && !gentle) {
            hitEffects(level, root, bestPoint, hull, approach);
        }
        if (!cooling && cap != null) cap.markShipHit(ship.id(), now, HIT_COOLDOWN_TICKS);
        LAST_STRUCK.put(root, new Struck(now, ship.id(), approach, t, pushed, !cooling));

        if (SERVER.debugShipCollisionLog.get() || (root instanceof Player && SERVER.debugShipContactLog.get())) {
            LOGGER.info("[FullStop] ship {} {} {} (server-detected): approach {} m/s, met {} s {}, lost {} m/s, kick {} m/s, "
                            + "friction {} m/s, lift {} m/s, pinned={} hull={} normal=({}) entity {} kg vs ship {} kg (effective), "
                            + "own velocity ({}) m/s, hurt={}, cooldown {}, pushed ({}) m/s{}",
                    ship.id(), cooling ? "pushes on" : "hit", root.getName().getString(), fmt(approach), fmt(Math.abs(t)),
                    t >= 0.0 ? "ahead" : "back", fmt(speedLost), fmt(kick), fmt(frictionDv), fmt(lift), pinned,
                    net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(hull.getBlock()), fmt(normal),
                    fmt(entityKg), Double.isInfinite(shipKg) ? "?" : fmt(shipKg), fmt(own), hurt, cooling ? "running" : "started",
                    fmt(pushed), root instanceof ServerPlayer p && BodyPush.clientSimulated(p) ? " (sent to the client as a change)" : "");
        }
    }

    /**
     * Deals a hull strike's damage (the kinetic damage of losing {@code speedLost} against the
     * hull block, crushed when pinned), crediting the ship's pilot. Returns whether it hurt.
     */
    private static boolean hurt(Entity root, @Nullable FullStopCapability cap, BlockState hull, Vec3 normal, GravityFrame frame,
                                double speedLost, double approach, boolean pinned, ShipPhysicsServer.Pilot pilot, long shipId) {
        if (!SERVER.damageEntities.get()) return false;
        // slime hurts nothing: it bounces (as slime does when an entity runs into it)
        if (ShipSlimeHoney.bouncy(hull)) return false;
        double vertical = frame.vertical(normal);
        boolean hullAbove = vertical < -0.7;
        boolean hullBelow = vertical > 0.7;
        // Damage first: vanilla's own on-hurt knockback must not overwrite the push after it. The speed lost as
        // the body's own size sees it (a 1/16 player struck at 8 m/s is struck as a full-size one at 128).
        double damage = KineticDamageCalculator.damageForSpeedLost(root, ShipContactPhysics.atOwnScale(root, speedLost),
                hullAbove || hullBelow, List.of(hull)) * SERVER.entityDamageMultiplier.get();
        if (pinned) damage *= SERVER.pinnedDamageMultiplier.get();
        if (damage < 1) return false;
        Entity credited = SERVER.creditPilot.get() ? pilot.get() : null;
        DamageSource source = FullStopDamageSources.ship(root, credited,
                FullStopDamageSources.mutualVelocityDisplay(approach), pinned, hullAbove, hullBelow, shipId);
        boolean marked = root.hurtMarked;
        boolean hurt = KineticDamageApplier.applyShipHit(root, cap, source, damage, hullAbove, hullBelow);
        if (root instanceof ServerPlayer player && BodyPush.clientSimulated(player)) {
            // Vanilla's hurt marks the body's motion for sending to clients (unless it was hurt in
            // the last half second). A player's own client moves it and has the hit's kick, or gets
            // it as a change: the server's velocity for a player means nothing, and sent along it
            // overwrote the kick - a player a rotor arm struck dropped straight down.
            player.hurtMarked = marked;
        }
        return hurt;
    }

    private static void hitEffects(ServerLevel level, Entity root, Vec3 point, BlockState hull, double approach) {
        CollisionParticleSpawner.spawnParticle(level, point, Collision.CollisionType.SOLID, hull,
                net.camacraft.camafy.compat.scale.EntityScale.heightFactor(root));
        float volume = Mth.clamp((float) (approach * 0.05), 0.2f, 1.0f);
        float pitch = Mth.clamp(0.9f + (float) (approach / 100.0) * 0.8f, 0.9f, 1.7f);
        FSSoundPlayer.playSoundServer(root, hull.getSoundType().getFallSound(), SoundSource.BLOCKS, volume, pitch);
    }

    /**
     * Whether a player's contacts with hulls are its client's to report ({@link #clientContact}):
     * a real player with FullStop, moving on foot. Its client is where VS moves it out of a hull,
     * and where the hull is where the player saw it; the server's ship poses run ahead of the
     * client's, and a hit judged here met a player where it wasn't (or missed it where it was).
     */
    static boolean clientReports(Entity root) {
        return root instanceof ServerPlayer player && !player.isPassenger() && BodyPush.clientSimulated(player);
    }

    /**
     * A hull ran into {@code player}, as its own client saw it (see {@code ClientShipContacts}):
     * the client has already given itself the kick. Here, after checking the report is plausible
     * against what the server knows (the ship exists, is near, can move that fast there; the
     * player isn't riding it, and isn't one the ship may not hit): the damage for a new hit, the
     * ship's push back at the contact, the hit's sound, and a correction of the kick when the
     * ship's effective mass there makes the two-body share smaller than the client's "far
     * heavier" guess.
     */
    public static void clientContact(ServerPlayer player, ShipContactPacket report) {
        if (!(player.level() instanceof ServerLevel level) || !ShipCompat.collisionsActive()) return;
        boolean log = SERVER.debugShipContactLog.get() || SERVER.debugShipCollisionLog.get();
        long now = level.getGameTime();
        ShipRef ship = Ships.byId(level, report.shipId());
        if (ship == null || !ship.isLoaded()) {
            if (log) LOGGER.info("[FullStop] {} reported a hit by ship {}: no such ship loaded, ignored",
                    player.getName().getString(), report.shipId());
            return;
        }
        String why = excluded(ship, player);
        if ((STANDING_ON.equals(why) || CARRIED.equals(why))
                && FullStopCapability.currentFrame(player).vertical(report.normal()) < ShipContactPhysics.FROM_BELOW) {
            // What the server has the player standing on comes from its own replay of the player's
            // moves against ship poses that run ahead of the client's: an arm sweeping in at foot
            // height could count as underfoot for a tick, and its hit was thrown away. The client
            // already leaves out the deck it stands on; here only a hull rising from below is.
            why = null;
        }
        ShipTracker.State state = ShipTracker.of(level).latest(ship.id());
        if (why == null && state != null && state.inGrace(now)) why = "the ship is in its grace";
        Vec3 point = ship.toWorld(report.localPoint());
        Vec3 normal = ship.faceNormal(report.face());
        Vec3 com = ship.position();
        Vec3 v = ship.velocity();
        Vec3 w = ship.angularVelocity();
        if (why == null && (point == null || normal == null || com == null || v == null || w == null)) why = "the ship can't be resolved";
        if (why == null) {
            // Where the client met the hull, against where the player is here. This server's pose of the ship runs
            // ahead of the client's - a fast rotor is a good part of a turn further round - and the reported spot of the
            // hull has turned on with it: taken as a place, a 240 RPM arm's hit was 8 blocks from the player and thrown
            // away (no damage, no push back on the ship). Its distance from the ship's centre, which no turning
            // changes, is what must match the player's; the ship's travel and the player's own are allowed for.
            Vec3 centre = player.getBoundingBox().getCenter();
            double slack = 4.0 + report.ownMps().length() * 0.25 + v.length() * 0.25;
            double off = Math.abs(point.distanceTo(com) - centre.distanceTo(com));
            AABB hullBox = ship.worldAabb();
            if (off > slack) why = "too far from the player (" + fmt(off) + " blocks off the hull's reach)";
            else if (hullBox != null && !hullBox.inflate(slack).contains(centre)) why = "too far from the ship";
        }
        if (why != null) {
            if (log) LOGGER.info("[FullStop] {} reported a hit by ship {} (closing {} m/s): ignored, {}", player.getName().getString(),
                    ship.id(), fmt(report.closing()), why);
            return;
        }

        FullStopCapability cap = grabCapability(player);
        GravityFrame frame = cap != null ? cap.getFrame() : FullStopCapability.currentFrame(player);
        // Plausibility: nowhere can the hull move faster than its centre plus its spin at that
        // distance (and the ship's pose on the client is a little behind: room for a turn's worth).
        double hullBound = v.length() + w.length() * (point.distanceTo(com) + 2.0) + 5.0;
        double approach = Mth.clamp(report.closing(), 0.0, hullBound + report.ownMps().length());
        boolean cooling = !report.hit() || (cap != null && cap.isShipHitOnCooldown(ship.id(), now));

        double mass = ship.isStatic() ? Double.POSITIVE_INFINITY : ship.mass();
        if (!(mass > 0.0)) mass = Double.POSITIVE_INFINITY;
        double effective = ShipInertia.effectiveMassKg(ship, mass, point, normal);
        boolean weightless = Gravity.isWeightless(player);
        BlockState hull = level.getBlockState(report.block());
        if (hull.isAir()) hull = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        ShipContactPhysics.Exchange exchange = ShipContactPhysics.resolve(approach, ShipContactPhysics.bodyKg(player), effective,
                ShipContactPhysics.restitution(report.normal(), frame, weightless, hull));
        boolean pinned = isPinned(level, ship, player, player.getBoundingBox(), report.normal(), frame);

        boolean hurt = !cooling && hurt(player, cap, hull, report.normal(), frame, exchange.speedLost(), approach, pinned,
                new ShipPhysicsServer.Pilot(level, ship), ship.id());

        // The ship's share, which the client couldn't know: take off what the client gave itself
        // beyond the two-body kick along the normal (a light ship doesn't throw a player as hard).
        Vec3 applied = report.appliedMps();
        double appliedNormal = applied.dot(report.normal());
        double correction = appliedNormal > 0.0 ? exchange.kick() - Math.min(appliedNormal, (1.0 + exchange.restitution()) * approach) : 0.0;
        boolean corrected = false;
        if (correction < -0.5 && !pinned && !PhysicsClaims.handsOff(player, PhysicsAspect.MOTION)) {
            PacketHandler.sendPlayerImpulse(player, report.normal().scale(correction * 0.05));
            corrected = true;
        }

        double impulseNs = 0.0;
        if (SERVER.reactionImpulses.get() && !pinned) {
            // the contact force back on the ship: normal and the friction the client gave itself
            Vec3 tangential = applied.subtract(report.normal().scale(appliedNormal));
            // the lift is no contact force; in zero gravity the client gave none, so the whole
            // tangential part is friction
            if (!weightless) {
                Vec3 upPart = frame.up().scale(frame.vertical(tangential));
                tangential = tangential.subtract(upPart);
            }
            Vec3 impulse = normal.scale(exchange.impulse()).add(tangential.scale(exchange.entityKg()));
            impulseNs = ShipImpulses.applyCapped(level, ship, impulse.scale(-1.0), point).length();
        }

        if (!cooling && !ShipContactPhysics.gentle(player, approach)) {
            // where the hull met the player as its client saw it - on the player's side facing the hull - not where this
            // server's pose of a fast rotor has taken that spot of the hull since (up to a turn's worth further round)
            AABB box = player.getBoundingBox();
            Vec3 met = box.getCenter().subtract(report.normal().scale(0.5 * Math.min(box.getXsize(), box.getYsize())));
            hitEffects(level, player, met, hull, approach);
        }
        if (!cooling && cap != null) cap.markShipHit(ship.id(), now, HIT_COOLDOWN_TICKS);
        LAST_STRUCK.put(player, new Struck(now, ship.id(), approach, 0.0, applied, !cooling));
        if (log) {
            LOGGER.info("[FullStop] ship {} {} {} (reported by its client): closing {} m/s (client {}; hull {} m/s there, "
                            + "you {} m/s), entity {} kg vs ship {} kg (effective), lost {} m/s, kick {} m/s; the client gave "
                            + "itself ({}) m/s{}; pinned={}, hurt={}, cooldown {}, ship pushed {} N s", ship.id(),
                    cooling ? "pushes on" : "hit", player.getName().getString(), fmt(approach), fmt(report.closing()),
                    fmt(report.hullMps()), fmt(report.ownMps()), fmt(exchange.entityKg()),
                    Double.isInfinite(exchange.shipKg()) ? "inf" : fmt(exchange.shipKg()), fmt(exchange.speedLost()),
                    fmt(exchange.kick()), fmt(applied), corrected ? ", corrected by " + fmt(correction) + " m/s along the normal" : "",
                    pinned, hurt, cooling ? "running" : "started", fmt(impulseNs));
        }
    }

    /**
     * The body's own velocity (m/s, world). What the server simulates moves next with its
     * deltaMovement, which already holds a push given this tick (the measured velocity only
     * catches up once it has moved; a body that can't move, NoAI, never would) - less the
     * gravity a grounded body picked up after its move, which the ground takes back. A player's
     * is measured (its deltaMovement means nothing on the server), with what its client's
     * Valkyrien Skies added to its position taken out as its client measured it.
     */
    private static Vec3 ownVelocity(Entity root, GravityFrame frame) {
        if (root instanceof Player) return VelocityMath.entityVelocity(root);
        // (a body Pehkui resized moves by its motion scale's share of its deltaMovement: its real velocity)
        Vec3 own = net.camacraft.camafy.compat.scale.EntityScale.realVelocity(root, Gravity.getWorldVelocity(root, frame)).scale(20.0);
        if (root.onGround()) {
            double down = frame.vertical(own);
            if (down < 0.0) own = own.subtract(frame.up().scale(down));
        }
        return own;
    }

    /** Seconds of hull travel the latest frame brought, on the tick it arrived; 0 otherwise. */
    private static double sinceLastFrame(ShipTracker.State state) {
        return state.newFrame() ? Math.min(state.frameSeconds(), 0.5) : 0.0;
    }

    /**
     * Whether something solid right behind the entity (along the push) keeps it from going
     * anywhere: terrain, or another ship. See {@link #pinProbe} for which way "behind" is.
     */
    private static boolean isPinned(ServerLevel level, ShipRef ship, Entity root, AABB box, Vec3 normal, GravityFrame frame) {
        AABB beyond = box.move(pinProbe(level, root, box, normal, frame));
        if (Collisions.blockedByBlocks(level, root, beyond)) return true;
        for (ShipRef other : Ships.intersecting(level, beyond)) {
            if (!other.equals(ship) && other.touchesBlock(beyond)) return true;
        }
        return false;
    }

    /**
     * Where the pin check looks: {@link #PIN_PROBE} along the push, except for an entity standing
     * on the ground (in its own gravity frame) pushed along or slightly into it. The ground
     * stops only the part of the push that goes into it, so the probe drops that part and looks
     * the full distance along the ground: a hull whose normal dips a hair downward (rotation
     * noise, a ship pitched a little nose-down) no longer "pins" a mob standing in the open, and
     * a wall behind it still does. A push mostly into the ground ({@link #PRESSED_INTO_GROUND}, a
     * hull coming down on it) is left as it is: crushed against the ground is a real pin.
     */
    static Vec3 pinProbe(ServerLevel level, Entity root, AABB box, Vec3 normal, GravityFrame frame) {
        Vec3 up = frame.up();
        double along = normal.dot(up);
        if (along < 0.0 && along > -PRESSED_INTO_GROUND
                && (root.onGround() || Collisions.blockedByBlocks(level, root, box.move(up.scale(-GROUND_PROBE))))) {
            Vec3 across = normal.subtract(up.scale(along));
            double length = across.length();
            if (length > 1.0e-9) return across.scale(PIN_PROBE / length);
        }
        return normal.scale(PIN_PROBE);
    }

    /**
     * A non-player the hull swallowed deeper than {@link #EMBED_PUSH_OUT_DEPTH} is set back out
     * along the push, if there is room. Never a player: the server sends no position packet for
     * a plain setPos, and the next move packet would rubber-band them.
     */
    private static void pushOutIfEmbedded(ServerLevel level, ShipRef ship, Entity root, Vec3 normal) {
        if (root instanceof Player) return;
        double depth = 0.0;
        for (ShipContact contact : ship.contacts(root.getBoundingBox())) {
            depth = Math.max(depth, contact.depth());
        }
        if (depth <= EMBED_PUSH_OUT_DEPTH) return;
        Vec3 out = normal.scale(depth * ship.scale() + 0.01);
        AABB moved = root.getBoundingBox().move(out);
        if (level.noCollision(root, moved) && !ship.touchesBlock(moved)) {
            root.setPos(root.position().add(out));
        }
    }

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
