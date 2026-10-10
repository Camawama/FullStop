package net.camacraft.fullstop.common.compat.create.kinetic;

import com.google.common.collect.MapMaker;
import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.scale.EntityScale;
import net.camacraft.camafy.physics.Impulses;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.physics.PhysicsOwnership;
import net.camacraft.camafy.weight.Weights;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.compat.create.CreateCompat;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.server.physics.ship.ShipContactPhysics;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Create Catastrophes, the kinetic half: the nip of two meshing gears draws bodies in and crushes them,
 * a single spinning gear pushes off what touches it, and water wheels drag bodies under and hold them
 * there ({@code gearHazards}, {@code waterWheelHazards} in the config's Create section). Registered only
 * with Create installed.
 *
 * <p><b>Who moves whom.</b> A body's motion is changed on the side that simulates it: the server for
 * mobs, the player's own client for a player (the client owns a player's movement; a pull applied by
 * the server would fight it every tick). Damage, the grinding and the jam's stress are the server's,
 * for everyone. Both sides find the parts the same way (block states and Create's synced speeds and
 * overstressed state), so a player feels exactly the pull the server judges by.
 *
 * <p><b>Gears</b> (cogwheels and large cogwheels; never encased ones, gearboxes or shafts). A single
 * gear never pulls and never hurts: a body touching its teeth is pushed off, out from the gear and along
 * the way the teeth move, a nudge from a crawling gear ({@link KineticGrip#push}); teeth hitting it at
 * {@code gearFlingSpeed} or faster (a cogwheel at 256 RPM, a large one from about 128) fling it like a
 * hull's strike instead ({@link KineticGrip#fling}): the throw hurts nothing, what it is thrown into does.
 * Bodies are caught only in the <em>nip</em> of two meshing gears (two cogwheels side by side on one
 * axis, a large and a small one diagonally: Create's own rule, {@link KineticGrip#meshes}), on its
 * in-running side, where both gears' teeth run into the mesh: a body at the mouth (its nearest point
 * within the smaller gear's radius upstream of the mesh, between the rims) is drawn in (harder with
 * speed: the square root of it, less for a sneaking player and heavier bodies) and crushed whenever
 * vanilla's damage cooldown allows ({@code gearDamage} at 64 RPM, growing with the speed to the 0.75
 * power, a quarter more with a large cogwheel in the pair; nothing below {@value #TEETH_BITE_RPM} RPM).
 * The out-running side pushes out, as a single gear does. A body in the nip also <b>jams</b> the gears:
 * it loads their network with {@code gearJamStress} stress units per RPM per kilogram (a player: 37.5,
 * more than a lone water wheel supplies), so a big body or a weak network overstresses, and Create stops
 * everything on it. Stopped, the gears neither draw in nor bite, and the body stays wedged until it
 * walks out (the load goes as it leaves, or dies) or the network is strengthened.
 *
 * <p><b>Water wheels</b> (both sizes). Above the axle the paddles sweep a body along with them, off the
 * top of the wheel and down into the water; in the water on the downstroke or below the axle the body
 * is pinned: every paddle presses it down under the wheel, so it can't surface and drowns by vanilla's
 * own air and drowning, unless it swims out sideways (along the axle the wheel holds weakly), the
 * wheel stops, or it has been held {@code waterWheelHoldSeconds}, after which it's let go for five
 * seconds. A wheel turned fast (a network driven by something else) bites like a gear out of water.
 *
 * <p>Respects Camafy's contracts: nobody else's MOTION claim is overridden (karts, grapples, wall
 * clings), bodies held to a surface by the gravity mod ({@code Gravity.isAnchored}) aren't pulled,
 * spectators and flying players are left alone, creative players are never caught (a single gear's teeth
 * still push or fling one on foot, as a contraption knocks one about), and a body held is claimed (MOTION and G_FORCE,
 * re-claimed each tick) so the other movement mods yield and the tug isn't felt as a crash. Velocity goes through {@code Impulses}, so a kart another
 * mod scripts is pushed in its own terms. The pulls work in world terms under any gravity.
 */
public final class KineticHazards {

    public static final ResourceLocation OWNER = new ResourceLocation(FullStop.MODID, "create_kinetic");
    /** Ticks between searches for parts near a body (also searched again once it has moved half a block). */
    private static final int SCAN_EVERY = 10;
    private static final double RESCAN_MOVE_SQR = 0.5 * 0.5;
    /** Below this a nip draws in but doesn't bite. */
    static final double TEETH_BITE_RPM = 8.0;
    /** A water wheel this fast bites like a gear out of water. */
    static final double PADDLE_BITE_RPM = 24.0;
    /** How long a body a water wheel let go of can't be caught again. */
    private static final int RELEASE_IMMUNITY = 100;
    /** Ticks after a single gear flung a body before that gear flings it again (it pushes meanwhile). */
    static final int FLING_COOLDOWN = 10;

    private static final Map<LivingEntity, State> STATES = new MapMaker().weakKeys().makeMap();

    private KineticHazards() {
    }

    /** What one body knows about the parts around it, on one side. */
    static final class State {
        final List<Hazard> hazards = new ArrayList<>();
        Vec3 scannedAt = null;
        long scannedTick = Long.MIN_VALUE;
        long immuneUntil = Long.MIN_VALUE;
        boolean held;
        int pinnedTicks;
        /** The last tick a water wheel held this body in its water (server). */
        long wheelHoldTick = Long.MIN_VALUE;
        /** The single gear that last flung this body, and when. */
        @Nullable
        Hazard flungBy;
        long flungTick = Long.MIN_VALUE;
    }

    /** The speed (m/s) of each body's last fling by a single gear, for the game tests and the harness. */
    private static final Map<LivingEntity, Double> LAST_FLING = new MapMaker().weakKeys().makeMap();

    /** The speed (m/s, after the launch cap) {@code body} was last flung at by a single gear on this side, 0 if never. */
    public static double lastFlingMps(LivingEntity body) {
        Double v = LAST_FLING.get(body);
        return v == null ? 0.0 : v;
    }

    /** Test hook: when set, the server's time in {@link #onLivingTick} is added to {@link #timedNanos}. */
    static volatile boolean timing;
    static long timedNanos;

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (!timing || event.getEntity().level().isClientSide()) {
            tick(event.getEntity());
            return;
        }
        long start = System.nanoTime();
        tick(event.getEntity());
        timedNanos += System.nanoTime() - start;
    }

    private static void tick(LivingEntity body) {
        Level level = body.level();
        boolean client = level.isClientSide();
        // the side that moves the body: the server for mobs, the player's own client for a player
        boolean simulate = client ? (body instanceof Player p && p.isLocalPlayer()) : !(body instanceof Player);
        if (client && !simulate) return;
        if (!CreateCompat.active()) return;
        FullStopConfig.ServerConfigValues cfg = FullStopConfig.SERVER;
        boolean gears = cfg.gearHazards.get();
        boolean wheels = cfg.waterWheelHazards.get();
        if (!gears && !wheels) return;
        if (exempt(body)) {
            State gone = STATES.remove(body);
            if (gone != null && gone.held) PhysicsOwnership.release(body, OWNER);
            return;
        }
        long now = level.getGameTime();
        // a creative player on foot: a single gear's teeth push or fling it, nothing catches it
        boolean creative = body instanceof Player p && p.isCreative();
        State state = STATES.computeIfAbsent(body, b -> new State());
        if (now < state.immuneUntil) {
            letGo(body, state);
            return;
        }
        AABB box = body.getBoundingBox();
        if (state.scannedAt == null || now - state.scannedTick >= SCAN_EVERY
                || state.scannedAt.distanceToSqr(body.position()) > RESCAN_MOVE_SQR) {
            state.hazards.clear();
            HazardScanner.scan(level, box, gears, wheels, state.hazards);
            state.scannedAt = body.position();
            state.scannedTick = now;
        }
        if (state.hazards.isEmpty()) {
            letGo(body, state);
            return;
        }

        Vec3 v = Gravity.getWorldVelocity(body);
        boolean mayPull = simulate && !Gravity.isAnchored(body)
                && !PhysicsOwnership.claimedByOther(body, PhysicsAspect.MOTION, OWNER);
        double minRpm = cfg.gearMinRpm.get();

        // the gears: what turns them now (or would: a jammed pair still knows its in-running side), and the water wheels
        List<Hazard.Live> cogs = new ArrayList<>();
        KineticGrip.Catch wheel = null;
        for (Hazard hazard : state.hazards) {
            Hazard.Live live = HazardScanner.resolveAny(level, hazard);
            if (live == null) continue;
            if (hazard.kind().wheel) {
                if (!live.turning(minRpm)) continue;
                KineticGrip.Catch k = KineticGrip.of(box, live);
                if (k != null && (wheel == null || k.outside() < wheel.outside())) wheel = k;
            } else if (Math.abs(live.driveRpm()) >= minRpm) {
                cogs.add(live);
            }
        }

        // the in-running mouth of two meshing gears: drawn in, crushed, and jamming them
        KineticGrip.NipCatch nip = gears && !creative ? deepestNip(box, cogs) : null;
        if (nip != null) {
            KineticGrip.Nip pair = nip.nip();
            if (!client) {
                double jam = cfg.gearJamStress.get() * Weights.massKg(body);
                HazardScanner.jam(level, pair.a().hazard(), body, jam);
            }
            if (pair.stalled()) {
                // jammed: the gears stand still with the body wedged in them, until it gets out (or the motor gives)
                letGo(body, state);
                return;
            }
            if (mayPull) {
                Impulses.add(body, KineticGrip.nipPull(nip, v, gearStrength(body, pair.rpm(), cfg)));
                hold(body, state);
            }
            if (!client && pair.rpm() >= TEETH_BITE_RPM) {
                bite((ServerLevel) level, body, pair.large() ? Hazard.Kind.LARGE_COGWHEEL : Hazard.Kind.COGWHEEL, pair.rpm(), cfg);
            }
            return;
        }

        // a single gear's teeth: pushed off (flung by a very fast one), never pulled in, never hurt
        KineticGrip.Catch touched = null;
        if (gears) {
            for (Hazard.Live live : cogs) {
                if (!live.turning(minRpm)) continue;
                KineticGrip.Catch k = KineticGrip.touch(box, live);
                if (k != null && (touched == null || k.outside() < touched.outside())) touched = k;
            }
        }
        if (touched != null) {
            if (mayPull) {
                if (!fling(body, state, touched, v, now, cfg)) {
                    Impulses.add(body, KineticGrip.push(touched, v, up(body), resistance(body) * cfg.gearPullStrength.get()));
                }
                hold(body, state);
            }
            return;
        }

        if (creative || wheel == null || !wheels) {
            letGo(body, state);
            return;
        }
        if (wheel(body, state, wheel, v, mayPull, cfg) && !client) {
            bite((ServerLevel) level, body, wheel.part().hazard().kind(), Math.abs(wheel.part().rpm()), cfg);
        }
    }

    /**
     * A very fast single gear's teeth flinging the body that touches them, on the side that moves it: true when they
     * did, false when they only push it (they hit it slower than {@code gearFlingSpeed}, at its own scale, or this gear
     * flung it within the last {@value #FLING_COOLDOWN} ticks). The throw is Full Stop's strike law for a driven hull
     * ({@link KineticGrip#fling}), capped at {@code maxHitLaunchSpeed}; it hurts nothing by itself, and what the body
     * is thrown into or lands on is judged as any impact.
     */
    private static boolean fling(LivingEntity body, State state, KineticGrip.Catch k, Vec3 v, long now,
                                 FullStopConfig.ServerConfigValues cfg) {
        Hazard gear = k.part().hazard();
        if (gear.equals(state.flungBy) && now - state.flungTick < FLING_COOLDOWN) return false;
        Vec3 own = v.scale(20.0);
        KineticGrip.Fling thrown = KineticGrip.fling(k, own, up(body), body.onGround(), cfg.entityRestitution.get(),
                cfg.hitLift.get() * ShipContactPhysics.liftShare(body));
        if (!(ShipContactPhysics.atOwnScale(body, thrown.closing()) >= cfg.gearFlingSpeed.get())) return false;
        Vec3 change = ShipContactPhysics.capLaunch(body, own, thrown.change());
        // real motion in the world: a body a size mod shrank moves by its motion scale's share of its own velocity
        Impulses.add(body, EntityScale.velocityFor(body, change.scale(0.05)));
        body.hasImpulse = true;
        if (!body.level().isClientSide()) body.hurtMarked = true;
        state.flungBy = gear;
        state.flungTick = now;
        LAST_FLING.put(body, change.length());
        Vec3 at = body.getBoundingBox().getCenter();
        float volume = (float) Math.max(0.3, Math.min(1.0, thrown.closing() / 30.0));
        float pitch = (float) Math.max(0.8, Math.min(1.5, 0.8 + thrown.closing() / 60.0));
        // heard by all from the server (a mob); a player's own client flings it, and plays it for that player
        body.level().playSound(body instanceof Player p ? p : null, at.x, at.y, at.z, SoundEvents.PLAYER_ATTACK_KNOCKBACK,
                SoundSource.BLOCKS, volume, pitch);
        return true;
    }

    /** Of the in-running mouths of the meshing pairs among {@code cogs}, the one the body is deepest in; null for none. */
    @Nullable
    static KineticGrip.NipCatch deepestNip(AABB box, List<Hazard.Live> cogs) {
        KineticGrip.NipCatch best = null;
        for (int i = 0; i < cogs.size(); i++) {
            for (int j = i + 1; j < cogs.size(); j++) {
                Hazard.Live a = cogs.get(i), b = cogs.get(j);
                if (!KineticGrip.meshes(a, b)) continue;
                KineticGrip.Nip pair = KineticGrip.nip(a, b);
                if (pair == null) continue;
                KineticGrip.NipCatch k = KineticGrip.inMouth(box, pair);
                if (k != null && (best == null || k.s() > best.s())) best = k;
            }
        }
        return best;
    }

    /** The meshing pairs among {@code parts} and their nips (for F3+V). */
    static List<KineticGrip.Nip> nips(List<Hazard.Live> parts) {
        List<KineticGrip.Nip> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            for (int j = i + 1; j < parts.size(); j++) {
                if (!KineticGrip.meshes(parts.get(i), parts.get(j))) continue;
                KineticGrip.Nip pair = KineticGrip.nip(parts.get(i), parts.get(j));
                if (pair != null) out.add(pair);
            }
        }
        return out;
    }

    /** The paddles; returns whether they bite (a fast wheel out of water, in the paddles). */
    private static boolean wheel(LivingEntity body, State state, KineticGrip.Catch k, Vec3 v, boolean mayPull,
                                 FullStopConfig.ServerConfigValues cfg) {
        boolean wet = body.isInWater();
        // in the paddles' water: what drowns it now is the wheel (marked on the server too, where a player isn't pulled)
        if (wet) state.wheelHoldTick = body.level().getGameTime();
        if (mayPull) {
            double grip = cfg.waterWheelGrip.get() * resistance(body);
            KineticGrip.WheelResult result = KineticGrip.wheel(k, v, body.getBoundingBox().getCenter(), up(body), wet, grip);
            Impulses.add(body, result.delta());
            hold(body, state);
            if (result.pinned()) {
                state.pinnedTicks++;
                double limit = cfg.waterWheelHoldSeconds.get() * 20.0;
                if (state.pinnedTicks > limit) {
                    // the wheel lets go at last
                    state.immuneUntil = body.level().getGameTime() + RELEASE_IMMUNITY;
                    letGo(body, state);
                }
            } else {
                state.pinnedTicks = 0;
            }
        }
        return !wet && k.inTeeth() && Math.abs(k.part().rpm()) >= PADDLE_BITE_RPM;
    }

    /** One bite (server): the nip of two gears crushing a body, or a fast water wheel's paddles out of the water. */
    private static void bite(ServerLevel level, LivingEntity body, Hazard.Kind kind, double rpm, FullStopConfig.ServerConfigValues cfg) {
        double damage = cfg.gearDamage.get() * Math.pow(rpm / 64.0, 0.75);
        if (kind.large()) damage *= 1.25;
        if (kind.wheel) damage *= 0.5;
        if (damage <= 0.0) return;
        // within the first half of vanilla's damage cooldown a bite is refused anyway (and would post the attack events for nothing)
        if (body.invulnerableTime > 10) return;
        if (DamageImmunityRules.isDamageImmune(body)) return;
        if (!body.hurt(GearDamage.source(body, kind), (float) Math.min(damage, 1000.0))) return;
        Vec3 at = body.getBoundingBox().getCenter();
        float pitch = (float) Math.max(0.6, Math.min(1.6, 0.6 + rpm / 128.0));
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.7F, pitch);
        level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 6, 0.2, 0.2, 0.2, 0.15);
    }

    /** How hard a nip draws this body in: the speed's square root, a player's weight, standing. */
    private static double gearStrength(LivingEntity body, double rpm, FullStopConfig.ServerConfigValues cfg) {
        double speed = Math.sqrt(Math.min(Math.abs(rpm), 1024.0) / 64.0);
        return cfg.gearPullStrength.get() * speed * resistance(body);
    }

    /** A sneaking player braces; heavy bodies are harder to drag (a player's weight is 1). */
    private static double resistance(LivingEntity body) {
        double r = body instanceof Player p && p.isShiftKeyDown() ? 0.4 : 1.0;
        double mass = Weights.massKg(body);
        if (mass > 0 && Double.isFinite(mass)) r *= Math.max(0.25, Math.min(2.0, Math.sqrt(75.0 / mass)));
        return r;
    }

    private static Vec3 up(LivingEntity body) {
        Vec3 down = Gravity.pullDirection(body);
        return down == null || down.lengthSqr() < 1.0E-8 ? new Vec3(0, 1, 0) : down.normalize().scale(-1.0);
    }

    /**
     * Vanilla's drowning hit on a body a water wheel is holding under (this tick or the last) becomes the wheel's own
     * ({@code fullstop:water_wheel_drown}, "was held under by a water wheel"): the same amount, still drowning
     * ({@code #minecraft:is_drowning}), never twice (the vanilla hit is cancelled before anything of it applies).
     */
    @SubscribeEvent
    public static void onLivingAttack(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        LivingEntity body = event.getEntity();
        if (body.level().isClientSide() || !event.getSource().is(net.minecraft.world.damagesource.DamageTypes.DROWN)) return;
        State state = STATES.get(body);
        // compared this way round: gameTime - Long.MIN_VALUE (never held) overflows to a negative "age",
        // which relabelled every drowning of a body this class had ever tracked as a water wheel's
        if (state == null || state.wheelHoldTick < body.level().getGameTime() - 2) return;
        event.setCanceled(true);
        body.hurt(GearDamage.drowned(body), event.getAmount());
    }

    /** Test hook: marks {@code body} as held under by a water wheel this tick (server). */
    static void markHeldUnderForTest(LivingEntity body) {
        STATES.computeIfAbsent(body, b -> new State()).wheelHoldTick = body.level().getGameTime();
    }

    private static void hold(LivingEntity body, State state) {
        // MOTION: the other movement mods yield; G_FORCE: a tug of the teeth is no crash to black out from
        PhysicsOwnership.claim(body, OWNER, PhysicsAspect.bits(PhysicsAspect.MOTION, PhysicsAspect.G_FORCE), 3);
        state.held = true;
    }

    private static void letGo(LivingEntity body, State state) {
        if (state.held) PhysicsOwnership.release(body, OWNER);
        state.held = false;
        state.pinnedTicks = 0;
    }

    private static boolean exempt(LivingEntity body) {
        if (body.isSpectator() || body.isPassenger() || body.noPhysics || body.isDeadOrDying()) return true;
        // decorations, not bodies: a gear would knock a display stand to bits
        if (body instanceof net.minecraft.world.entity.decoration.ArmorStand) return true;
        // a creative player on foot is only pushed or flung by a single gear (see tick), never caught
        if (body instanceof Player p && p.getAbilities().flying) return true;
        return body.getType().is(FullStopTags.PHYSICS_BLACKLIST);
    }
}
