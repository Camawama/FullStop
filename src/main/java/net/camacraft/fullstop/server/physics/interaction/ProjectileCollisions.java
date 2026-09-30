package net.camacraft.fullstop.server.physics.interaction;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.weight.Weights;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.mixin.AbstractArrowAccessor;
import net.camacraft.fullstop.common.mixin.ProjectileHitInvoker;
import net.camacraft.fullstop.common.physics.math.SweptBoxes;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
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
 * Projectiles that meet in the air ({@code enableProjectileCollisions}, Projectile Collisions).
 * Vanilla lets projectiles fly through each other: a projectile's own hit test only looks for
 * "pickable" things (mobs, fireballs, shulker bullets), so an arrow can shoot a fireball down, but
 * two arrows, a snowball and a fireball, or anything a mod throws pass through one another. Once a
 * tick, after every entity has moved, each
 * projectile in flight is swept along this tick's motion against the others near its path
 * ({@link SweptBoxes}: two arrows closing at 120 m/s cross six blocks a tick), and a pair whose
 * boxes touch while closing collides where they met:
 * <ul>
 *   <li>arrows (spectral, tipped and modded ones too) and tridents - {@link Kind#RIGID} - exchange
 *   momentum by their masses (Camafy's weights: an arrow 0.05 kg, a trident 2) along the line
 *   between their centres, with {@code projectileRestitution}, and a little friction across it.
 *   Two arrows meeting head-on stop and tumble down; a glancing hit deflects both; an arrow hitting
 *   a trident bounces off it. After that each is an ordinary arrow: it lands, sticks and can be
 *   picked up as its pickup rule allows. A deflected arrow is no longer a critical shot;</li>
 *   <li>every other projectile - {@link Kind#BREAKS}: thrown items (eggs, snowballs, splash and
 *   lingering potions, ender pearls, bottles o' enchanting), fireballs and wither skulls, shulker
 *   bullets, llama spit, and whatever a mod throws or shoots - meets the other as it would meet
 *   anything it hits: its own hit behaviour runs with the other projectile as what it struck (an egg
 *   bursts and may hatch a chick, a snowball puffs, a potion splashes there, a bottle spills its
 *   experience, an ender pearl lands its thrower there, a fireball or wither skull explodes there, a
 *   modded projectile does whatever it does). The exchange is inelastic, so what survives is slowed
 *   or turned by the momentum the broken one carried.</li>
 * </ul>
 * Forge's {@link ProjectileImpactEvent} is fired for each of the two, the other as the entity hit:
 * a mod that has one skip the hit keeps both flying. Never: projectiles a volley fired together
 * (the same owner, both a few ticks old - a multishot crossbow's arrows leave it overlapping),
 * ones stuck in a block, returning tridents and other no-clip ones, riders and vehicles, and the
 * {@code #fullstop:no_projectile_collision} tag (fishing bobbers, firework rockets, grappling
 * hooks: their hits are their own business), and anything whose contacts another mod owns.
 */
public final class ProjectileCollisions {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Closing speed (m/s) below which two projectiles only touch. */
    private static final double MIN_CLOSING = 1.0;
    /** Friction across the contact: at most this share of the push along it, never past moving together. */
    private static final double FRICTION = 0.2;
    /** A pair that collided doesn't collide again for this many ticks. */
    private static final int PAIR_COOLDOWN_TICKS = 10;
    /** Projectiles of one owner that are both younger than this (ticks) left the bow together. */
    private static final int VOLLEY_TICKS = 3;
    /** A projectile moving further than this in a tick (blocks) went through a portal or gateway. */
    private static final double MAX_MOVE = 20.0;
    /** What a projectile Camafy has no weight for weighs (an arrow). */
    private static final double DEFAULT_KG = 0.05;

    private static final Map<ServerLevel, Long2LongOpenHashMap> COOLING = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Entity, Record> LAST = Collections.synchronizedMap(new WeakHashMap<>());

    private ProjectileCollisions() {
    }

    /** How a projectile takes a hit in the air. */
    public enum Kind {
        /** Arrows and tridents: they survive it and exchange momentum. */
        RIGID,
        /** Everything else: it runs its own hit on what it met (thrown items break, fireballs explode). */
        BREAKS
    }

    /**
     * One projectile's side of a collision, for the debug log and the game tests.
     *
     * @param normal from the other projectile toward this one (world)
     * @param closing the speed they met with along it (m/s)
     * @param before the velocity it carried on with, which the change was added to (m/s)
     * @param change the change of velocity it was given (m/s)
     * @param broke  it broke (ran its own hit behaviour)
     */
    public record Record(long tick, Entity other, Vec3 normal, double closing, double restitution, double kg, double otherKg,
                         Vec3 before, Vec3 change, boolean broke) {
    }

    /** The last mid-air collision {@code projectile} had, or null. */
    @Nullable
    public static Record last(Entity projectile) {
        return LAST.get(projectile);
    }

    /** Whether projectiles collide at all (the Projectile Collisions section). */
    public static boolean enabled() {
        return FullStopConfig.SERVER_SPEC.isLoaded() && SERVER.enableProjectileCollisions.get();
    }

    /** How {@code projectile} takes a hit from another in the air right now, or null when it doesn't. */
    @Nullable
    public static Kind kind(Projectile projectile) {
        if (projectile.isRemoved() || !projectile.isAlive() || projectile.noPhysics) return null;
        // not ticked yet: where it was before its first move isn't known (a constructor that only sets the position)
        if (projectile.tickCount < 1) return null;
        if (projectile.isPassenger() || projectile.isVehicle()) return null;
        // whose hits are their own business: bobbers, fireworks, grappling hooks, what a pack adds to the tag
        if (projectile.getType().is(FullStopTags.NO_PROJECTILE_COLLISION)) return null;
        Kind kind;
        if (projectile instanceof AbstractArrow arrow) {
            if (((AbstractArrowAccessor) arrow).fullstop$inGround()) return null;
            kind = Kind.RIGID;
        } else {
            // every other projectile - thrown items, fireballs and wither skulls, shulker bullets, llama spit, a
            // mod's own - meets the other as it meets anything: its own hit behaviour runs. (Vanilla's own hit
            // test still runs first where it can see the other: an arrow shooting a fireball down stays vanilla's.)
            kind = Kind.BREAKS;
        }
        return PhysicsClaims.cachedHandsOff(projectile, PhysicsAspect.CONTACT) ? null : kind;
    }

    /** A projectile in flight as this tick's pass sees it. */
    private record Flying(Projectile p, Kind kind, AABB box, Vec3 move, double kg) {
        AABB startBox() {
            return box.move(-move.x, -move.y, -move.z);
        }

        Vec3 centreAt(double t) {
            return box.getCenter().subtract(move.scale(1.0 - t));
        }

        /** Where the projectile itself was, {@code t} into the tick. */
        Vec3 pointAt(double t) {
            return p.position().subtract(move.scale(1.0 - t));
        }

        /** Its velocity through this tick (m/s): projectiles move by exactly their velocity each tick. */
        Vec3 velocity() {
            return move.scale(20.0);
        }
    }

    /** Two projectiles touching during this tick; {@code normal} points from {@code b} toward {@code a}. */
    private record Contact(Flying a, Flying b, double t, Vec3 normal, double closing) {
    }

    /** This tick's mid-air projectile collisions in {@code level}, after every entity has moved. */
    public static void tick(ServerLevel level) {
        if (!enabled()) {
            COOLING.remove(level);
            return;
        }
        Int2ObjectOpenHashMap<Flying> flying = new Int2ObjectOpenHashMap<>();
        double longestMove = 0.0;
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Projectile projectile)) continue;
            Kind kind = kind(projectile);
            if (kind == null) continue;
            Vec3 move = projectile.position().subtract(projectile.xOld, projectile.yOld, projectile.zOld);
            if (move.length() > MAX_MOVE) continue;
            flying.put(projectile.getId(), new Flying(projectile, kind, projectile.getBoundingBox(), move, kg(projectile)));
            longestMove = Math.max(longestMove, move.length());
        }
        long now = level.getGameTime();
        Long2LongOpenHashMap cooling = COOLING.computeIfAbsent(level, l -> new Long2LongOpenHashMap());
        if (now % 100L == 0L) cooling.long2LongEntrySet().removeIf(e -> e.getLongValue() <= now);
        if (flying.size() < 2) return;

        List<Contact> contacts = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        for (Flying a : flying.values()) {
            if (a.move.lengthSqr() < 1.0e-8) continue; // a still one is found by what moves into it
            AABB path = a.box.minmax(a.startBox()).inflate(longestMove + 0.05);
            for (Entity near : level.getEntities(a.p, path, e -> flying.containsKey(e.getId()))) {
                Flying b = flying.get(near.getId());
                if (b == null) continue;
                long key = pair(a.p.getId(), b.p.getId());
                if (!seen.add(key) || cooling.get(key) > now || volley(a.p, b.p)) continue;
                Contact contact = contact(a, b);
                if (contact != null) contacts.add(contact);
            }
        }
        if (contacts.isEmpty()) return;

        contacts.sort(Comparator.comparingDouble(Contact::t));
        Set<Entity> done = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Contact contact : contacts) {
            Projectile a = contact.a().p(), b = contact.b().p();
            if (done.contains(a) || done.contains(b) || a.isRemoved() || b.isRemoved()) continue;
            done.add(a);
            done.add(b);
            cooling.put(pair(a.getId(), b.getId()), now + PAIR_COOLDOWN_TICKS);
            resolve(level, contact);
        }
    }

    private static double kg(Projectile projectile) {
        double kg = Weights.bodyMassKg(projectile);
        return kg > 1.0e-4 && Double.isFinite(kg) ? kg : DEFAULT_KG;
    }

    /** Fired together by one owner: a multishot volley leaves the crossbow with its arrows overlapping. */
    private static boolean volley(Projectile a, Projectile b) {
        if (a.tickCount >= VOLLEY_TICKS || b.tickCount >= VOLLEY_TICKS) return false;
        Entity owner = a.getOwner();
        return owner != null && owner == b.getOwner();
    }

    @Nullable
    private static Contact contact(Flying a, Flying b) {
        SweptBoxes.Touch touch = SweptBoxes.first(a.startBox(), a.move, b.startBox(), b.move);
        if (touch == null) return null;
        double t = touch.t();
        // Round things more or less: along the line between them as they met - between their own
        // points, not their boxes' centres (an arrow's box is taller than an egg's: thrown from the same
        // height, the centres differed by an eighth of a block and a head-on hit glanced 18 degrees).
        Vec3 d = a.pointAt(t).subtract(b.pointAt(t));
        Vec3 normal;
        if (d.lengthSqr() > 1.0e-8) {
            normal = d.normalize();
        } else {
            Vec3 relative = a.move.subtract(b.move);
            if (relative.lengthSqr() < 1.0e-10) return null;
            normal = relative.normalize().scale(-1.0);
        }
        double closing = -a.velocity().subtract(b.velocity()).dot(normal);
        if (closing < MIN_CLOSING) return null;
        return new Contact(a, b, t, normal, closing);
    }

    private static void resolve(ServerLevel level, Contact c) {
        Flying a = c.a(), b = c.b();
        Vec3 n = c.normal();
        double t = c.t();
        Vec3 atA = a.centreAt(t), atB = b.centreAt(t);
        Vec3 point = atA.add(atB).scale(0.5);

        // Each hits the other, as far as Forge's impact event goes: a mod may have one pass through.
        if (skips(a.p, b.p, point) || skips(b.p, a.p, point)) return;

        boolean breaks = a.kind == Kind.BREAKS || b.kind == Kind.BREAKS;
        double e = breaks ? 0.0 : SERVER.projectileRestitution.get();
        double invA = 1.0 / a.kg, invB = 1.0 / b.kg;
        double reduced = 1.0 / (invA + invB);
        // On the velocities they carry on with (this tick's drag and gravity already taken off): they
        // part at exactly e times the speed they were still closing at, momentum kept.
        Vec3 carriedA = a.p.getDeltaMovement().scale(20.0), carriedB = b.p.getDeltaMovement().scale(20.0);
        Vec3 relative = carriedA.subtract(carriedB);
        double along = (1.0 + e) * Math.max(0.0, -relative.dot(n)) * reduced;
        Vec3 slip = relative.subtract(n.scale(relative.dot(n)));
        double slipSpeed = slip.length();
        double across = slipSpeed > 1.0e-6 ? Math.min(FRICTION * along, slipSpeed * reduced) : 0.0;
        Vec3 impulse = n.scale(along);
        if (across > 0.0) impulse = impulse.subtract(slip.scale(across / slipSpeed));
        Vec3 changeA = impulse.scale(invA);
        Vec3 changeB = impulse.scale(-invB);

        // Where they met: both go back there, and whatever happens next happens there.
        a.p.setPos(a.p.position().subtract(a.move.scale(1.0 - t)));
        b.p.setPos(b.p.position().subtract(b.move.scale(1.0 - t)));

        apply(level, a, b.p, changeA, point);
        apply(level, b, a.p, changeB, point);
        if (!breaks) clack(level, a, b, point, c.closing());

        long tick = level.getGameTime();
        LAST.put(a.p, new Record(tick, b.p, n, c.closing(), e, a.kg, b.kg, carriedA, changeA, a.kind == Kind.BREAKS));
        LAST.put(b.p, new Record(tick, a.p, n.scale(-1.0), c.closing(), e, b.kg, a.kg, carriedB, changeB,
                b.kind == Kind.BREAKS));
        if (SERVER.debugImpactLog.get()) {
            LOGGER.info("[FullStop] projectile collision {} and {}: closing {} m/s along ({}) {} into the tick, {} kg vs {} kg, "
                            + "e {}, changes ({}) / ({}) m/s{}",
                    a.p.getName().getString(), b.p.getName().getString(), fmt(c.closing()), fmt(n), fmt(t), fmt(a.kg), fmt(b.kg),
                    fmt(e), fmt(changeA), fmt(changeB), breaks ? ", broke " + (a.kind == Kind.BREAKS ? a.p.getName().getString() : "")
                            + (a.kind == Kind.BREAKS && b.kind == Kind.BREAKS ? " and " : "")
                            + (b.kind == Kind.BREAKS ? b.p.getName().getString() : "") : "");
        }
    }

    /** Whether Forge's impact event for {@code projectile} hitting {@code other} says it passes through. */
    private static boolean skips(Projectile projectile, Entity other, Vec3 point) {
        ProjectileImpactEvent.ImpactResult result = ForgeEventFactory.onProjectileImpactResult(projectile,
                new EntityHitResult(other, point));
        return result == ProjectileImpactEvent.ImpactResult.SKIP_ENTITY;
    }

    /**
     * One side of the collision: a thrown item breaks, as on anything it hits (its own hit behaviour,
     * the other projectile as what it hit); an arrow or trident takes its change of velocity (m/s).
     */
    private static void apply(ServerLevel level, Flying side, Projectile other, Vec3 change, Vec3 point) {
        Projectile p = side.p;
        if (side.kind == Kind.BREAKS) {
            // Watching clients see it where it broke (the burst plays where their copy of it is).
            level.getChunkSource().broadcast(p, new ClientboundTeleportEntityPacket(p));
            ((ProjectileHitInvoker) p).fullstop$onHit(new EntityHitResult(other, point));
            if (p.isRemoved()) return;
            // (a subclass that survives its hit flies on with its share)
        }
        p.setDeltaMovement(p.getDeltaMovement().add(change.scale(0.05)));
        p.hasImpulse = true;
        p.hurtMarked = true;
        // knocked off its line: no longer a critical shot
        if (p instanceof AbstractArrow arrow) arrow.setCritArrow(false);
    }

    /** Two arrows (or tridents) striking each other. */
    private static void clack(ServerLevel level, Flying a, Flying b, Vec3 at, double closing) {
        boolean trident = a.p instanceof ThrownTrident || b.p instanceof ThrownTrident;
        float volume = Mth.clamp((float) (closing * 0.01), 0.3f, 1.0f);
        float pitch = 1.2f + level.random.nextFloat() * 0.3f;
        level.playSound(null, at.x, at.y, at.z, trident ? SoundEvents.TRIDENT_HIT : SoundEvents.ARROW_HIT,
                SoundSource.NEUTRAL, volume, pitch);
        level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, Mth.clamp((int) (closing / 10.0), 3, 10), 0.05, 0.05, 0.05, 0.2);
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
