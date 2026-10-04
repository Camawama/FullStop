package net.camacraft.fullstop.common.compat.create.contraption;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ContraptionHandler;
import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.scale.EntityScale;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.minigame.MatchPolicies;
import net.camacraft.camafy.physics.Collisions;
import net.camacraft.camafy.physics.EntityStack;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.create.CreateCompat;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.particle.CollisionParticleSpawner;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.camacraft.fullstop.common.sound.FSSoundPlayer;
import net.camacraft.fullstop.server.physics.BodyPush;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.camacraft.fullstop.server.physics.damage.KineticDamageApplier;
import net.camacraft.fullstop.server.physics.damage.KineticDamageCalculator;
import net.camacraft.fullstop.server.physics.ship.ShipContactPhysics;
import net.camacraft.fullstop.server.physics.ship.ShipSlimeHoney;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * A moving Create contraption striking what it runs into, exactly as a Valkyrien Skies ship's hull
 * does ({@code contraptionImpacts}): a mechanical bearing's arm or windmill sail, a piston's or
 * gantry's ram, a pulley's platform, a minecart contraption, a train.
 *
 * <p><b>When.</b> Once per server level tick, after every entity and contraption has moved and before
 * Create's own collider pushes bodies out of contraptions (it runs at the end of the same level tick,
 * at a lower priority). Create moves each contraption from its previous pose to its current one every
 * tick, and resolves contacts only at the end pose, so a fast arm passes straight through what lies
 * in its sweep (a 256 RPM bearing turns its arm 77 degrees a tick). This test is swept: each body's
 * box is followed through the contraption's whole motion of the tick, in the contraption's own frame
 * (its pose at any fraction of the tick is Create's own interpolation, {@code applyRotation} with
 * partial ticks and the anchor in between), in steps no longer than half the body, against the
 * contraption's actual block collision shapes. The first step that meets a block is the contact; the
 * face it came in through is the normal.
 *
 * <p><b>The hit.</b> The contraption's velocity at the contact point (linear and turning, from that
 * same interpolation) against the body's own: it must be the contraption doing the hitting (its speed
 * into the body at least {@code pushMinSpeed} and at least what the body brings), and faster than
 * {@code gentlePushSpeed}, or it is only a push and Create's own collider shoves the body along as
 * it always has. A strike is {@link ShipContactPhysics}' two-body law with the contraption as an
 * immovable body (it is driven: a bearing doesn't slow down for a cow): the body is knocked away along
 * the normal at {@code (1 + restitution)} times the closing speed (inelastic from below, as on a deck),
 * dragged along by the contraption's slide ({@code hullFriction}), lifted off the ground when knocked
 * sideways ({@code hitLift}), capped at {@code maxHitLaunchSpeed}, and hurt by the kinetic damage of
 * losing the closing speed against the block that hit it (its hardness counts, as a hull block's
 * does; slime bounces and never hurts), times {@code contraptionDamageMultiplier}, crushed
 * ({@code pinnedDamageMultiplier}, no knock-away) when something solid is right behind it. One hit per
 * contraption per body per {@value #HIT_COOLDOWN_TICKS} ticks; within it a contraption that catches the
 * body again pushes it on but hurts it no more.
 *
 * <p><b>Never hit:</b> what rides the contraption (seated, or standing on it and carried: the block
 * under its feet is the contraption's at the start and the end of the tick, or Create registered it as
 * standing on it), what Create itself never collides with (spectators, items' riders, seats, glue,
 * hanging entities, projectiles, entities that aren't pushed), items and other unphysable entities,
 * {@code #fullstop:contraption_immune}, bodies whose impacts another mod owns (an MKW kart in a race),
 * and bodies a minigame's rules protect ({@code MatchPolicies.canHarm}). A body the gravity mod holds to
 * its surface ({@code Gravity.isAnchored}) is hurt but not flung. Creative players are knocked about,
 * never hurt. Contraptions on a Valkyrien Skies ship are left alone (their blocks live in the ship's
 * yard; the ship's own hits are FullStop's ship code).
 *
 * <p>Players are judged here too, from where their client last put them: a player moves on its client,
 * where Create's collider pushes it out of contraptions, and the server's copy is a tick behind. The
 * contraption's pose is the same on both sides (a bearing's angle follows its speed on each), so a
 * strike found here meets the player where it saw the arm come, and the kick reaches its client
 * unclamped ({@link BodyPush}).
 */
public final class ContraptionImpacts {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Entity types no contraption ever strikes (data/fullstop/tags/entity_types/contraption_immune.json). */
    public static final TagKey<EntityType<?>> CONTRAPTION_IMMUNE =
            TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(FullStop.MODID, "contraption_immune"));

    /** One hit per contraption per body per this many ticks (damage, sound, particles). */
    static final int HIT_COOLDOWN_TICKS = 10;
    /**
     * How far past this tick (in ticks) a body's sweep looks: the coming tick, as Create's collider does before it
     * shoves the body out of the contraption's way.
     */
    static final double LOOKAHEAD = 1.0;
    /** The most sub-steps one body's sweep takes. */
    private static final int MAX_STEPS = 64;
    /** The shortest sub-step (blocks of the contraption's fastest travel). */
    private static final double MIN_STEP = 0.2;
    /** How far past the body, along the push, something solid means it is pinned. */
    private static final double PIN_PROBE = 0.25;
    /** A push pointing down into the ground by more than this (cosine against up) is a real pin. */
    private static final double PRESSED_INTO_GROUND = 0.7;
    /** How far below its feet a contraption block makes a body stand on it. */
    private static final double STAND_PROBE = 0.1;
    /** The pivot every contraption turns about, in its local frame (the anchor block's centre). */
    private static final Vec3 CENTER = new Vec3(0.5, 0.5, 0.5);

    /** The last strike each body got from a contraption, for the debug log, the game tests and the harness. */
    private static final Map<Entity, Struck> LAST_STRUCK = Collections.synchronizedMap(new WeakHashMap<>());
    /** The last strike each body got that was a hit (not a push on within a hit's cooldown). */
    private static final Map<Entity, Struck> LAST_HIT = Collections.synchronizedMap(new WeakHashMap<>());
    /** Per body, per contraption id: the tick its last hit's cooldown started. */
    private static final Map<Entity, Map<Integer, Long>> COOLDOWNS = Collections.synchronizedMap(new WeakHashMap<>());
    /** What the last level tick cost, per level (for the performance check). */
    private static final Map<Level, Stats> STATS = Collections.synchronizedMap(new WeakHashMap<>());
    /** Contraptions on ships already logged as skipped. */
    private static final Set<Integer> SHIP_SKIP_LOGGED = Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * One strike by a contraption: when, by which contraption (entity id), how fast it closed on the body
     * (m/s), the fraction of the tick it met it at (over 1: early in the coming tick, a contact Create's collider would
     * otherwise have shoved the body clear of first), the velocity change the body got (m/s, world), whether
     * it was a hit (hurt, sound, cooldown started) or only a push on within a hit's cooldown, the damage
     * dealt and whether the body was pinned.
     */
    public record Struck(long tick, int contraptionId, double approach, double atFraction, Vec3 pushMps, boolean hit,
                         double damage, boolean pinned, Vec3 normal) {}

    /** One level tick's cost: contraptions looked at, fast enough to test, bodies tested, nanoseconds. */
    public record Stats(long tick, int contraptions, int fast, int bodies, long nanos) {}

    private ContraptionImpacts() {
    }

    /** The last strike {@code body} got from a contraption, or null. */
    @Nullable
    public static Struck lastStruck(Entity body) {
        return LAST_STRUCK.get(body);
    }

    /** The last strike {@code body} got from a contraption that was a hit (hurt, sound, cooldown), or null. */
    @Nullable
    public static Struck lastHit(Entity body) {
        return LAST_HIT.get(body);
    }

    /** What the latest level tick's contraption checks cost in {@code level}, or null before any. */
    @Nullable
    public static Stats stats(Level level) {
        return STATS.get(level);
    }

    /** Whether contraption hits are on: Create installed, {@code enableCreateCompat} and {@code contraptionImpacts}. */
    public static boolean enabled() {
        return CreateCompat.active() && SERVER.contraptionImpacts.get();
    }

    /**
     * Whether Create's own train run-over damage stands down: trains are contraption hits here
     * ({@code contraptionsReplaceTrainDamage}). Asked on both sides by {@code TrainDamageMixin}.
     */
    public static boolean replacesTrainDamage() {
        return enabled() && SERVER.contraptionsReplaceTrainDamage.get();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || !enabled()) return;
        long start = System.nanoTime();
        Map<Integer, WeakReference<AbstractContraptionEntity>> loaded = ContraptionHandler.loadedContraptions.get(level);
        if (loaded == null || loaded.isEmpty()) return;
        int[] counts = new int[3];
        int[] budget = {SERVER.maxContraptionHitChecksPerTick.get()};
        // a copy: a hit can discard an entity, and Create's own map may change under a strike's side effects
        List<AbstractContraptionEntity> contraptions = new ArrayList<>(loaded.size());
        for (WeakReference<AbstractContraptionEntity> ref : loaded.values()) {
            AbstractContraptionEntity c = ref.get();
            if (c != null && c.isAlive()) contraptions.add(c);
        }
        for (AbstractContraptionEntity c : contraptions) {
            counts[0]++;
            try {
                process(level, c, counts, budget);
            } catch (RuntimeException e) {
                LOGGER.warn("[FullStop] contraption {} hit check failed: {}", c.getId(), e.toString());
            }
            if (budget[0] <= 0) break;
        }
        STATS.put(level, new Stats(level.getGameTime(), counts[0], counts[1], counts[2], System.nanoTime() - start));
    }

    private static void process(ServerLevel level, AbstractContraptionEntity c, int[] counts, int[] budget) {
        Contraption contraption = c.getContraption();
        if (contraption == null || contraption.bounds == null) return;
        // A contraption on a Valkyrien Skies ship lives in the ship's yard: its blocks aren't where the world sees them.
        if (Ships.available() && Ships.managing(c) != null) {
            if (SHIP_SKIP_LOGGED.add(c.getId()) && SERVER.debugContraptionLog.get()) {
                LOGGER.info("[FullStop] contraption {} is on a ship: its hits are left to the ship", c.getId());
            }
            return;
        }

        // The farthest any of its blocks travels this tick: the corners of its bounds, start to end pose (a turn's
        // farthest point is a corner of the bounds).
        AABB local = contraption.bounds;
        double reach = 0.0;
        AABB swept = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 p = corner(local, corner);
            Vec3 from = toGlobal(c, p, 0.0);
            Vec3 to = toGlobal(c, p, 1.0);
            reach = Math.max(reach, from.distanceTo(to));
            // this tick and the coming one (the sweep looks ahead as Create's collider does)
            int quarters = (int) Math.round(4 * (1.0 + LOOKAHEAD));
            for (int q = 0; q <= quarters; q++) {
                Vec3 at = q == 0 ? from : q == 4 ? to : toGlobal(c, p, q * 0.25);
                swept = swept == null ? new AABB(at, at) : swept.minmax(new AABB(at, at));
            }
        }
        if (swept == null || reach * 20.0 < SERVER.pushMinSpeed.get()) return;
        counts[1]++;
        // the arc between the sampled poses bulges out by at most a quarter of a quarter turn's chord
        swept = swept.inflate(1.0 + reach * 0.25);

        Set<Entity> bodies = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Entity entity : level.getEntities((Entity) null, swept, e -> true)) {
            Entity root = entity.getRootVehicle();
            if (!bodies.add(root)) continue;
            if (root == c || root instanceof AbstractContraptionEntity) continue;
            if (!ShipContactPhysics.isBody(root)) continue;
            String why = excluded(c, root);
            if (why != null) {
                explain(level, c, root, "skipped: " + why);
                continue;
            }
            if (budget[0]-- <= 0) return;
            counts[2]++;
            hit(level, c, contraption, root, reach);
        }
    }

    /** Why a body is never struck by this contraption, or null when it may be. */
    @Nullable
    private static String excluded(AbstractContraptionEntity c, Entity root) {
        if (DamageImmunityRules.unphysableIgnoringAi(root)) return "unphysable";
        if (root.isSpectator()) return "spectator";
        if (root.getType().is(CONTRAPTION_IMMUNE)) return "contraption_immune";
        // riding it (its seats), or something Create never collides with
        if (root == c.getRootVehicle()) return "riding it";
        if (!c.canCollideWith(root)) return "Create doesn't collide with it";
        // standing on it, as Create's own collider saw it (non-players: players stand on it on their client). Create
        // registers a body on any surface contact of its push, a ram's side included (a gantry's plank or a cart's
        // load shoving a body ahead), so its feet must really be on a block of the contraption too
        if (c.collidingEntities.containsKey(root) && c.getContraption() != null
                && standsOn(c, c.getContraption(), root.getBoundingBox(), 1.0)) {
            return "standing on it";
        }
        for (Entity member : EntityStack.selfAndPassengers(root)) {
            if (PhysicsAspect.IMPACT.in(PhysicsClaims.live(member))) return "IMPACT claimed";
        }
        return null;
    }

    private static void hit(ServerLevel level, AbstractContraptionEntity c, Contraption contraption, Entity root, double reach) {
        AABB boxNow = root.getBoundingBox();
        Vec3 moved = root.position().subtract(root.xo, root.yo, root.zo);
        // standing on it and carried with it through the whole tick: the contraption's block is under the feet at the
        // start and the end of the tick
        if (standsOn(c, contraption, boxNow.move(moved.scale(-1.0)), 0.0) && standsOn(c, contraption, boxNow, 1.0)) {
            explain(level, c, root, "carried: stands on it all tick");
            return;
        }

        double size = Math.max(0.05, Math.min(boxNow.getXsize(), boxNow.getYsize()) * 0.5);
        // The tick just gone and the coming one (LOOKAHEAD): Create's own collider, which runs after this, sweeps each
        // body's coming motion against the contraption's and puts it at the contact point with the contraption's speed,
        // so a steadily moving gantry, pulley or cart never overlaps a body; a contact it would shove first is a strike
        // now. The coming tick is Create's own interpolation carried on, the body going on as it moved.
        double span = 1.0 + LOOKAHEAD;
        int steps = Mth.clamp((int) Math.ceil(span * reach / Math.max(MIN_STEP, size)), 1, MAX_STEPS);
        AABB before = null;
        AABB meetBox = null;
        AABB met = null;
        BlockPos metPos = null;
        double fraction = 0.0;
        for (int i = 0; i <= steps; i++) {
            double f = span * i / steps;
            AABB world = boxNow.move(moved.scale(f - 1.0));
            AABB localBox = toLocal(c, world, f);
            Contact contact = firstContact(contraption, localBox);
            if (contact != null) {
                meetBox = localBox;
                met = contact.box();
                metPos = contact.pos();
                fraction = f;
                break;
            }
            before = localBox;
        }
        if (met == null) {
            explain(level, c, root, String.format(Locale.ROOT, "no contact in the sweep (reach %.2f, %d steps)", reach, steps));
            return;
        }

        // The face it came in through (in the contraption's frame), and when within the step.
        Face face = before != null ? entryFace(before, meetBox, met) : null;
        double at = fraction;
        if (face != null) at = Math.max(0.0, fraction - (1.0 - face.entry()) * span / steps);
        else face = leastPenetration(meetBox, met);
        Vec3 localNormal = face.normal();
        Vec3 localPoint = contactPoint(meetBox, met, localNormal);

        Vec3 point = toGlobal(c, localPoint, at);
        Vec3 normal = toGlobal(c, localPoint.add(localNormal), at).subtract(point).normalize();
        Vec3 hullV = velocityAt(c, localPoint, at);

        FullStopCapability cap = FullStopCapability.grabCapability(root);
        GravityFrame frame = cap != null ? cap.getFrame() : FullStopCapability.currentFrame(root);
        Vec3 own = ownVelocity(root, frame);

        double hullIn = hullV.dot(normal);
        double approach = hullV.subtract(own).dot(normal);
        double pushMin = SERVER.pushMinSpeed.get() * EntityScale.motion(root);
        // the contraption must be doing the hitting: running into a contraption is no strike
        if (hullIn < Math.max(pushMin, -own.dot(normal)) || approach <= 0.0) {
            explain(level, c, root, "met, but not hitting it: contraption " + fmt(hullIn) + " m/s into it, body " + fmt(own) + " m/s, closing "
                    + fmt(approach) + " m/s, normal " + fmt(normal));
            return;
        }
        // a slow contraption only pushes, and Create's own collider does that
        if (ShipContactPhysics.gentle(root, approach)) {
            explain(level, c, root, "a gentle push: closing " + fmt(approach) + " m/s");
            return;
        }

        Entity driver = driver(level, c);
        if (!MatchPolicies.canHarm(driver != null ? driver : c, root)) return;

        long now = level.getGameTime();
        boolean cooling = onCooldown(root, c.getId(), now);
        // A player moves on its own client, a tick or two ahead of its copy here: right after a hit this copy still
        // stands where the arm struck it and would be "caught again" (and kicked again) while the player is already
        // flying. Within a hit's cooldown a player is left to its client; a mob, simulated here, is really caught again.
        if (cooling && root instanceof ServerPlayer player && BodyPush.clientSimulated(player)) return;
        boolean weightless = Gravity.isWeightless(root);
        BlockState block = blockAt(contraption, metPos);
        ShipContactPhysics.Exchange exchange = ShipContactPhysics.resolve(approach, ShipContactPhysics.bodyKg(root),
                Double.POSITIVE_INFINITY, ShipContactPhysics.restitution(normal, frame, weightless, block));
        double kick = exchange.kick();

        // friction: the contraption sliding past drags the body along (Coulomb), never past moving with it there
        Vec3 relative = hullV.subtract(own);
        Vec3 slip = relative.subtract(normal.scale(relative.dot(normal)));
        double slipSpeed = slip.length();
        double frictionDv = slipSpeed > 1.0e-6 ? Math.min(SERVER.hullFriction.get() * kick, slipSpeed) : 0.0;
        Vec3 change = normal.scale(kick).add(frictionDv > 0.0 ? slip.scale(frictionDv / slipSpeed) : Vec3.ZERO);
        double lift = 0.0;
        if (root.onGround() && Math.abs(frame.vertical(normal)) < ShipContactPhysics.FROM_BELOW) {
            Vec3 up = frame.up();
            double across = change.subtract(up.scale(frame.vertical(change))).length();
            lift = SERVER.hitLift.get() * ShipContactPhysics.liftShare(root) * across;
            change = change.add(up.scale(lift));
        }
        change = ShipContactPhysics.capLaunch(root, own, change);

        boolean pinned = isPinned(level, root, boxNow, normal, frame);
        if (pinned && cooling) return; // crushed, and already hurt for this hit
        // pinned against a Valkyrien Skies ship rather than the terrain: its own death message
        boolean pinnedByShip = pinned && Ships.available() && againstShip(level, boxNow.move(pinProbe(root, normal, frame)));

        double damage = cooling ? 0.0 : hurt(root, cap, block, normal, frame, exchange.speedLost(), approach, pinned, driver,
                ContraptionKinds.of(level, c), pinnedByShip);

        Vec3 pushed = Vec3.ZERO;
        // a hit that kills still throws the body; one the gravity mod holds to its surface isn't flung
        if (!pinned && !root.isRemoved() && !Gravity.isAnchored(root)) {
            if (BodyPush.push(root, change.scale(0.05), frame, own)) pushed = change;
        }
        if (!cooling) {
            effects(level, root, point, block, approach);
            startCooldown(root, c.getId(), now);
        }
        Struck struck = new Struck(now, c.getId(), approach, at, pushed, !cooling, damage, pinned, normal);
        LAST_STRUCK.put(root, struck);
        if (!cooling) LAST_HIT.put(root, struck);

        if (SERVER.debugContraptionLog.get()) {
            LOGGER.info("[FullStop] contraption {} ({}) {} {}: closing {} m/s (contraption {} m/s there, body {} m/s) at {} of the "
                            + "tick, block {}, normal ({}), lost {} m/s, kick {} m/s, friction {} m/s, lift {} m/s, pinned={}, "
                            + "damage {}, pushed ({}) m/s, cooldown {}", c.getId(), net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(c.getType()),
                    cooling ? "pushes on" : "hit", root.getName().getString(), fmt(approach), fmt(hullV.length()), fmt(own.length()),
                    fmt(at), net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block.getBlock()), fmt(normal),
                    fmt(exchange.speedLost()), fmt(kick), fmt(frictionDv), fmt(lift), pinned, fmt(damage), fmt(pushed),
                    cooling ? "running" : "started");
        }
    }

    /**
     * Deals a strike's damage (the kinetic damage of losing {@code speedLost} against the block that hit, crushed
     * when pinned). Returns the damage dealt (0 when none).
     */
    private static double hurt(Entity root, @Nullable FullStopCapability cap, BlockState block, Vec3 normal, GravityFrame frame,
                               double speedLost, double approach, boolean pinned, @Nullable Entity driver, @Nullable String kind,
                               boolean pinnedByShip) {
        if (ShipSlimeHoney.bouncy(block)) return 0.0; // slime bounces, never hurts
        double vertical = frame.vertical(normal);
        boolean above = vertical < -0.7;
        boolean below = vertical > 0.7;
        double damage = KineticDamageCalculator.damageForSpeedLost(root, ShipContactPhysics.atOwnScale(root, speedLost),
                above || below, List.of(block)) * SERVER.contraptionDamageMultiplier.get()
                * ShipContactPhysics.strikeDamageShare(block); // a wool arm shoves, barely hurts
        if (pinned) damage *= SERVER.pinnedDamageMultiplier.get();
        if (damage < 1) return 0.0;
        DamageSource source = FullStopDamageSources.contraption(root, driver,
                FullStopDamageSources.mutualVelocityDisplay(approach), kind, pinned, pinnedByShip, above, below);
        boolean marked = root.hurtMarked;
        boolean hurt = KineticDamageApplier.applyShipHit(root, cap, source, damage, above, below);
        if (root instanceof ServerPlayer player && BodyPush.clientSimulated(player)) {
            // the player's own client has the kick (sent as a change): vanilla's hurt must not send the server's
            // meaningless velocity over it
            player.hurtMarked = marked;
        }
        return hurt ? damage : 0.0;
    }

    private static void effects(ServerLevel level, Entity root, Vec3 point, BlockState block, double approach) {
        CollisionParticleSpawner.spawnParticle(level, point, Collision.CollisionType.SOLID, block, EntityScale.heightFactor(root));
        float volume = Mth.clamp((float) (approach * 0.05), 0.2f, 1.0f);
        float pitch = Mth.clamp(0.9f + (float) (approach / 100.0) * 0.8f, 0.9f, 1.7f);
        FSSoundPlayer.playSoundServer(root, block.getSoundType().getFallSound(), SoundSource.BLOCKS, volume, pitch);
    }

    /** Who answers for a contraption's hits: the player driving it (a train's conductor), or nobody. */
    @Nullable
    private static Entity driver(ServerLevel level, AbstractContraptionEntity c) {
        Optional<UUID> id = c.getControllingPlayer();
        return id.isPresent() ? level.getPlayerByUUID(id.get()) : null;
    }

    // ---- the swept test, in the contraption's frame -------------------------------------------------------------

    /** A contraption block's collision box (local) the body's box met, and its block. */
    private record Contact(AABB box, BlockPos pos) {}

    /** The face a box came in through: its outward normal (local, from the block toward the body), and when (0..1 of the step). */
    private record Face(Vec3 normal, double entry) {}

    /** The first of the contraption's block boxes {@code box} (local) overlaps, or null. */
    @Nullable
    private static Contact firstContact(Contraption contraption, AABB box) {
        Optional<List<AABB>> simplified = contraption.getSimplifiedEntityColliders();
        if (simplified.isPresent()) {
            for (AABB collider : simplified.get()) {
                if (collider.intersects(box)) {
                    return new Contact(collider, BlockPos.containing(Mth.clamp(box.getCenter().x, collider.minX, collider.maxX - 1.0e-6),
                            Mth.clamp(box.getCenter().y, collider.minY, collider.maxY - 1.0e-6),
                            Mth.clamp(box.getCenter().z, collider.minZ, collider.maxZ - 1.0e-6)));
                }
            }
            return null;
        }
        Map<BlockPos, StructureBlockInfo> blocks = contraption.getBlocks();
        // one cell further down: fences and walls stand taller than their cell
        int minX = Mth.floor(box.minX), minY = Mth.floor(box.minY) - 1, minZ = Mth.floor(box.minZ);
        int maxX = Mth.floor(box.maxX), maxY = Mth.floor(box.maxY), maxZ = Mth.floor(box.maxZ);
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cell.set(x, y, z);
                    StructureBlockInfo info = blocks.get(cell);
                    if (info == null || contraption.isHiddenInPortal(cell)) continue;
                    VoxelShape shape = info.state().getCollisionShape(contraption.getContraptionWorld(), cell, CollisionContext.empty());
                    if (shape.isEmpty()) continue;
                    for (AABB part : shape.toAabbs()) {
                        AABB placed = part.move(x, y, z);
                        if (placed.intersects(box)) return new Contact(placed, cell.immutable());
                    }
                }
            }
        }
        return null;
    }

    /** Whether a contraption block is right under the body's feet (local frame at {@code f}). */
    private static boolean standsOn(AbstractContraptionEntity c, Contraption contraption, AABB box, double f) {
        AABB feet = new AABB(box.minX + 0.05, box.minY - STAND_PROBE, box.minZ + 0.05, box.maxX - 0.05, box.minY + 0.05, box.maxZ - 0.05);
        // the feet in the contraption's frame: only when its up is the world's (a deck, a train), never on a wall
        Vec3 upLocal = toLocal(c, box.getCenter().add(0, 1, 0), f).subtract(toLocal(c, box.getCenter(), f));
        if (upLocal.y < 0.7) return false;
        return firstContact(contraption, toLocal(c, feet, f)) != null;
    }

    /**
     * Swept-box entry: which face of {@code block} the box crossed between {@code before} (clear of it) and
     * {@code after} (in it), and when within the step: the axis that was separated and came into overlap last.
     */
    @Nullable
    private static Face entryFace(AABB before, AABB after, AABB block) {
        Face best = null;
        double[][] axes = {
                {before.minX, before.maxX, after.minX, after.maxX, block.minX, block.maxX},
                {before.minY, before.maxY, after.minY, after.maxY, block.minY, block.maxY},
                {before.minZ, before.maxZ, after.minZ, after.maxZ, block.minZ, block.maxZ}};
        for (int axis = 0; axis < 3; axis++) {
            double[] a = axes[axis];
            double entry;
            double sign;
            if (a[0] >= a[5]) { // the body was on the block's positive side
                double gapBefore = a[0] - a[5];
                double gapAfter = a[2] - a[5];
                if (gapAfter >= 0.0) continue;
                entry = gapBefore / (gapBefore - gapAfter);
                sign = 1.0;
            } else if (a[1] <= a[4]) { // on its negative side
                double gapBefore = a[4] - a[1];
                double gapAfter = a[4] - a[3];
                if (gapAfter >= 0.0) continue;
                entry = gapBefore / (gapBefore - gapAfter);
                sign = -1.0;
            } else {
                continue; // overlapping along this axis all along
            }
            if (best == null || entry > best.entry()) {
                Vec3 n = axis == 0 ? new Vec3(sign, 0, 0) : axis == 1 ? new Vec3(0, sign, 0) : new Vec3(0, 0, sign);
                best = new Face(n, Mth.clamp(entry, 0.0, 1.0));
            }
        }
        return best;
    }

    /** The face of {@code block} the box is least deep behind (the way out of it): for a body already in it at the start. */
    private static Face leastPenetration(AABB box, AABB block) {
        double[] depth = {block.maxX - box.minX, box.maxX - block.minX, block.maxY - box.minY, box.maxY - block.minY,
                block.maxZ - box.minZ, box.maxZ - block.minZ};
        Vec3[] normals = {new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, -1, 0), new Vec3(0, 0, 1),
                new Vec3(0, 0, -1)};
        int best = 0;
        for (int i = 1; i < 6; i++) if (depth[i] < depth[best]) best = i;
        return new Face(normals[best], 0.0);
    }

    /** Where on the block's face the box meets it (local): the middle of their overlap, on the face. */
    private static Vec3 contactPoint(AABB box, AABB block, Vec3 normal) {
        double x = (Math.max(box.minX, block.minX) + Math.min(box.maxX, block.maxX)) * 0.5;
        double y = (Math.max(box.minY, block.minY) + Math.min(box.maxY, block.maxY)) * 0.5;
        double z = (Math.max(box.minZ, block.minZ) + Math.min(box.maxZ, block.maxZ)) * 0.5;
        if (normal.x > 0.5) x = block.maxX;
        else if (normal.x < -0.5) x = block.minX;
        if (normal.y > 0.5) y = block.maxY;
        else if (normal.y < -0.5) y = block.minY;
        if (normal.z > 0.5) z = block.maxZ;
        else if (normal.z < -0.5) z = block.minZ;
        return new Vec3(x, y, z);
    }

    private static BlockState blockAt(Contraption contraption, @Nullable BlockPos pos) {
        StructureBlockInfo info = pos != null ? contraption.getBlocks().get(pos) : null;
        BlockState state = info != null ? info.state() : null;
        return state == null || state.isAir() ? Blocks.STONE.defaultBlockState() : state;
    }

    // ---- the contraption's pose at any fraction of the tick (Create's own interpolation) ----------------------

    private static Vec3 anchor(AbstractContraptionEntity c, double f) {
        Vec3 from = c.getPrevAnchorVec();
        Vec3 to = c.getAnchorVec();
        return from.add(to.subtract(from).scale(f));
    }

    /** A point of the contraption (its local frame) in the world, at fraction {@code f} of this tick's motion. */
    static Vec3 toGlobal(AbstractContraptionEntity c, Vec3 local, double f) {
        return c.applyRotation(local.subtract(CENTER), (float) f).add(CENTER).add(anchor(c, f));
    }

    /** A world point in the contraption's local frame at fraction {@code f} of this tick's motion. */
    static Vec3 toLocal(AbstractContraptionEntity c, Vec3 world, double f) {
        return c.reverseRotation(world.subtract(anchor(c, f)).subtract(CENTER), (float) f).add(CENTER);
    }

    /** A world box's corners in the contraption's frame at {@code f}, as the box enclosing them. */
    private static AABB toLocal(AbstractContraptionEntity c, AABB world, double f) {
        AABB out = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 p = toLocal(c, corner(world, corner), f);
            out = out == null ? new AABB(p, p) : out.minmax(new AABB(p, p));
        }
        return out;
    }

    /** The contraption's velocity (m/s, world) at its local point {@code local}, at fraction {@code f} of the tick. */
    static Vec3 velocityAt(AbstractContraptionEntity c, Vec3 local, double f) {
        double eps = 0.02;
        double a = Math.max(0.0, f - eps);
        double b = Math.min(1.0 + LOOKAHEAD, f + eps);
        if (b - a < 1.0e-6) return Vec3.ZERO;
        return toGlobal(c, local, b).subtract(toGlobal(c, local, a)).scale(20.0 / (b - a));
    }

    private static Vec3 corner(AABB box, int corner) {
        return new Vec3((corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY,
                (corner & 4) == 0 ? box.minZ : box.maxZ);
    }

    // ---- the body ---------------------------------------------------------------------------------------------

    /**
     * The body's own velocity (m/s, world). A player's is measured (its deltaMovement means nothing on the
     * server); anything else moves with its deltaMovement, less the gravity a grounded body picked up after its
     * move, which the ground takes back.
     */
    private static Vec3 ownVelocity(Entity root, GravityFrame frame) {
        if (root instanceof Player) return VelocityMath.entityVelocity(root);
        Vec3 own = EntityScale.realVelocity(root, Gravity.getWorldVelocity(root, frame)).scale(20.0);
        if (root.onGround()) {
            double down = frame.vertical(own);
            if (down < 0.0) own = own.subtract(frame.up().scale(down));
        }
        return own;
    }

    /**
     * Whether something solid right behind the body (along the push) keeps it from going anywhere. A body on the
     * ground pushed along or slightly into it looks along the ground (the ground only stops the part going into it);
     * a push mostly into the ground (a ram coming down on it) is a real pin.
     */
    private static boolean isPinned(ServerLevel level, Entity root, AABB box, Vec3 normal, GravityFrame frame) {
        return Collisions.blockedByBlocks(level, root, box.move(pinProbe(root, normal, frame)));
    }

    /** Where {@link #isPinned} looks for what is behind the body: along the push, or along the ground when pushed along it. */
    private static Vec3 pinProbe(Entity root, Vec3 normal, GravityFrame frame) {
        Vec3 up = frame.up();
        double along = normal.dot(up);
        Vec3 probe = normal.scale(PIN_PROBE);
        if (along < 0.0 && along > -PRESSED_INTO_GROUND && root.onGround()) {
            Vec3 across = normal.subtract(up.scale(along));
            double length = across.length();
            if (length > 1.0e-9) probe = across.scale(PIN_PROBE / length);
        }
        return probe;
    }

    /** Whether a Valkyrien Skies ship's block is in {@code box} (what a body was pinned against). */
    private static boolean againstShip(ServerLevel level, AABB box) {
        for (net.camacraft.camafy.compat.vs.ShipRef ship : Ships.intersecting(level, box)) {
            if (ship.touchesBlock(box)) return true;
        }
        return false;
    }

    private static boolean onCooldown(Entity root, int contraptionId, long now) {
        Map<Integer, Long> map = COOLDOWNS.get(root);
        if (map == null) return false;
        Long started = map.get(contraptionId);
        return started != null && now - started < HIT_COOLDOWN_TICKS && now >= started;
    }

    private static void startCooldown(Entity root, int contraptionId, long now) {
        Map<Integer, Long> map = COOLDOWNS.computeIfAbsent(root, k -> new HashMap<>());
        map.values().removeIf(t -> now - t >= HIT_COOLDOWN_TICKS || now < t);
        map.put(contraptionId, now);
    }

    /** Per contraption, body and kind of reason: the tick it was last logged (debugContraptionLog), so each is logged once a second. */
    private static final Map<Long, Long> EXPLAINED = Collections.synchronizedMap(new HashMap<>());

    /** With {@code debugContraptionLog}: why a body near a moving contraption wasn't struck (once a second per pair). */
    private static void explain(ServerLevel level, AbstractContraptionEntity c, Entity root, String why) {
        if (!SERVER.debugContraptionLog.get()) return;
        // per pair and kind of reason (its first word), so an approach's "no contact" doesn't hide what happens at the contact
        long key = ((((long) c.getId() << 32) | (root.getId() & 0xffffffffL)) * 31L) ^ why.split(" ", 2)[0].hashCode();
        long now = level.getGameTime();
        Long last = EXPLAINED.get(key);
        if (last != null && now - last < 20 && now >= last) return;
        if (EXPLAINED.size() > 4096) EXPLAINED.clear();
        EXPLAINED.put(key, now);
        LOGGER.info("[FullStop] contraption {} ({}) and {}: {}", c.getId(),
                net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(c.getType()), root.getName().getString(), why);
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
