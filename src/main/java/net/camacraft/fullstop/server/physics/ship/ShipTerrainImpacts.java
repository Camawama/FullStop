package net.camacraft.fullstop.server.physics.ship;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.camacraft.camafy.compat.vs.ShipCollision;
import net.camacraft.camafy.compat.vs.ShipCollisions;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.camacraft.fullstop.server.physics.interaction.BlockAlterRules;
import net.camacraft.fullstop.server.physics.interaction.KineticBlockInteractions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Valkyrien Skies ships crashing into terrain (and into each other): what breaks, and how it
 * sounds. The collisions come from VS's physics engine through Camafy's {@link ShipCollisions};
 * the leaves and plants VS lets ships pass straight through are {@link ShipVegetationSweep}'s.
 * On VS's PhysX and Jolt backends they are VS's own collision events; on its default,
 * KRUNCH_CLASSIC, which sends none, Camafy's collision fallback detects the crashes itself (a ship
 * that suddenly slows has its hull probed for what it hit). The ship game tests run on all three.
 * PhysX measures a contact's velocity after its solver (a ram reports the rebound), Jolt and the
 * fallback before it; the speed rule below works with either.
 *
 * <h3>Which block gives</h3>
 * At each contact the terrain block and the ship's own block there are weighed by toughness,
 * {@code hardness * (VS block mass / 1000 kg) ^ massInfluence} ({@link ShipMaterials}), and only
 * the WEAKER of the two can break: grass under a stone hull; the planks of a wooden hull rammed
 * into stone, which holds ({@code hullDamage}); nothing at all when obsidian meets obsidian. Soft
 * vegetation ({@code fullstop:ship_sweepable}) always gives. Unbreakable blocks,
 * {@code fullstop:ship_unbreakable}, slime and honey, and (by default) blocks with block
 * entities never break - on the terrain or on the ship. A crash with honey
 * ({@code fullstop:gluey}) at any contact has honeySoftening of its breaking energy soaked up; what
 * slime and honey do to the ship itself is {@link ShipSlimeHoney}'s.
 *
 * <h3>How much gives</h3>
 * Energy budget per ship per tick, in FullStop units (about 46 kJ each):
 * {@code 1/2 * mass * v^2 * terrainEnergyFraction}, with {@code v} the ship's speed into the
 * terrain. For a contact that just began that is the larger of VS's approach speed and what the
 * hull was doing there the tick before (VS's solver has usually stopped the ship by the time the
 * game sees the crash); for a continuing one, VS's approach alone, so a hull resting, sliding or
 * rolling on the ground never grinds itself away. Blocks are paid for fastest contact first, at
 * {@code T_weak * breakCostMultiplier * r / (r - 1)} with {@code r = T_strong / T_weak}: a far
 * tougher striker pays about {@code T_weak * breakCostMultiplier}, near-equal materials a
 * fortune, and equal ones can't break at all. Cracks accumulated on {@code fullstop:crackable}
 * blocks lower what is left; a hit short of breaking cracks the block instead. Nothing breaks
 * slower than {@code breakMinSpeed}; at most {@code terrainMaxBlocksPerTick} blocks break per
 * level and 12 per ship each tick.
 *
 * <h3>Slowing</h3>
 * VS's solver stopped the ship at the terrain. If every terrain block it hit this tick broke, its
 * speed into the terrain is given back the next tick (after VS has taken the blocks out of its
 * world), less what breaking cost: {@code v' = sqrt(v^2 - 2 * spent * terrainBreakDrag / mass)}.
 * It ploughs on until its energy runs out. If anything held - or the hull gave way instead - it
 * stays stopped.
 *
 * <h3>Sound</h3>
 * A contact that just began at {@code impactSoundMinSpeed} or faster sounds like both blocks that
 * met, louder and deeper the harder the hit ({@link ShipImpactSounds}) - whether or not anything
 * breaks, and with breaking switched off.
 *
 * <p>Ship against ship: both hulls sound, and blocks break by the same toughness rule
 * ({@code shipVsShipBreaking}, FULL by default: the weaker block at each contact, of either ship),
 * out of the pair's collision energy with their reduced mass, and two ships that broke through
 * each other plough on, both slowed (see {@code ships}). Who may break: a piloted ship is its pilot acting; an unpiloted one
 * follows mobGriefing ({@code unpilotedFollowsMobGriefing}). Every block also asks minigame hosts
 * ({@code MatchPolicies.mayAlter}) and posts a break event (with the pilot, or the
 * {@code [Full Stop]} fake player) that protection mods can cancel. Broken blocks, the hull's
 * included, drop like mined ones; VS moves a hull block's drops out into the world. Chunks are
 * never loaded.
 */
public final class ShipTerrainImpacts {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** FullStop's subscription with Camafy's collision listener. */
    public static final ResourceLocation SUBSCRIPTION = new ResourceLocation(FullStop.MODID, "ship_collisions");

    private static final int MAX_BLOCKS_PER_SHIP = 12;
    /**
     * How many ticks back a crash between two ships may have happened when it reaches the game
     * (VS's contact comes after its solver has already bounced them apart, sometimes several
     * frames on): the closing speed is taken over the frames of these ticks. Short enough that a
     * ship braking on its own before a gentle touch isn't billed its old speed.
     */
    private static final int PAIR_LATE_TICKS = 6;
    /** How far from a contact point, along the normal, to look for the blocks that touch. */
    private static final double[] PROBE_DEPTHS = {0.3, 0.6, 0.0};

    /** Momentum given back to ships that broke through, due the tick after: per level, per ship id. */
    private static final Map<ServerLevel, Long2ObjectMap<Restore>> PENDING = new WeakHashMap<>();

    private record Restore(Vec3 normal, Vec3 point, double targetSpeed, long dueTick) {}

    /** Closing speed given back to pairs of ships that broke through each other, due the tick after. */
    private static final Map<ServerLevel, List<PairRestore>> PENDING_PAIRS = new WeakHashMap<>();

    /** {@code normal} points from ship {@code b} toward ship {@code a}. */
    private record PairRestore(long a, long b, Vec3 normal, Vec3 point, double targetClosing, long dueTick) {}

    /** One contact that qualified this tick, resolved to the two blocks that touch. */
    record Strike(Vec3 point, Vec3 normal, double speed,
                          @Nullable BlockPos terrainPos, @Nullable BlockState terrain,
                          @Nullable BlockPos hullPos, @Nullable BlockState hull) {}

    private ShipTerrainImpacts() {
    }

    /**
     * Keeps FullStop subscribed to Camafy's ship collisions exactly while something uses them -
     * impact sounds, terrain or hull breaking, ship-vs-ship breaking, the plants VS does collide
     * with - at the slowest speed any of them needs. Called whenever the server config (re)loads.
     */
    public static void syncSubscription() {
        double min = FullStopConfig.SERVER_SPEC.isLoaded() && SERVER.enableValkyrienSkiesCompat.get()
                && SERVER.enableShipCollisions.get() ? slowestUseful() : Double.POSITIVE_INFINITY;
        if (min < Double.POSITIVE_INFINITY) {
            ShipCollisions.subscribe(SUBSCRIPTION, min);
        } else {
            ShipCollisions.unsubscribe(SUBSCRIPTION);
        }
    }

    /** The slowest contact (m/s) anything here acts on; +infinity when nothing is switched on. */
    private static double slowestUseful() {
        double min = Double.POSITIVE_INFINITY;
        if (SERVER.impactSounds.get()) min = Math.min(min, SERVER.impactSoundMinSpeed.get());
        if (breaksTerrainOrHull() || SERVER.shipVsShipBreaking.get() != FullStopConfig.ShipVsShipBreaking.NONE) {
            min = Math.min(min, SERVER.breakMinSpeed.get());
        }
        if (SERVER.breakTerrain.get() && SERVER.sweepVegetation.get()) {
            min = Math.min(min, SERVER.sweepMinSpeed.get());
        }
        // slime and honey hulls (ShipSlimeHoney): bounces, and the gentlest crash that still glues
        if (SERVER.slimeBounce.get()) min = Math.min(min, SERVER.slimeMinSpeed.get());
        if (SERVER.honeyGlue.get()) min = Math.min(min, ShipGlue.MIN_SPEED);
        return min;
    }

    private static boolean breaksTerrainOrHull() {
        return SERVER.breakTerrain.get() || SERVER.hullDamage.get();
    }

    /** Once per level tick: VS's reported crashes, then the hulls that crossed each other unreported ({@link ShipHullClash}). */
    static void process(ServerLevel level, ShipTracker tracker, List<ShipRef> ships, long now) {
        applyRestorations(level, now);

        Set<BlockPos> struck = new HashSet<>();
        List<ShipCollision> collisions = ShipCollisions.thisTick(level);
        if (!collisions.isEmpty()) {
            int[] levelBudget = {SERVER.terrainMaxBlocksPerTick.get()};
            for (ShipCollision collision : collisions) {
                if (collision.isTerrain()) {
                    terrain(level, tracker, collision, now, struck, levelBudget);
                } else if (collision.ship().id() < collision.other().id()) {
                    // Each pair comes twice (once from each side): handle it once.
                    ships(level, tracker, collision, now, struck, levelBudget);
                }
            }
        }
        ShipHullClash.process(level, tracker, ships, now, struck);
    }

    /**
     * The speed (m/s) a contact met at. One that just began: the largest of VS's approach speed,
     * the speed Camafy saw the two close at when that physics step began (VS measures after its
     * solver: a hard crash reads as two hulls already parting), and {@code before}, what the hull
     * was doing into it in the frames the game saw (all a contact has without Camafy's record).
     * The step's own speed matters on a busy server: its frames can show a striker at rest and then
     * already bounced off, an obsidian ram at 20 m/s judged at 0 and breaking nothing. One that
     * carries on: VS's approach alone - last tick's speed there belongs to the hit that started it,
     * and counting it again would grind a resting hull away.
     */
    private static double speedInto(boolean started, ShipCollision.Contact contact, double before) {
        return started ? Math.max(contact.metMps(), before) : contact.approachMps();
    }

    // ------------------------------------------------------------------
    // Ship against terrain
    // ------------------------------------------------------------------

    private static void terrain(ServerLevel level, ShipTracker tracker, ShipCollision collision, long now,
                                Set<BlockPos> struck, int[] levelBudget) {
        ShipRef ship = collision.ship();
        ShipTracker.State state = tracker.get(ship.id(), now);
        if (state == null || state.inGrace(now) || ship.isStatic()) return;

        double floor = slowestUseful();
        List<Strike> strikes = new ArrayList<>();
        for (ShipCollision.Contact contact : collision.contacts()) {
            // Into the terrain: the normal points out of it, toward the ship.
            double before = -state.prevVelocityAt(contact.point()).dot(contact.normal());
            double into = speedInto(collision.started(), contact, before);
            if (into < floor) continue;
            strikes.add(resolve(level, ship, contact, into));
        }
        if (strikes.isEmpty()) return;

        List<ShipImpactSounds.Hit> hits = new ArrayList<>(strikes.size());
        for (Strike strike : strikes) {
            hits.add(new ShipImpactSounds.Hit(strike.point(), strike.speed(), strike.terrain(), strike.hull()));
        }
        ShipImpactSounds.play(level, ship.id(), state.mass(), collision.started(), hits, now);

        if (breaksTerrainOrHull() && levelBudget[0] > 0) {
            smash(level, ship, state, strikes, now, struck, levelBudget);
        }
    }

    private static void smash(ServerLevel level, ShipRef ship, ShipTracker.State state, List<Strike> strikes, long now,
                              Set<BlockPos> struck, int[] levelBudget) {
        double massKg = state.mass();
        if (!Double.isFinite(massKg)) return;

        double breakMin = SERVER.breakMinSpeed.get();
        double softMin = SERVER.sweepVegetation.get() ? Math.min(breakMin, SERVER.sweepMinSpeed.get()) : breakMin;
        List<Strike> fastEnough = new ArrayList<>();
        double speed = 0.0;
        for (Strike strike : strikes) {
            boolean soft = strike.terrain() != null && ShipMaterials.sweepable(strike.terrain());
            if (strike.speed() < (soft ? softMin : breakMin)) continue;
            fastEnough.add(strike);
            speed = Math.max(speed, strike.speed());
        }
        if (fastEnough.isEmpty()) return;

        ShipPhysicsServer.Pilot pilot = new ShipPhysicsServer.Pilot(level, ship);
        double energy = 0.5 * EntityStackUtils.kgToUnits(massKg) * (speed / 20.0) * (speed / 20.0)
                * SERVER.terrainEnergyFraction.get() * honeyShare(strikes);
        double startEnergy = energy;
        int broken = 0;   // terrain blocks that gave way
        int smashed = 0;  // every block broken, the ship's own included (the per-ship cap)
        boolean held = false;
        Vec3 normalSum = Vec3.ZERO;
        Vec3 pointSum = Vec3.ZERO;

        for (Strike strike : fastEnough) {
            if (strike.terrain() == null || strike.hull() == null) {
                // Nothing to weigh the contact against (a VS physics entity, an unloaded chunk).
                held = true;
                continue;
            }
            if (smashed >= MAX_BLOCKS_PER_SHIP || levelBudget[0] <= 0) {
                held = true;
                break;
            }
            Verdict verdict = judge(level, strike);
            if (verdict == null) {
                if (SERVER.debugShipCollisionLog.get()) {
                    debug("ship {} vs terrain {} at {}: holds (toughness hull {} {} vs terrain {})", ship.id(),
                            strike.terrain(), strike.terrainPos(), strike.hull(),
                            fmt(ShipMaterials.toughness(level, strike.hullPos(), strike.hull())),
                            fmt(ShipMaterials.toughness(level, strike.terrainPos(), strike.terrain())));
                }
                held = true;
                continue;
            }
            // The same block reached through two contacts (or two ships) breaks once.
            if (!struck.add(verdict.pos())) continue;
            if (!mayBreak(level, verdict.pos(), pilot)) {
                held = true;
                continue;
            }
            KineticBlockInteractions.SmashOutcome outcome = KineticBlockInteractions.smashBlock(level, verdict.pos(),
                    verdict.state(), verdict.cost(), energy, SERVER.terrainBlockDrops.get(), pilot.get());
            debug("ship {} ({} kg at {} m/s) vs {} at {}: {} gives, cost {} of {} units -> {}", ship.id(),
                    Math.round(massKg), fmt(strike.speed()), strike.terrain(), strike.terrainPos(),
                    verdict.hull() ? "hull " + verdict.state() : "terrain", fmt(verdict.cost()), fmt(energy),
                    outcome.broken() ? "broken" : outcome.cracked() ? "cracked" : "held");
            if (outcome.broken()) {
                energy -= outcome.spent();
                levelBudget[0]--;
                smashed++;
                if (verdict.hull()) {
                    // The ship lost a block, the terrain stood: nothing to plough on through.
                    held = true;
                    continue;
                }
                broken++;
                normalSum = normalSum.add(strike.normal());
                pointSum = pointSum.add(strike.point());
            } else {
                held = true;
            }
        }

        if (broken > 0 && !held && normalSum.lengthSqr() > 1.0e-8) {
            // Everything gave: hand the ship back its momentum into the terrain, less the cost.
            double spentJoules = (startEnergy - energy) * EntityStackUtils.JOULES_PER_ENERGY_UNIT;
            double squared = speed * speed - 2.0 * spentJoules * SERVER.terrainBreakDrag.get() / massKg;
            double target = Math.sqrt(Math.max(0.0, squared));
            if (target > 0.0) {
                PENDING.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>()).put(ship.id(),
                        new Restore(normalSum.normalize(), pointSum.scale(1.0 / broken), target, now + 1));
            }
        }
    }

    /** Finds the terrain block and the ship's own block at a contact (never loading a chunk). */
    static Strike resolve(ServerLevel level, ShipRef ship, ShipCollision.Contact contact, double speed) {
        Vec3 point = contact.point();
        Vec3 normal = contact.normal();
        BlockPos terrainPos = null;
        BlockState terrain = null;
        for (double depth : PROBE_DEPTHS) {
            BlockPos pos = BlockPos.containing(point.subtract(normal.scale(depth)));
            if (Ships.inShipyard(level, pos)) continue; // never the ship's own blocks
            BlockState state = loadedState(level, pos);
            if (state != null && !state.getCollisionShape(level, pos).isEmpty()) {
                terrainPos = pos;
                terrain = state;
                break;
            }
        }
        BlockPos hullPos = null;
        BlockState hull = null;
        for (double depth : PROBE_DEPTHS) {
            Vec3 inShip = ship.toShip(point.add(normal.scale(depth)));
            if (inShip == null) break;
            BlockPos pos = BlockPos.containing(inShip);
            BlockState state = loadedState(level, pos);
            if (state != null && !state.isAir()) {
                hullPos = pos;
                hull = state;
                break;
            }
        }
        return new Strike(point, normal, speed, terrainPos, terrain, hullPos, hull);
    }

    // ------------------------------------------------------------------
    // Ship against ship (shipVsShipBreaking, FULL by default)
    // ------------------------------------------------------------------

    /**
     * One ship-against-ship contact that qualified this tick, with the block of each ship there
     * ({@code normal} from {@code b}'s block toward {@code a}'s, {@code speed} how fast the two
     * closed there, m/s).
     */
    record PairStrike(Vec3 point, Vec3 normal, double speed, @Nullable BlockPos posA, @Nullable BlockState blockA,
                      @Nullable BlockPos posB, @Nullable BlockState blockB) {}

    /**
     * Two ships colliding, handled once per pair and tick (Camafy reports each pair from both
     * sides, whichever source). Both hulls sound. Then the same rules as a hull against terrain,
     * for the pair: at each contact the WEAKER of the two blocks that meet breaks (either
     * ship's: obsidian rammed into planks breaks planks, never obsidian), or BOTH when they are
     * within {@code mutualBreakRatio} of each other (planks against planks at speed: neither is
     * tough enough to survive the other), paid for, fastest contact first, out of the collision's
     * energy - {@code 1/2 mu v^2 * terrainEnergyFraction}, with {@code mu} the pair's reduced mass
     * (a static ship counts as immovable) and {@code v} the fastest closing speed. If everything
     * that was struck broke, the two are handed back their closing speed the tick after, less
     * what breaking cost, by equal and opposite impulses at the contact (the ram ploughs on, both
     * ships slowed); otherwise VS's collision stands. Hulls that cross each other between two
     * physics frames, which VS never reports, are {@link ShipHullClash}'s.
     */
    private static void ships(ServerLevel level, ShipTracker tracker, ShipCollision collision, long now,
                              Set<BlockPos> struck, int[] levelBudget) {
        ShipRef a = collision.ship();
        ShipRef b = collision.other();
        ShipTracker.State stateA = tracker.get(a.id(), now);
        ShipTracker.State stateB = tracker.get(b.id(), now);
        if (stateA == null || stateB == null || stateA.inGrace(now) || stateB.inGrace(now)) {
            debug("ships {} and {}: crash not judged ({})", a.id(), b.id(), stateA == null || stateB == null ? "no frames yet"
                    : "in grace: " + (stateA.inGrace(now) ? "ship " + a.id() + " " + stateA.graceLeft(now) + " ticks" : "")
                    + (stateB.inGrace(now) ? " ship " + b.id() + " " + stateB.graceLeft(now) + " ticks" : ""));
            return;
        }
        double massA = a.isStatic() ? Double.POSITIVE_INFINITY : stateA.mass();
        double massB = b.isStatic() ? Double.POSITIVE_INFINITY : stateB.mass();
        double reduced = reducedMass(massA, massB);

        double floor = slowestUseful();
        double slowest = Double.POSITIVE_INFINITY, fastestSeen = 0.0, beforeSeen = 0.0;
        List<ShipImpactSounds.Hit> hits = new ArrayList<>();
        List<PairStrike> strikes = new ArrayList<>();
        for (ShipCollision.Contact contact : collision.contacts()) {
            Vec3 point = contact.point();
            Vec3 normal = contact.normal(); // from b toward a
            // Two ships' crash can reach the game frames late (the two already bounced apart in
            // the frames before this one): the closing speed of the frames before that counts too,
            // over the last few ticks' frames.
            double before = Math.max(-stateA.prevVelocityAt(point).subtract(stateB.prevVelocityAt(point)).dot(normal),
                    -stateA.prev2VelocityAt(point).subtract(stateB.prev2VelocityAt(point)).dot(normal));
            if (collision.started()) {
                before = Math.max(before, stateA.recentClosing(stateB, point, normal, now, PAIR_LATE_TICKS));
            }
            double speed = speedInto(collision.started(), contact, before);
            slowest = Math.min(slowest, speed);
            fastestSeen = Math.max(fastestSeen, speed);
            beforeSeen = Math.max(beforeSeen, before);
            if (speed < floor) continue;
            BlockPos posA = shipBlock(level, a, point, normal, 1.0);
            BlockPos posB = shipBlock(level, b, point, normal, -1.0);
            BlockState blockA = posA != null ? loadedState(level, posA) : null;
            BlockState blockB = posB != null ? loadedState(level, posB) : null;
            hits.add(new ShipImpactSounds.Hit(point, speed, blockA, blockB));
            strikes.add(new PairStrike(point, normal, speed, posA, blockA, posB, blockB));
        }
        if (strikes.isEmpty()) {
            debug("ships {} and {}: crash too slow to break anything ({} contact(s), fastest {} m/s, frames before it {} m/s, "
                    + "floor {} m/s)", a.id(), b.id(), collision.contacts().size(), fmt(fastestSeen), fmt(beforeSeen), fmt(floor));
            return;
        }
        ShipImpactSounds.play(level, a.id(), Double.isFinite(reduced) ? reduced : Math.min(massA, massB),
                collision.started(), hits, now);

        if (SERVER.shipVsShipBreaking.get() == FullStopConfig.ShipVsShipBreaking.NONE || !Double.isFinite(reduced)
                || levelBudget[0] <= 0) {
            return;
        }
        strikes.sort((x, y) -> Double.compare(y.speed(), x.speed()));
        double speed = strikes.get(0).speed();
        if (speed < SERVER.breakMinSpeed.get()) {
            debug("ships {} and {}: met at {} m/s (frames before it {} m/s), under breakMinSpeed {}: nothing breaks", a.id(), b.id(),
                    fmt(speed), fmt(beforeSeen), fmt(SERVER.breakMinSpeed.get()));
            return;
        }
        PairOutcome outcome = breakPair(level, a, b, reduced, speed, strikes, struck, levelBudget, MAX_BLOCKS_PER_SHIP,
                honeyPairShare(strikes), "hit", false);
        if (outcome.broken() > 0 && !outcome.held() && outcome.normalSum().lengthSqr() > 1.0e-8) {
            // Everything struck gave: the two keep closing, less what breaking cost.
            double spentJoules = outcome.spent() * EntityStackUtils.JOULES_PER_ENERGY_UNIT;
            double squared = speed * speed - 2.0 * spentJoules * SERVER.terrainBreakDrag.get() / reduced;
            double target = Math.sqrt(Math.max(0.0, squared));
            if (target > 0.0) {
                PENDING_PAIRS.computeIfAbsent(level, l -> new ArrayList<>()).add(new PairRestore(a.id(), b.id(),
                        outcome.normalSum().normalize(), outcome.pointSum().scale(1.0 / outcome.broken()), target, now + 1));
            }
        }
    }

    /** What breaking did for a pair of ships this tick: blocks broken, energy spent, whether anything struck held. */
    record PairOutcome(int broken, boolean held, double spent, Vec3 normalSum, Vec3 pointSum) {}

    /** One block a ship-against-ship contact breaks: whose it is, and which ship (its pilot) answers for breaking it. */
    private record PairVerdict(BlockPos pos, BlockState state, double cost, ShipRef owner, ShipRef breaker) {}

    /**
     * Breaks what a pair's strikes (sorted fastest first) break, out of the pair's collision
     * energy at {@code speed} (m/s, the fastest strike) with reduced mass {@code reduced} (kg):
     * at most {@code pairCap} blocks for the pair and what {@code levelBudget} has left. The
     * same block reached twice (or by two pairs) breaks once ({@code struck}). With
     * {@code noCracks} a block the energy doesn't cover is left alone rather than cracked: a
     * swept crossing VS's solver has already resolved reads slow here, and a crack it made would
     * count as the same impact for eight ticks and refuse the real crash VS reports a tick later.
     */
    static PairOutcome breakPair(ServerLevel level, ShipRef a, ShipRef b, double reduced, double speed, List<PairStrike> strikes,
                                 Set<BlockPos> struck, int[] levelBudget, int pairCap, double honeyShare, String how, boolean noCracks) {
        FullStopConfig.ShipVsShipBreaking mode = SERVER.shipVsShipBreaking.get();
        double breakMin = SERVER.breakMinSpeed.get();
        double energy = 0.5 * EntityStackUtils.kgToUnits(reduced) * (speed / 20.0) * (speed / 20.0)
                * SERVER.terrainEnergyFraction.get() * honeyShare;
        double startEnergy = energy;
        int broken = 0;
        boolean held = false;
        Vec3 normalSum = Vec3.ZERO;
        Vec3 pointSum = Vec3.ZERO;
        for (PairStrike strike : strikes) {
            if (strike.speed() < breakMin) break;
            if (strike.blockA() == null || strike.blockB() == null) {
                held = true; // nothing to weigh (an unloaded chunk)
                continue;
            }
            if (broken >= pairCap || levelBudget[0] <= 0) {
                held = true;
                break;
            }
            List<PairVerdict> verdicts = judgePair(level, a, strike.posA(), strike.blockA(), b, strike.posB(), strike.blockB(), mode);
            if (verdicts.isEmpty()) {
                held = true;
                continue;
            }
            for (PairVerdict verdict : verdicts) {
                if (broken >= pairCap || levelBudget[0] <= 0) {
                    held = true;
                    break;
                }
                if (noCracks && energy < verdict.cost()) {
                    held = true;
                    continue;
                }
                // The same block reached through two contacts breaks once.
                if (!struck.add(verdict.pos())) continue;
                ShipPhysicsServer.Pilot pilot = new ShipPhysicsServer.Pilot(level, verdict.breaker());
                if (!mayBreak(level, verdict.pos(), pilot)) {
                    held = true;
                    continue;
                }
                KineticBlockInteractions.SmashOutcome outcome = KineticBlockInteractions.smashBlock(level, verdict.pos(),
                        verdict.state(), verdict.cost(), energy, SERVER.terrainBlockDrops.get(), pilot.get());
                debug("ships {} and {} ({}, {} kg reduced) at {} m/s: {} of ship {} ({}), cost {} of {} units -> {}",
                        a.id(), b.id(), how, Math.round(reduced), fmt(strike.speed()), verdict.state(), verdict.owner().id(),
                        verdicts.size() == 2 ? "both give" : "the weaker gives", fmt(verdict.cost()), fmt(energy),
                        outcome.broken() ? "broken" : outcome.cracked() ? "cracked" : "held");
                if (outcome.broken()) {
                    energy -= outcome.spent();
                    broken++;
                    levelBudget[0]--;
                    normalSum = normalSum.add(strike.normal());
                    pointSum = pointSum.add(strike.point());
                } else {
                    held = true;
                }
            }
        }
        return new PairOutcome(broken, held, startEnergy - energy, normalSum, pointSum);
    }

    /**
     * Which of two ships' touching blocks give, and what each costs: the weaker by the toughness
     * rule, or both within {@code mutualBreakRatio} of each other ({@link ShipMaterials}); none
     * that FullStop may not break at all, or that {@code mode} FRAGILE leaves alone.
     */
    private static List<PairVerdict> judgePair(ServerLevel level, ShipRef a, BlockPos posA, BlockState blockA, ShipRef b,
                                               BlockPos posB, BlockState blockB, FullStopConfig.ShipVsShipBreaking mode) {
        double toughA = ShipMaterials.toughness(level, posA, blockA);
        double toughB = ShipMaterials.toughness(level, posB, blockB);
        double multiplier = SERVER.breakCostMultiplier.get();
        double ratio = SERVER.mutualBreakRatio.get();
        List<PairVerdict> out = new ArrayList<>(2);
        switch (ShipMaterials.whichGives(toughA, toughB, ratio)) {
            case FIRST -> out.add(new PairVerdict(posA, blockA, ShipMaterials.breakCost(toughA, toughB, multiplier), a, b));
            case SECOND -> out.add(new PairVerdict(posB, blockB, ShipMaterials.breakCost(toughB, toughA, multiplier), b, a));
            case BOTH -> {
                out.add(new PairVerdict(posA, blockA, ShipMaterials.mutualCost(toughA, multiplier, ratio), a, b));
                out.add(new PairVerdict(posB, blockB, ShipMaterials.mutualCost(toughB, multiplier, ratio), b, a));
            }
            case NEITHER -> {
            }
        }
        out.removeIf(v -> (mode == FullStopConfig.ShipVsShipBreaking.FRAGILE && !v.state().is(FullStopTags.FRAGILE))
                || !breakable(level, v.pos(), v.state()));
        return out;
    }

    /**
     * What is left of a crash's breaking energy when honey took part in it: honey (block tag
     * fullstop:gluey) soaks up honeySoftening of the hit, on either side of any contact.
     */
    private static double honeyShare(List<Strike> strikes) {
        for (Strike strike : strikes) {
            if (ShipSlimeHoney.gluey(strike.terrain()) || ShipSlimeHoney.gluey(strike.hull())) {
                return 1.0 - SERVER.honeySoftening.get();
            }
        }
        return 1.0;
    }

    private static double honeyPairShare(List<PairStrike> strikes) {
        for (PairStrike strike : strikes) {
            if (ShipSlimeHoney.gluey(strike.blockA()) || ShipSlimeHoney.gluey(strike.blockB())) {
                return 1.0 - SERVER.honeySoftening.get();
            }
        }
        return 1.0;
    }

    /** The reduced mass of two bodies (kg); the other's mass when one is immovable, +infinity when both are. */
    static double reducedMass(double massA, double massB) {
        if (Double.isInfinite(massA)) return massB;
        if (Double.isInfinite(massB)) return massA;
        return massA * massB / (massA + massB);
    }

    @Nullable
    static BlockPos shipBlock(ServerLevel level, ShipRef ship, Vec3 point, Vec3 normal, double side) {
        for (double depth : PROBE_DEPTHS) {
            Vec3 inShip = ship.toShip(point.add(normal.scale(depth * side)));
            if (inShip == null) return null;
            BlockPos pos = BlockPos.containing(inShip);
            BlockState state = loadedState(level, pos);
            if (state != null && !state.isAir()) return pos;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The rules
    // ------------------------------------------------------------------

    /** Which block breaks (the ship's own when {@code hull}), and what it costs. */
    private record Verdict(BlockPos pos, BlockState state, double cost, boolean hull) {}

    /** The block that gives at a contact, or null when neither may (the ship just stops). */
    @Nullable
    private static Verdict judge(ServerLevel level, Strike strike) {
        BlockPos terrainPos = strike.terrainPos();
        BlockState terrain = strike.terrain();
        BlockPos hullPos = strike.hullPos();
        BlockState hull = strike.hull();
        double multiplier = SERVER.breakCostMultiplier.get();
        boolean terrainMay = SERVER.breakTerrain.get() && level.isPositionEntityTicking(terrainPos)
                && breakable(level, terrainPos, terrain);

        if (SERVER.sweepVegetation.get() && ShipMaterials.sweepable(terrain)) {
            // The few soft plants VS does collide with (azaleas, dripleaf) give to any hull.
            double tough = ShipMaterials.toughness(level, terrainPos, terrain);
            return terrainMay && Double.isFinite(tough) ? new Verdict(terrainPos, terrain, tough * multiplier, false) : null;
        }

        double hullTough = ShipMaterials.toughness(level, hullPos, hull);
        double terrainTough = ShipMaterials.toughness(level, terrainPos, terrain);
        return switch (ShipMaterials.whichGives(terrainTough, hullTough)) {
            case FIRST -> terrainMay
                    ? new Verdict(terrainPos, terrain, ShipMaterials.breakCost(terrainTough, hullTough, multiplier), false)
                    : null;
            case SECOND -> SERVER.hullDamage.get() && breakable(level, hullPos, hull)
                    ? new Verdict(hullPos, hull, ShipMaterials.breakCost(hullTough, terrainTough, multiplier), true)
                    : null;
            // (terrain keeps the strict rule: whichGives without a mutual band never says BOTH)
            case NEITHER, BOTH -> null;
        };
    }

    /** Whether FullStop may break this block at all (terrain or hull), whatever hits it. */
    private static boolean breakable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0) return false;
        if (state.is(FullStopTags.SHIP_UNBREAKABLE)) return false;
        // Slime and honey are what ships bounce off: they never shatter.
        if (state.isStickyBlock()) return false;
        return !state.hasBlockEntity() || SERVER.terrainBreakBlockEntities.get();
    }

    /** Mob griefing (for an unpiloted ship) and the minigame host's rules for this block. */
    private static boolean mayBreak(ServerLevel level, BlockPos pos, ShipPhysicsServer.Pilot pilot) {
        Entity cause = pilot.get();
        if (cause == null && SERVER.unpilotedFollowsMobGriefing.get()
                && !ForgeEventFactory.getMobGriefingEvent(level, null)) {
            return false;
        }
        return BlockAlterRules.mayAlter(level, pos, cause);
    }

    /** The block at {@code pos}, or null when its chunk isn't loaded (never loads one). */
    @Nullable
    static BlockState loadedState(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    // ------------------------------------------------------------------
    // Momentum restoration
    // ------------------------------------------------------------------

    private static void applyRestorations(ServerLevel level, long now) {
        applyPairRestorations(level, now);
        Long2ObjectMap<Restore> pending = PENDING.get(level);
        if (pending == null || pending.isEmpty()) return;
        var iterator = pending.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<Restore> entry = iterator.next();
            // Read before removing: fastutil's entry is a view of its slot, dead once removed.
            long shipId = entry.getLongKey();
            Restore restore = entry.getValue();
            if (restore.dueTick() > now) continue;
            iterator.remove();
            if (restore.dueTick() < now - 1) continue; // stale (the level didn't tick)

            ShipRef ship = Ships.byId(level, shipId);
            if (ship == null) continue;
            Vec3 hullV = ship.velocityAt(restore.point());
            double mass = ship.physicalMass();
            if (hullV == null || !(mass > 0) || !Double.isFinite(mass)) continue;
            // Speed into the terrain now vs what it should keep after ploughing through. The push is sized by
            // the ship's effective mass at the point (a point off the centre mostly turns the ship, and its
            // speed there answers to much less than the whole mass), never by more than the speed it restores.
            double into = -hullV.dot(restore.normal());
            double missing = restore.targetSpeed() - into;
            if (missing <= 0) continue;
            Vec3 push = restore.normal().scale(-1.0);
            double effective = ShipInertia.effectiveMassKg(ship, mass, restore.point(), push);
            ShipImpulses.applyCapped(level, ship, push.scale(effective * missing), restore.point(), restore.targetSpeed());
        }
    }

    /**
     * Two ships that broke through each other close again at what they kept: equal and opposite
     * impulses at the contact, sized by the pair's reduced mass (a static ship takes none).
     */
    private static void applyPairRestorations(ServerLevel level, long now) {
        List<PairRestore> pending = PENDING_PAIRS.get(level);
        if (pending == null || pending.isEmpty()) return;
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            PairRestore restore = iterator.next();
            if (restore.dueTick() > now) continue;
            iterator.remove();
            if (restore.dueTick() < now - 1) continue; // stale (the level didn't tick)

            ShipRef a = Ships.byId(level, restore.a());
            ShipRef b = Ships.byId(level, restore.b());
            if (a == null || b == null) continue;
            Vec3 va = a.velocityAt(restore.point());
            Vec3 vb = b.velocityAt(restore.point());
            double massA = a.isStatic() ? Double.POSITIVE_INFINITY : a.physicalMass();
            double massB = b.isStatic() ? Double.POSITIVE_INFINITY : b.physicalMass();
            if (va == null || vb == null || !(massA > 0) || !(massB > 0)) continue;
            double reduced = reducedMass(massA, massB);
            if (!Double.isFinite(reduced)) continue;
            double closing = vb.subtract(va).dot(restore.normal());
            double missing = restore.targetClosing() - closing;
            if (missing <= 0) continue;
            Vec3 impulse = restore.normal().scale(reduced * missing);
            if (Double.isFinite(massA)) ShipImpulses.applyCapped(level, a, impulse.scale(-1.0), restore.point(), restore.targetClosing());
            if (Double.isFinite(massB)) ShipImpulses.applyCapped(level, b, impulse, restore.point(), restore.targetClosing());
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static void debug(String message, Object... args) {
        if (SERVER.debugShipCollisionLog.get()) {
            LOGGER.info("[FullStop] " + message, args);
        }
    }
}
