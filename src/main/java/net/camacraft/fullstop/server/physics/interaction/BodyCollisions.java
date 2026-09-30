package net.camacraft.fullstop.server.physics.interaction;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.minigame.MatchPolicies;
import net.camacraft.camafy.physics.EntityStack;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.physics.math.SweptBoxes;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.camacraft.fullstop.common.registry.ModEnchantments;
import net.camacraft.fullstop.common.util.EnchantmentUtils;
import net.camacraft.fullstop.server.physics.BodyPush;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.camacraft.fullstop.server.physics.damage.KineticDamageApplier;
import net.camacraft.fullstop.server.physics.damage.KineticDamageCalculator;
import net.camacraft.fullstop.server.physics.ship.ShipContactPhysics;
import net.camacraft.fullstop.server.physics.ship.ShipMaterials;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Bodies that run, fly or fall into each other - mobs, players, boats and carts, a mount with its
 * riders as one body, and blocks in flight (falling sand, a dropped anvil, at their block's mass) -
 * collide as two bodies ({@code enableEntityCollisions}, Entity Collisions).
 * Minecraft only nudges overlapping entities apart, a few hundredths of a block a tick, so a pig
 * flung at 30 m/s flew straight through a hovering one. Once a tick, after every entity has moved
 * (and before FullStop measures each one's own motion):
 * <ol>
 *   <li>each body's box is swept along this tick's motion against the bodies near its path
 *   ({@link SweptBoxes}), so a fast body can't skip past another between ticks, and the moment two
 *   first touch is found;</li>
 *   <li>a contact closing faster than {@code entityCollisionMinSpeed} is a collision; slower ones
 *   are left to vanilla's gentle pushing, so walking into a crowd stays what it was;</li>
 *   <li>along the contact normal the two exchange momentum by their masses (Camafy's body
 *   weights, riders included) with restitution {@code e}, the same two-body law as a body against
 *   a ship ({@link ShipContactPhysics}); friction acts across the normal, at most
 *   {@code entityCollisionFriction} times the push along it and never past moving together;</li>
 *   <li>each takes the kinetic damage of the speed it lost bringing the closing speed to zero
 *   ({@link KineticDamageCalculator#damageForSpeedLost}: the same thresholds, armour and
 *   immunities as any impact);</li>
 *   <li>bodies the server moves are put back where they met, so they don't end the tick inside or
 *   past each other; a player's share goes to its own client ({@link BodyPush}).</li>
 * </ol>
 * The normal treats bodies as upright cylinders: meeting across the top or bottom of the boxes is
 * vertical, anything else runs horizontally along the line between the two centres (two bodies
 * meeting head-on along a diagonal stop each other along it).
 *
 * <p>A falling block collides in the air like any body - two sand blocks meeting in the air bounce
 * apart, an anvil striking a mob in flight hits it with its whole mass - and still lands as a block
 * afterwards; one landing on a body standing on the ground is left to vanilla's rules (an anvil
 * hurts what it lands on, sand buries it).
 *
 * <p>A body landing on one that stands on the ground (not in zero gravity) crushes it: the ground
 * holds the lower one up, so it takes the lander's whole momentum into the contact (the speed it
 * "loses" is the closing speed times the lander's mass over its own - a golem landing on a
 * chicken flattens it, a chicken landing on a cow does nothing), and the lander - mobs aren't
 * solid to each other - falls on past it to the ground, where FullStop bills its landing as usual.
 *
 * <p>Left alone: spectators, what vanilla doesn't push either (armour stands, bats, a body on a
 * ladder), the ender dragon (multipart), NoAI mobs, sleepers, riders of the same mount, a rider and
 * the mount it just left, two minecarts (vanilla bumps carts), anything seated on a ship, bodies
 * whose contacts another mod owns ({@code CONTACT}: MKW's karts
 * and racers), bodies whose scoreboard teams don't collide, and pairs a minigame host says may
 * not touch ({@code MatchPolicies.canHarm}). A body another mod moves ({@code MOTION}) isn't
 * pushed, but what hits it still gets its share; one another mod owns the impacts of
 * ({@code IMPACT}) isn't hurt; one Gravity Unbound holds to its surface ({@code Gravity.isAnchored})
 * doesn't move at all, like the ground.
 */
public final class BodyCollisions {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A pair that collided doesn't collide again for this many ticks: one collision per meeting. */
    private static final int PAIR_COOLDOWN_TICKS = 10;
    /** A body knocked sideways off the ground gets this share of the push as lift, as vanilla's knockback lifts. */
    private static final double LIFT = 0.3;
    /** A normal leaning this close to a body's up (cos, about 45 degrees) is one body on top of the other. */
    private static final double ON_TOP = 0.7;
    /** Moving faster than three times its last measured speed plus this (m/s) is a teleport, not motion. */
    private static final double JUMP_MPS = 60.0;
    /** A body wearing Reflective keeps this much more of its momentum per level (and what hits it rebounds harder). */
    private static final double REFLECTIVE_KEEP = 0.1;
    private static final double REFLECTIVE_RETURN = 0.15;

    private static final Map<ServerLevel, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Entity, Record> LAST = Collections.synchronizedMap(new WeakHashMap<>());

    private BodyCollisions() {
    }

    /**
     * One body's side of a collision, for the debug log and the game tests.
     *
     * @param normal    world, from the other body toward this one
     * @param closing   the speed they met with along it (m/s)
     * @param kg        this body's mass (its ride stack's)
     * @param before    the world velocity it carried on with, which the change was added to (m/s)
     * @param change    the change of velocity it was given (m/s): zero when it wasn't moved (crushed
     *                  against the ground, held by Gravity Unbound, moved by another mod)
     * @param speedLost the speed it lost bringing the closing speed to zero (m/s): what hurts
     * @param damage    the damage dealt to it, before armour (0 when none)
     * @param crushed   it was landed on while standing on the ground
     */
    public record Record(long tick, Entity other, Vec3 normal, double closing, double restitution, double kg, double otherKg,
                        Vec3 before, Vec3 change, double speedLost, double damage, boolean crushed) {
    }

    /** The last collision {@code entity} (the root of its ride stack) had with another body, or null. */
    @Nullable
    public static Record last(Entity entity) {
        return LAST.get(entity);
    }

    /** Whether bodies collide at all (the Entity Collisions section). */
    public static boolean enabled() {
        return FullStopConfig.SERVER_SPEC.isLoaded() && SERVER.enableEntityCollisions.get();
    }

    private static final class State {
        /** Where each body was at the end of the last pass (world), by entity id. */
        Int2ObjectOpenHashMap<Vec3> ends = new Int2ObjectOpenHashMap<>();
        long tick = Long.MIN_VALUE;
        /** Pairs that just collided, and the game time until which they can't again. */
        final Long2LongOpenHashMap cooling = new Long2LongOpenHashMap();
    }

    /** A ride stack as this tick's pass sees it. */
    private static final class Body {
        final Entity root;
        /** The whole stack's box, now. */
        final AABB box;
        /** How far it moved this tick (world blocks). */
        final Vec3 move;
        /** Its world velocity through this tick (m/s): what it hit with. */
        final Vec3 velocity;
        /**
         * The world velocity it carries into the next tick (m/s): what the push is added to. For a
         * body the server moves that is its velocity after this tick's drag and gravity, which
         * vanilla has already applied; for one a client moves, the same as {@link #velocity}.
         */
        final Vec3 carried;
        /** Its own velocity, a ship's carry left out (m/s): what BodyPush builds a server-side player's motion on. */
        final Vec3 own;
        final double kg;
        /** A player's own client moves it (or its driver's client). */
        final boolean clientMoved;
        final boolean grounded;
        final boolean weightless;
        final GravityFrame frame;
        @Nullable
        final FullStopCapability cap;

        Body(Entity root, AABB box, Vec3 move, Vec3 velocity, Vec3 carried, Vec3 own, double kg, boolean clientMoved,
             boolean grounded, boolean weightless, GravityFrame frame, @Nullable FullStopCapability cap) {
            this.root = root;
            this.box = box;
            this.move = move;
            this.velocity = velocity;
            this.carried = carried;
            this.own = own;
            this.kg = kg;
            this.clientMoved = clientMoved;
            this.grounded = grounded;
            this.weightless = weightless;
            this.frame = frame;
            this.cap = cap;
        }

        AABB startBox() {
            return box.move(-move.x, -move.y, -move.z);
        }

        Vec3 centreAt(double t) {
            return box.getCenter().subtract(move.scale(1.0 - t));
        }

        /** Standing on the ground, with something pulling it there. */
        boolean standing() {
            return grounded && !weightless;
        }
    }

    /** Two bodies touching during this tick: {@code normal} points from {@code b} toward {@code a}. */
    private record Contact(Body a, Body b, double t, Vec3 normal, double closing, boolean overlapping) {
    }

    /**
     * This tick's collisions in {@code level}. Runs once per level tick, after every entity has
     * moved and before FullStop's per-entity pass measures them (a body stopped where it met another
     * is measured from there).
     */
    public static void tick(ServerLevel level) {
        if (!enabled()) {
            STATES.remove(level);
            return;
        }
        State state = STATES.computeIfAbsent(level, l -> new State());
        long now = level.getGameTime();
        Int2ObjectOpenHashMap<Vec3> before = state.tick == now - 1 ? state.ends : new Int2ObjectOpenHashMap<>();
        Int2ObjectOpenHashMap<Vec3> ends = new Int2ObjectOpenHashMap<>();
        Int2ObjectOpenHashMap<Body> bodies = new Int2ObjectOpenHashMap<>();
        double longestMove = 0.0;
        for (Entity entity : level.getAllEntities()) {
            if (entity.isPassenger()) continue;
            Body body = body(entity, before);
            if (body == null) continue;
            bodies.put(entity.getId(), body);
            ends.put(entity.getId(), entity.position());
            longestMove = Math.max(longestMove, body.move.length());
        }
        state.ends = ends;
        state.tick = now;
        if (now % 100L == 0L) state.cooling.long2LongEntrySet().removeIf(e -> e.getLongValue() <= now);
        if (bodies.size() < 2) return;

        double minSpeed = SERVER.entityCollisionMinSpeed.get();
        List<Contact> contacts = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        for (Body a : bodies.values()) {
            // a pair closes at most as fast as its two speeds together: one of them brings at least half
            if (Math.max(a.velocity.length(), a.move.length() * 20.0) < minSpeed * 0.5) continue;
            AABB path = a.box.minmax(a.startBox()).inflate(longestMove + 0.1);
            for (Entity near : level.getEntities(a.root, path, e -> bodies.containsKey(e.getRootVehicle().getId()))) {
                Body b = bodies.get(near.getRootVehicle().getId());
                if (b == null || b == a) continue;
                long key = pair(a.root.getId(), b.root.getId());
                if (!seen.add(key) || state.cooling.get(key) > now) continue;
                Contact contact = contact(a, b, minSpeed);
                if (contact != null) contacts.add(contact);
            }
        }
        if (contacts.isEmpty()) return;

        // In the order they happened; a body collides once a tick (the next tick sees what is left).
        contacts.sort(Comparator.comparingDouble(Contact::t));
        Set<Entity> done = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Contact contact : contacts) {
            Body a = contact.a(), b = contact.b();
            if (done.contains(a.root) || done.contains(b.root)) continue;
            if (!a.root.isAlive() || !b.root.isAlive() || !allowed(a, b)) continue;
            done.add(a.root);
            done.add(b.root);
            state.cooling.put(pair(a.root.getId(), b.root.getId()), now + PAIR_COOLDOWN_TICKS);
            resolve(level, contact, ends);
        }
    }

    // ------------------------------------------------------------------
    // Who is a body, and how it moved
    // ------------------------------------------------------------------

    @Nullable
    private static Body body(Entity root, Int2ObjectOpenHashMap<Vec3> before) {
        if (root instanceof Projectile) return null; // ProjectileCollisions
        boolean fallingBlock = root instanceof FallingBlockEntity;
        if (!(root instanceof LivingEntity || root instanceof Boat || root instanceof AbstractMinecart || fallingBlock
                || carriesLiving(root))) {
            return null;
        }
        // not ticked yet: where it was before its first move isn't known
        if (root.tickCount < 1) return null;
        // the ender dragon: its parts are what things hit, its flight is its phases', and vanilla
        // already knocks away what its wings strike
        if (root.isMultipartEntity()) return null;
        if (DamageImmunityRules.unphysableIgnoringAi(root) || root.isSpectator()) return null;
        // what vanilla never pushes isn't knocked about here either: armour stands, bats, anything on a ladder
        // (a falling block is never pushable, and is a body all the same: a block in flight)
        if (!root.isPushable() && !root.isVehicle() && !fallingBlock) return null;
        FullStopCapability cap = FullStopCapability.grabCapability(root);
        if (root instanceof Mob mob && (cap != null ? cap.isNoAiCached(mob) : mob.isNoAi())) return null;
        if (!takesPart(root)) return null;
        if (root.isVehicle()) {
            for (Entity rider : root.getIndirectPassengers()) {
                if (!takesPart(rider)) return null;
            }
        }
        // Just teleported, respawned, boarded or left a ship: its move this tick is no motion.
        if (cap != null && cap.getIsDamageImmune()) return null;

        Vec3 now = root.position();
        Vec3 then = before.get(root.getId());
        if (then == null) then = new Vec3(root.xOld, root.yOld, root.zOld);
        Vec3 move = now.subtract(then);
        double lastSpeed = cap != null ? cap.getCurrentWorldVelocity().length() : root.getDeltaMovement().length() * 20.0;
        if (move.length() * 20.0 > lastSpeed * 3.0 + JUMP_MPS) return null;

        boolean clientMoved = clientMoved(root);
        // A player's server position comes in its client's move packets, none, one or two a server
        // tick: the path it covered is this tick's move, its speed FullStop's per-client-tick measure.
        Vec3 velocity = clientMoved && cap != null ? cap.getCurrentWorldVelocity() : move.scale(20.0);
        GravityFrame frame = cap != null ? cap.getFrame() : FullStopCapability.currentFrame(root);
        // deltaMovement may be in a gravity frame (Gravity Unbound) and scaled by a size mod (Pehkui)
        Vec3 carried = clientMoved && cap != null ? velocity
                : net.camacraft.camafy.compat.scale.EntityScale.realVelocity(root, Gravity.getWorldVelocity(root, frame)).scale(20.0);
        Vec3 own = clientMoved && cap != null ? cap.getCurrentScaledVelocity()
                : velocity.subtract(ShipCompat.shipDragVelocityMps(root));
        AABB box = root.getBoundingBox();
        if (root.isVehicle()) {
            for (Entity rider : root.getIndirectPassengers()) box = box.minmax(rider.getBoundingBox());
        }
        boolean weightless = cap != null ? cap.isWeightless() : Gravity.isWeightless(root);
        // a block in flight weighs what its block weighs (Valkyrien Skies' mass for it, or a tonne)
        double kg = root instanceof FallingBlockEntity falling ? ShipMaterials.massKg(falling.getBlockState())
                : ShipContactPhysics.bodyKg(root);
        return new Body(root, box, move, velocity, carried, own, kg, clientMoved, root.onGround(), weightless, frame, cap);
    }

    /** Whether this member of a ride stack lets the stack collide: awake, not a spectator, not another mod's, not in a ship's seat. */
    private static boolean takesPart(Entity member) {
        if (member.isSpectator()) return false;
        if (member instanceof LivingEntity living && living.isSleeping()) return false;
        if (PhysicsClaims.cachedHandsOff(member, PhysicsAspect.CONTACT)) return false;
        // a seat on a ship moves only with the ship
        return ShipCompat.seatShip(member) == null;
    }

    private static boolean carriesLiving(Entity root) {
        if (!root.isVehicle()) return false;
        for (Entity passenger : root.getIndirectPassengers()) {
            if (passenger instanceof LivingEntity) return true;
        }
        return false;
    }

    /** Whether a player's own client moves this body: the player on foot, or the vehicle it drives. */
    private static boolean clientMoved(Entity root) {
        if (root instanceof ServerPlayer player) return BodyPush.clientSimulated(player);
        return root.getControllingPassenger() instanceof ServerPlayer driver && BodyPush.clientSimulated(driver);
    }

    // ------------------------------------------------------------------
    // Contacts
    // ------------------------------------------------------------------

    @Nullable
    private static Contact contact(Body a, Body b, double minSpeed) {
        // a rider just off its mount rides on beside it for a moment: that is no collision
        if (recentRideExchange(a.root, b.root)) return null;
        // carts on rails bump each other by vanilla's own rules
        if (a.root instanceof AbstractMinecart && b.root instanceof AbstractMinecart) return null;
        SweptBoxes.Touch touch = SweptBoxes.first(a.startBox(), a.move, b.startBox(), b.move);
        if (touch == null) return null;
        double t = touch.t();
        Vec3 normal = normal(touch, a, b);
        if (normal == null) return null;
        double closing = -a.velocity.subtract(b.velocity).dot(normal);
        if (closing < minSpeed) return null;
        return new Contact(a, b, t, normal, closing, touch.overlapping());
    }

    /** The contact normal, from {@code b} toward {@code a}, treating both as upright cylinders; null when there is none. */
    @Nullable
    private static Vec3 normal(SweptBoxes.Touch touch, Body a, Body b) {
        double t = touch.t();
        Vec3 d = a.centreAt(t).subtract(b.centreAt(t));
        Vec3 flat = new Vec3(d.x, 0.0, d.z);
        boolean ends = touch.axis() == Direction.Axis.Y;
        if (touch.overlapping()) {
            // already overlapping as the tick began: across whichever way they overlap least
            AABB boxA = a.startBox(), boxB = b.startBox();
            double vertical = Math.min(boxA.maxY, boxB.maxY) - Math.max(boxA.minY, boxB.minY);
            double sideways = (boxA.getXsize() + boxB.getXsize()) * 0.5 - flat.length();
            ends = vertical < sideways;
        }
        if (ends) {
            double up = d.y != 0.0 ? Math.signum(d.y) : Math.signum(b.velocity.y - a.velocity.y);
            return up == 0.0 ? null : new Vec3(0.0, up, 0.0);
        }
        if (flat.lengthSqr() > 1.0e-6) return flat.normalize();
        // centres right above each other, met from the side: against their horizontal motion
        Vec3 relative = a.move.subtract(b.move);
        Vec3 across = new Vec3(relative.x, 0.0, relative.z);
        return across.lengthSqr() > 1.0e-10 ? across.normalize().scale(-1.0) : null;
    }

    /** Whether either body is inside its mount/dismount grace with the other (as the legacy detector exempts it). */
    private static boolean recentRideExchange(Entity a, Entity b) {
        FullStopCapability capA = FullStopCapability.grabCapability(a);
        if (capA != null && capA.recentlyExchangedWith(b)) return true;
        FullStopCapability capB = FullStopCapability.grabCapability(b);
        return capB != null && capB.recentlyExchangedWith(a);
    }

    /**
     * What the rules around the two bodies allow: nothing another mod owns the contacts of right now,
     * a minigame host's rules for one harming or grabbing the other, and the scoreboard teams'
     * collision rules (as vanilla's pushing obeys them).
     */
    private static boolean allowed(Body a, Body b) {
        List<Entity> stackA = EntityStack.selfAndPassengers(a.root);
        List<Entity> stackB = EntityStack.selfAndPassengers(b.root);
        Entity byA = FullStopDamageSources.responsible(a.root);
        Entity byB = FullStopDamageSources.responsible(b.root);
        for (Entity x : stackA) {
            if (PhysicsClaims.handsOff(x, PhysicsAspect.CONTACT) || !MatchPolicies.canHarm(byB, x)) return false;
        }
        for (Entity y : stackB) {
            if (PhysicsClaims.handsOff(y, PhysicsAspect.CONTACT) || !MatchPolicies.canHarm(byA, y)) return false;
        }
        for (Entity x : stackA) {
            for (Entity y : stackB) {
                if (!teamsCollide(x, y)) return false;
            }
        }
        return true;
    }

    /** Vanilla's team collision rule for the pair (EntitySelector.pushableBy). */
    private static boolean teamsCollide(Entity x, Entity y) {
        Team tx = x.getTeam();
        Team ty = y.getTeam();
        if (tx == null && ty == null) return true;
        Team.CollisionRule rx = tx == null ? Team.CollisionRule.ALWAYS : tx.getCollisionRule();
        Team.CollisionRule ry = ty == null ? Team.CollisionRule.ALWAYS : ty.getCollisionRule();
        if (rx == Team.CollisionRule.NEVER || ry == Team.CollisionRule.NEVER) return false;
        boolean allied = tx != null && tx.isAlliedTo(ty);
        if ((rx == Team.CollisionRule.PUSH_OWN_TEAM || ry == Team.CollisionRule.PUSH_OWN_TEAM) && allied) return false;
        return rx != Team.CollisionRule.PUSH_OTHER_TEAMS && ry != Team.CollisionRule.PUSH_OTHER_TEAMS || allied;
    }

    // ------------------------------------------------------------------
    // Resolving
    // ------------------------------------------------------------------

    private static void resolve(ServerLevel level, Contact c, Int2ObjectOpenHashMap<Vec3> ends) {
        Body a = c.a(), b = c.b();
        Vec3 n = c.normal();
        // One on top of the other, the lower one standing on the ground: a crush, not an exchange. A falling
        // block landing on a standing body is vanilla's own affair (an anvil hurts what it lands on, sand buries it).
        if (b.frame.vertical(n) > ON_TOP && b.standing()) {
            if (!(a.root instanceof FallingBlockEntity)) crush(level, c, a, b, n);
            return;
        }
        if (a.frame.vertical(n.scale(-1.0)) > ON_TOP && a.standing()) {
            if (!(b.root instanceof FallingBlockEntity)) crush(level, c, b, a, n.scale(-1.0));
            return;
        }

        double e = SERVER.entityCollisionRestitution.get();
        // held to its surface by Gravity Unbound (plating, Surface Cling): it moves as little as the ground
        double invA = Gravity.isAnchored(a.root) ? 0.0 : 1.0 / a.kg;
        double invB = Gravity.isAnchored(b.root) ? 0.0 : 1.0 / b.kg;
        if (!(invA + invB > 0.0)) return;
        double reduced = 1.0 / (invA + invB);
        // What bringing the closing speed to zero takes (N s): what hurts, at the speed they met with.
        double stop = c.closing() * reduced;
        // The push works on the velocities they carry on with (vanilla has already taken this tick's
        // drag off a mob's), so that afterwards they part at exactly e times the speed they were
        // still closing at, and momentum is kept.
        Vec3 relative = a.carried.subtract(b.carried);
        double carriedClosing = Math.max(0.0, -relative.dot(n));
        double along = (1.0 + e) * carriedClosing * reduced;
        Vec3 slip = relative.subtract(n.scale(relative.dot(n)));
        double slipSpeed = slip.length();
        double across = slipSpeed > 1.0e-6 ? Math.min(SERVER.entityCollisionFriction.get() * along, slipSpeed * reduced) : 0.0;
        Vec3 impulse = n.scale(along);
        if (across > 0.0) impulse = impulse.subtract(slip.scale(across / slipSpeed));
        Vec3 changeA = impulse.scale(invA);
        Vec3 changeB = impulse.scale(-invB);

        // Reflective armour: its wearer keeps more of its momentum, and what hits it rebounds harder.
        int reflectA = reflective(a.root), reflectB = reflective(b.root);
        if (reflectA > 0 || reflectB > 0) {
            changeA = changeA.scale(Math.max(0.2, 1.0 - REFLECTIVE_KEEP * reflectA) * Math.min(2.0, 1.0 + REFLECTIVE_RETURN * reflectB));
            changeB = changeB.scale(Math.max(0.2, 1.0 - REFLECTIVE_KEEP * reflectB) * Math.min(2.0, 1.0 + REFLECTIVE_RETURN * reflectA));
        }

        // The speed each lost bringing the closing speed to zero: what hurts. One held fast loses none
        // of its own, and takes the push it resisted.
        double lostA = stop / a.kg;
        double lostB = stop / b.kg;

        // Damage first: vanilla's own on-hurt knockback is undone before the push.
        double damageA = hurt(a, b, n, c.closing(), lostA);
        double damageB = hurt(b, a, n.scale(-1.0), c.closing(), lostB);
        Vec3 pushedA = invA > 0.0 ? push(a, changeA, n) : Vec3.ZERO;
        Vec3 pushedB = invB > 0.0 ? push(b, changeB, n.scale(-1.0)) : Vec3.ZERO;

        // Where they met, not inside or past each other (a fast pair can cross completely within the
        // tick). Only what the server moves, and only what it pushed.
        if (!c.overlapping()) {
            if (!pushedA.equals(Vec3.ZERO)) putBack(a, c.t(), ends);
            if (!pushedB.equals(Vec3.ZERO)) putBack(b, c.t(), ends);
        }

        effects(level, c);
        long tick = level.getGameTime();
        LAST.put(a.root, new Record(tick, b.root, n, c.closing(), e, a.kg, b.kg, a.carried, pushedA, lostA, damageA, false));
        LAST.put(b.root, new Record(tick, a.root, n.scale(-1.0), c.closing(), e, b.kg, a.kg, b.carried, pushedB, lostB, damageB, false));
        log(c, e, lostA, lostB, damageA, damageB, pushedA, pushedB, null);
    }

    /**
     * {@code upper} came down on {@code lower}, which stands on the ground: the ground holds it up, so
     * it takes the lander's whole momentum into the contact, and nothing is pushed - the lander, mobs
     * not being solid to each other, falls on past it and lands, billed by FullStop's own landing.
     *
     * @param up the normal from the lower body toward the upper one
     */
    private static void crush(ServerLevel level, Contact c, Body upper, Body lower, Vec3 up) {
        double lost = c.closing() * upper.kg / lower.kg;
        double damage = hurt(lower, upper, up.scale(-1.0), c.closing(), lost);
        effects(level, c);
        long tick = level.getGameTime();
        LAST.put(lower.root, new Record(tick, upper.root, up.scale(-1.0), c.closing(), 0.0, lower.kg, upper.kg, lower.carried,
                Vec3.ZERO, lost, damage, true));
        LAST.put(upper.root, new Record(tick, lower.root, up, c.closing(), 0.0, upper.kg, lower.kg, upper.carried,
                Vec3.ZERO, 0.0, 0.0, false));
        boolean aBelow = lower == c.a();
        log(c, 0.0, aBelow ? lost : 0.0, aBelow ? 0.0 : lost, aBelow ? damage : 0.0, aBelow ? 0.0 : damage,
                Vec3.ZERO, Vec3.ZERO, lower);
    }

    /**
     * The victim's kinetic damage for losing {@code lost} m/s in a collision with {@code other}.
     *
     * @param n from the other body toward the victim
     * @return the damage dealt (before armour), 0 when none
     */
    private static double hurt(Body victim, Body other, Vec3 n, double closing, double lost) {
        double multiplier = SERVER.entityCollisionDamageMultiplier.get();
        if (!(multiplier > 0.0)) return 0.0;
        Entity root = victim.root;
        if (PhysicsClaims.handsOff(root, PhysicsAspect.IMPACT)) return 0.0;
        double up = victim.frame.vertical(n);
        boolean fromAbove = up < -ON_TOP; // the other came down on it
        boolean fromBelow = up > ON_TOP;  // it came down on the other
        // at the victim's own size (a 1/16 body losing 1 m/s loses sixteen of its own lengths a second)
        double damage = KineticDamageCalculator.damageForSpeedLost(root, ShipContactPhysics.atOwnScale(root, lost),
                fromAbove || fromBelow, List.of()) * multiplier;
        if (damage < 1.0) return 0.0;

        // Who brought the speed: the victim running into the other, the other into it, or both.
        double ownIn = -victim.velocity.dot(n);
        double otherIn = other.velocity.dot(n);
        String key;
        Entity by;
        FullStopDamageSources.VelocityDisplay shown;
        if (ownIn > 0.25 * closing && otherIn > 0.25 * closing) {
            key = "collided";
            by = other.root;
            shown = FullStopDamageSources.mutualVelocityDisplay(closing);
        } else if (otherIn >= ownIn) {
            key = fromAbove ? "crushed_by" : "hit_by";
            by = other.root;
            shown = FullStopDamageSources.velocityDisplay(other.velocity.length());
        } else {
            key = fromBelow ? "fell_on" : fromAbove ? "head" : "slammed";
            by = null;
            shown = FullStopDamageSources.velocityDisplay(victim.velocity.length());
        }
        DamageSource source = FullStopDamageSources.bodyCollision(root, other.root, by, shown, key);
        // Vanilla's hurt halves the victim's velocity and knocks it 0.4 away from the attacker: the
        // collision itself moves it (after this), so that is undone. A player's client gets the
        // collision's change, never the server's meaningless velocity for it.
        Vec3 keep = root.getDeltaMovement();
        boolean marked = root.hurtMarked;
        // (no capability: a collision is one blow, not part of a two-tick impact to top up)
        boolean hurt = KineticDamageApplier.applyShipHit(root, null, source, damage, fromAbove, fromBelow);
        root.setDeltaMovement(keep);
        if (victim.clientMoved) root.hurtMarked = marked;
        return hurt ? damage : 0.0;
    }

    /**
     * Gives the body its change of velocity (m/s) through whoever simulates it, a little lift when it
     * is knocked sideways off its feet, and at most the launch speed hits allow.
     *
     * @param away the contact normal pointing away from the other body
     * @return the change it was given, zero when another mod owns its motion
     */
    private static Vec3 push(Body body, Vec3 change, Vec3 away) {
        if (change.lengthSqr() < 1.0e-8) return Vec3.ZERO;
        // Knocked away sideways off its feet (not a rammer merely slowed): on the ground, friction would
        // skid it to a stop within a few ticks.
        if (body.standing() && Math.abs(body.frame.vertical(away)) < ON_TOP && body.carried.add(change).dot(away) > 0.5) {
            Vec3 up = body.frame.up();
            double sideways = change.subtract(up.scale(body.frame.vertical(change))).length();
            change = change.add(up.scale(LIFT * ShipContactPhysics.liftShare(body.root) * sideways));
        }
        change = ShipContactPhysics.capLaunch(body.root, body.carried, change);
        return BodyPush.push(body.root, change.scale(0.05), body.frame, body.own) ? change : Vec3.ZERO;
    }

    /** Puts a body the server moves back where it was when the two met, {@code t} into the tick. */
    private static void putBack(Body body, double t, Int2ObjectOpenHashMap<Vec3> ends) {
        if (body.clientMoved || body.root.isRemoved() || !(t < 1.0)) return;
        Vec3 at = body.root.position().subtract(body.move.scale(1.0 - t));
        body.root.setPos(at);
        ends.put(body.root.getId(), at);
    }

    private static int reflective(Entity root) {
        return root instanceof LivingEntity living ? EnchantmentUtils.totalArmorLevel(living, ModEnchantments.REFLECTIVE.get()) : 0;
    }

    /** A thud and a puff where they met, louder the faster. */
    private static void effects(ServerLevel level, Contact c) {
        double t = c.t();
        Vec3 at = c.a().centreAt(t).add(c.b().centreAt(t)).scale(0.5);
        float volume = Mth.clamp((float) (c.closing() * 0.04), 0.3f, 1.0f);
        float pitch = Mth.clamp(1.3f - (float) (c.closing() / 60.0), 0.6f, 1.2f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.NEUTRAL, volume, pitch);
        int count = Mth.clamp((int) (c.closing() / 4.0), 3, 12);
        level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, count, 0.2, 0.2, 0.2, 0.3);
    }

    private static void log(Contact c, double e, double lostA, double lostB, double damageA, double damageB, Vec3 pushedA,
                            Vec3 pushedB, @Nullable Body crushed) {
        if (!SERVER.debugImpactLog.get()) return;
        Body a = c.a(), b = c.b();
        String how = crushed == null ? "and" : crushed == b ? "crushed" : "was landed on by";
        LOGGER.info("[FullStop] body collision {} {} {}: closing {} m/s along ({}) {} into the tick, {} kg vs {} kg, e {}, "
                        + "lost {} / {} m/s, damage {} / {}, pushed ({}) / ({}) m/s{}",
                a.root.getName().getString(), how, b.root.getName().getString(), fmt(c.closing()),
                fmt(c.normal()), fmt(c.t()), fmt(a.kg), fmt(b.kg), fmt(e), fmt(lostA), fmt(lostB), fmt(damageA), fmt(damageB),
                fmt(pushedA), fmt(pushedB), c.overlapping() ? " (already overlapping)" : "");
    }

    private static long pair(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }
}
