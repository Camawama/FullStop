package net.camacraft.fullstop.server.physics.ship;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.camacraft.camafy.compat.vs.ShipContact;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Hulls that cross each other between two physics frames ({@code shipClashSweep}). A block of a
 * 256 RPM rotor's tip moves twenty blocks a tick; Valkyrien Skies resolves the two ships only
 * where they overlap at the end of the step, so two such rotors pass through each other with
 * nothing reported (the owner: a sound and some particles now and then, a few planks lost).
 *
 * <p>Each tick, for every pair of loaded ships whose swept bounds meet and whose hulls close
 * faster than {@code breakMinSpeed}, every block of the smaller ship that can reach the other is
 * followed along the path it really took over the tick - the two ships' poses of last tick and
 * this one, interpolated (position, scale, rotation slerped), sampled every half block of
 * relative travel - as a cube in the other ship's space, against the other's actual blocks
 * (Camafy's exact oriented-box test). Every pair of blocks that crossed is a strike at the speed
 * their material closed there; the strikes break blocks by the ship-vs-ship rule
 * ({@link ShipTerrainImpacts#breakPair}: the weaker gives, or both within
 * {@code mutualBreakRatio} of each other), fastest first, out of the pair's collision energy
 * ({@code 1/2 mu v^2 * terrainEnergyFraction} at the fastest strike), at most
 * {@code shipClashMaxBlocksPerTick} per level and per pair. Both hulls sound. The ships'
 * momentum is left to VS: a rotor ploughs on through what it broke, and whatever VS resolved of
 * the overlap stands.
 *
 * <p>Cost: a pair is only looked at when its swept bounds meet; a block only when its own
 * travel plus the other ship's could carry it into the other's bounds; a sample only when the
 * cube's centre is within a block of the other's blocks. Two 3x3x16 rotors crossing tips are a
 * few dozen blocks times a hundred samples. Each ship's blocks are kept for a while
 * ({@link #HULL_TICKS}, or until its mass changes); a hull of more than {@link #MAX_SWEPT_CELLS}
 * blocks is never the one swept (it can still be hit by a smaller one's blocks).
 */
final class ShipHullClash {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Blocks of relative travel between two samples of a block's path (half a block: nothing a block wide is stepped over). */
    private static final double STEP = 0.5;
    private static final int MAX_SAMPLES = 128;
    /** The tick's poses are kept at this many evenly spaced moments per pair: matrix work per pair, not per sample. */
    private static final int POSE_STEPS = MAX_SAMPLES;
    /** How long (ticks) a ship's blocks are kept before being looked up again. */
    private static final int HULL_TICKS = 100;
    /** A hull with more blocks than this isn't swept (its blocks aren't even listed). */
    private static final int MAX_SWEPT_CELLS = 16384;
    /** Most pairs looked at per level per tick. */
    private static final int MAX_PAIRS = 64;
    /** Half the diagonal of a block, and then some: how far a cube's centre may be from a cell it overlaps. */
    private static final double CUBE_REACH = 0.9;
    private static final double TICK = 0.05;

    /** A ship's blocks (packed positions, ship space), when they were listed and its mass then. Null cells: too many. */
    private record Hull(long found, double mass, long @Nullable [] cells) {}

    private static final Map<ServerLevel, Long2ObjectOpenHashMap<Hull>> HULLS = new WeakHashMap<>();

    private ShipHullClash() {
    }

    /** The two poses a ship had this tick (last tick's and this one's), and any pose in between. */
    private static final class Poses {
        final Vec3 anchor;      // a point of the ship (its centre of mass), ship space
        final Vec3 prev;        // where it was last tick, world
        final Vec3 now;         // where it is now, world
        final Quaterniond rotPrev;
        final Quaterniond rotNow;
        final double scalePrev;
        final double scaleNow;
        final double travel;    // blocks the anchor moved over the tick
        final double turn;      // radians the ship turned over the tick

        private Poses(Vec3 anchor, Vec3 prev, Vec3 now, Quaterniond rotPrev, Quaterniond rotNow, double scalePrev, double scaleNow) {
            this.anchor = anchor;
            this.prev = prev;
            this.now = now;
            this.rotPrev = rotPrev;
            this.rotNow = rotNow;
            this.scalePrev = scalePrev;
            this.scaleNow = scaleNow;
            this.travel = now.distanceTo(prev);
            Quaterniond delta = new Quaterniond(rotNow).mul(new Quaterniond(rotPrev).conjugate()).normalize();
            this.turn = 2.0 * Math.acos(Math.min(1.0, Math.abs(delta.w)));
        }

        @Nullable
        static Poses of(ShipRef ship) {
            Vec3 com = ship.position();
            Vec3 anchor = com == null ? null : ship.toShip(com);
            Vec3 before = anchor == null ? null : ship.toWorld(anchor, ShipRef.Pose.PREV_TICK);
            Quaterniond rotPrev = ship.rotation(ShipRef.Pose.PREV_TICK);
            Quaterniond rotNow = ship.rotation(ShipRef.Pose.TICK);
            if (before == null || rotPrev == null || rotNow == null) return null;
            double scalePrev = ship.scale(ShipRef.Pose.PREV_TICK);
            double scaleNow = ship.scale(ShipRef.Pose.TICK);
            if (!(scalePrev > 0.0) || !(scaleNow > 0.0) || !Double.isFinite(scalePrev + scaleNow)) return null;
            return new Poses(anchor, before, com, rotPrev, rotNow, scalePrev, scaleNow);
        }

        /** Ship space to the world, the ship posed as it was at {@code f} of the way through the tick. */
        Matrix4d shipToWorld(double f) {
            Vec3 anchorAt = prev.lerp(now, f);
            double scaleAt = Mth.lerp(f, scalePrev, scaleNow);
            Quaterniond rotationAt = new Quaterniond(rotPrev).slerp(rotNow, f);
            return new Matrix4d().translation(anchorAt.x, anchorAt.y, anchorAt.z).rotate(rotationAt).scale(scaleAt)
                    .translate(-anchor.x, -anchor.y, -anchor.z);
        }

        /** How far (blocks) the ship's material at {@code worldPoint} can have moved over the tick, at most. */
        double reach(Vec3 worldPoint) {
            return travel + turn * worldPoint.distanceTo(now);
        }
    }

    /** One ship's part in this tick's clashes. */
    private record Party(ShipRef ship, ShipTracker.State state, Poses poses, AABB bounds, double radius, double reach, Hull hull) {}

    static void process(ServerLevel level, ShipTracker tracker, List<ShipRef> ships, long now, Set<BlockPos> struck) {
        if (ships.size() < 2 || !SERVER.shipClashSweep.get()
                || SERVER.shipVsShipBreaking.get() == FullStopConfig.ShipVsShipBreaking.NONE) {
            return;
        }
        double breakMin = SERVER.breakMinSpeed.get();
        List<Party> parties = new ArrayList<>(ships.size());
        for (ShipRef ship : ships) {
            ShipTracker.State state = tracker.get(ship.id(), now);
            AABB bounds = ship.worldAabb();
            if (state == null || bounds == null || state.inGrace(now)) continue;
            Poses poses = Poses.of(ship);
            if (poses == null) continue;
            double radius = 0.5 * Math.sqrt(bounds.getXsize() * bounds.getXsize() + bounds.getYsize() * bounds.getYsize()
                    + bounds.getZsize() * bounds.getZsize());
            double reach = poses.travel + poses.turn * radius;
            parties.add(new Party(ship, state, poses, bounds, radius, reach, null));
        }
        int[] budget = {SERVER.shipClashMaxBlocksPerTick.get()};
        // (debugShipCollisionLog: every pair that could clash, once a second)
        boolean log = SERVER.debugShipCollisionLog.get() && now % 20 == 0;
        int pairs = 0;
        for (int i = 0; i < parties.size() && budget[0] > 0; i++) {
            Party a = parties.get(i);
            for (int j = i + 1; j < parties.size() && budget[0] > 0; j++) {
                Party b = parties.get(j);
                if (a.ship().isStatic() && b.ship().isStatic()) continue;
                if (!a.bounds().inflate(a.reach()).intersects(b.bounds().inflate(b.reach()))) continue;
                // the hulls must close faster than anything breaks at, somewhere, over the tick
                double closing = (a.reach() + b.reach()) / TICK;
                if (closing < breakMin) {
                    if (log) {
                        LOGGER.info("[FullStop] ships {} and {}: near, but their hulls close at most {} m/s over the tick (ship {}: moved {}, "
                                        + "turned {} rad; ship {}: moved {}, turned {} rad): no clash test", a.ship().id(), b.ship().id(),
                                fmt(closing), a.ship().id(), fmt(a.poses().travel), fmt(a.poses().turn), b.ship().id(), fmt(b.poses().travel),
                                fmt(b.poses().turn));
                    }
                    continue;
                }
                if (++pairs > MAX_PAIRS) return;
                clash(level, a, b, now, struck, budget, log);
            }
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /** The smaller of the two hulls swept through the other; every block pair that crossed breaks by the pair rule. */
    private static void clash(ServerLevel level, Party a, Party b, long now, Set<BlockPos> struck, int[] budget, boolean log) {
        Hull hullA = hull(level, a.ship(), now);
        Hull hullB = hull(level, b.ship(), now);
        int countA = hullA.cells() == null ? Integer.MAX_VALUE : hullA.cells().length;
        int countB = hullB.cells() == null ? Integer.MAX_VALUE : hullB.cells().length;
        if (countA == Integer.MAX_VALUE && countB == Integer.MAX_VALUE) return; // both too big to sweep
        boolean aSweeps = countA <= countB;
        Party s = aSweeps ? a : b;
        Party t = aSweeps ? b : a;
        long[] cells = aSweeps ? hullA.cells() : hullB.cells();
        if (cells == null || cells.length == 0) return;

        BoundingBox tBlocks = t.ship().shipyardBlocks();
        if (tBlocks == null) return;
        AABB tNear = AABB.of(tBlocks).inflate(CUBE_REACH); // where a cube's centre may be to overlap one of t's blocks (t's space)
        // t's blocks never leave this far (world blocks) from its anchor, however it turns
        double tRadius = 0.0;
        for (int corner = 0; corner < 8; corner++) {
            double dx = ((corner & 1) == 0 ? tBlocks.minX() : tBlocks.maxX() + 1.0) - t.poses().anchor.x;
            double dy = ((corner & 2) == 0 ? tBlocks.minY() : tBlocks.maxY() + 1.0) - t.poses().anchor.y;
            double dz = ((corner & 4) == 0 ? tBlocks.minZ() : tBlocks.maxZ() + 1.0) - t.poses().anchor.z;
            tRadius = Math.max(tRadius, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        tRadius *= Math.max(t.poses().scalePrev, t.poses().scaleNow);
        double tAnchorTravel = t.poses().travel;
        // the tick's poses, at POSE_STEPS moments, as they are needed
        Matrix4d[] sToWorldAt = new Matrix4d[POSE_STEPS + 1];
        Matrix4d[] tToWorldAt = new Matrix4d[POSE_STEPS + 1];
        Matrix4d[] sToTAt = new Matrix4d[POSE_STEPS + 1];
        Matrix4d sToWorldNow = poseAt(s, sToWorldAt, POSE_STEPS);
        Matrix4d sToWorldPrev = poseAt(s, sToWorldAt, 0);
        Matrix4d tToWorldNow = poseAt(t, tToWorldAt, POSE_STEPS);
        Matrix4d tToWorldPrev = poseAt(t, tToWorldAt, 0);

        List<ShipTerrainImpacts.PairStrike> strikes = new ArrayList<>();
        double fastest = 0.0;
        Vector3d scratch = new Vector3d();
        int swept = 0, sampled = 0, tested = 0;
        for (long packed : cells) {
            BlockPos cell = BlockPos.of(packed);
            Vec3 centreLocal = Vec3.atCenterOf(cell);
            Vec3 pNow = at(sToWorldNow, centreLocal, scratch);
            Vec3 pPrev = at(sToWorldPrev, centreLocal, scratch);
            // Can this block reach t's blocks at all over the tick? Its path (a segment, near enough) against the sphere
            // t's blocks stay in about its anchor, which itself moves by at most t's travel.
            if (segmentDistance(pPrev, pNow, t.poses().now) > tRadius + tAnchorTravel + CUBE_REACH) continue;
            // the block's own travel over the tick, and the other ship's under it: what their relative travel is at most
            double relative = pNow.distanceTo(pPrev) + t.poses().reach(pNow);
            int samples = Mth.clamp((int) Math.ceil(relative / STEP), 1, MAX_SAMPLES);
            swept++;
            AABB cube = new AABB(cell);
            Set<BlockPos> met = null;
            int lastStep = -1;
            for (int k = 0; k <= samples; k++) {
                int step = (int) Math.round((double) k / samples * POSE_STEPS);
                if (step == lastStep) continue;
                lastStep = step;
                sampled++;
                // s's space at f -> the world at f -> t's space at f: the cube of s's block as an oriented box in t's space
                Matrix4d m = sToTAt[step];
                if (m == null) {
                    m = sToTAt[step] = new Matrix4d(poseAt(t, tToWorldAt, step)).invert().mul(poseAt(s, sToWorldAt, step));
                }
                m.transformPosition(scratch.set(centreLocal.x, centreLocal.y, centreLocal.z));
                if (!tNear.contains(scratch.x, scratch.y, scratch.z)) continue;
                tested++;
                List<ShipContact> contacts = t.ship().contacts(cube, m);
                if (contacts.isEmpty()) continue;
                Matrix4d sToWorld = poseAt(s, sToWorldAt, step);
                Vec3 worldPoint = at(sToWorld, centreLocal, scratch);
                for (ShipContact contact : contacts) {
                    if (met == null) met = new HashSet<>();
                    if (!met.add(contact.pos())) continue;
                    BlockState blockT = ShipTerrainImpacts.loadedState(level, contact.pos());
                    BlockState blockS = ShipTerrainImpacts.loadedState(level, cell);
                    if (blockT == null || blockT.isAir() || blockS == null || blockS.isAir()) continue;
                    // How fast the two blocks closed: how far they moved toward each other over the tick (the poses), or
                    // the hulls' material speeds of the frame before - never this frame's, which VS's solver may already
                    // have taken the crash out of (a ram it stopped reads as a slow touch, and a slow touch here must not
                    // crack a block the real crash then can't break: see breakPair's noCracks)
                    Vec3 tCentre = Vec3.atCenterOf(contact.pos());
                    Vec3 tMoved = at(tToWorldNow, tCentre, scratch).subtract(at(tToWorldPrev, tCentre, scratch));
                    double closedOverTick = pNow.subtract(pPrev).subtract(tMoved).length() / TICK;
                    double closedBefore = s.state().prevVelocityAt(worldPoint).subtract(t.state().prevVelocityAt(worldPoint)).length();
                    double speed = Math.max(closedOverTick, closedBefore);
                    fastest = Math.max(fastest, speed);
                    // the contact's normal comes back in s's space (the box's own frame): into the world, pointing out
                    // of t's block toward s's, so from b toward a when s is a
                    Vector3d n = sToWorld.transformDirection(scratch.set(contact.normal().x, contact.normal().y, contact.normal().z)).normalize();
                    Vec3 normal = aSweeps ? new Vec3(n.x, n.y, n.z) : new Vec3(-n.x, -n.y, -n.z);
                    strikes.add(aSweeps
                            ? new ShipTerrainImpacts.PairStrike(worldPoint, normal, speed, cell, blockS, contact.pos(), blockT)
                            : new ShipTerrainImpacts.PairStrike(worldPoint, normal, speed, contact.pos(), blockT, cell, blockS));
                }
            }
        }
        if (log) {
            Vec3 upA = a.ship().dirToWorld(new Vec3(0.0, 1.0, 0.0)), upB = b.ship().dirToWorld(new Vec3(0.0, 1.0, 0.0));
            LOGGER.info("[FullStop] ships {} and {}: {} of ship {}'s {} blocks followed through ship {} ({} samples, {} against its blocks): "
                            + "{} crossings, fastest {} m/s (ship {}: at y {}, tilted {} deg, moved {}, turned {} rad; ship {}: at y {}, tilted {} deg, "
                            + "moved {}, turned {} rad)", a.ship().id(), b.ship().id(), swept, s.ship().id(), cells.length, t.ship().id(), sampled,
                    tested, strikes.size(), fmt(fastest), a.ship().id(), fmt(a.poses().now.y),
                    upA == null ? "?" : fmt(Math.toDegrees(Math.acos(Mth.clamp(upA.y, -1.0, 1.0)))), fmt(a.poses().travel), fmt(a.poses().turn),
                    b.ship().id(), fmt(b.poses().now.y), upB == null ? "?" : fmt(Math.toDegrees(Math.acos(Mth.clamp(upB.y, -1.0, 1.0)))),
                    fmt(b.poses().travel), fmt(b.poses().turn));
        }
        if (strikes.isEmpty()) return;
        if (fastest < SERVER.breakMinSpeed.get()) return;

        double massA = a.ship().isStatic() ? Double.POSITIVE_INFINITY : a.state().mass();
        double massB = b.ship().isStatic() ? Double.POSITIVE_INFINITY : b.state().mass();
        double reduced = ShipTerrainImpacts.reducedMass(massA, massB);
        if (!Double.isFinite(reduced)) return;
        strikes.sort((x, y) -> Double.compare(y.speed(), x.speed()));

        // both hulls sound (and throw their particles) where they crossed hardest
        List<ShipImpactSounds.Hit> hits = new ArrayList<>(Math.min(4, strikes.size()));
        for (int i = 0; i < strikes.size() && i < 4; i++) {
            ShipTerrainImpacts.PairStrike strike = strikes.get(i);
            hits.add(new ShipImpactSounds.Hit(strike.point(), strike.speed(), strike.blockA(), strike.blockB()));
        }
        ShipImpactSounds.play(level, a.ship().id(), reduced, true, hits, now);

        int cap = Math.min(budget[0], SERVER.shipClashMaxBlocksPerTick.get());
        ShipTerrainImpacts.PairOutcome outcome = ShipTerrainImpacts.breakPair(level, a.ship(), b.ship(), reduced, fastest, strikes,
                struck, budget, cap, 1.0, "crossed", true);
        if (outcome.broken() > 0) {
            // what they lost changes both hulls: listed again next time
            forget(level, a.ship().id());
            forget(level, b.ship().id());
        }
        if (SERVER.debugShipCollisionLog.get()) {
            LOGGER.info("[FullStop] ships {} and {} crossed: {} blocks of ship {} met ship {}'s over the tick, closing at up to {} m/s"
                            + " -> {} broken{}", a.ship().id(), b.ship().id(), strikes.size(), s.ship().id(), t.ship().id(),
                    String.format(java.util.Locale.ROOT, "%.1f", fastest), outcome.broken(), outcome.held() ? " (some held)" : "");
        }
    }

    private static Vec3 at(Matrix4d shipToWorld, Vec3 local, Vector3d scratch) {
        shipToWorld.transformPosition(scratch.set(local.x, local.y, local.z));
        return new Vec3(scratch.x, scratch.y, scratch.z);
    }

    /** The party's ship-to-world matrix at moment {@code step} of {@link #POSE_STEPS}, kept once made. */
    private static Matrix4d poseAt(Party party, Matrix4d[] cache, int step) {
        Matrix4d m = cache[step];
        if (m == null) m = cache[step] = party.poses().shipToWorld((double) step / POSE_STEPS);
        return m;
    }

    /** How far {@code point} is from the segment {@code a}-{@code b}. */
    private static double segmentDistance(Vec3 a, Vec3 b, Vec3 point) {
        Vec3 ab = b.subtract(a);
        double lengthSqr = ab.lengthSqr();
        if (lengthSqr < 1.0e-12) return point.distanceTo(a);
        double f = Mth.clamp(point.subtract(a).dot(ab) / lengthSqr, 0.0, 1.0);
        return point.distanceTo(a.add(ab.scale(f)));
    }

    /** The ship's blocks, listed now or recently. */
    private static Hull hull(ServerLevel level, ShipRef ship, long now) {
        Long2ObjectOpenHashMap<Hull> known = HULLS.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        Hull hull = known.get(ship.id());
        double mass = ship.mass();
        if (hull != null && now - hull.found() <= HULL_TICKS && Math.abs(mass - hull.mass()) <= 0.005 * Math.max(1.0, mass)) {
            return hull;
        }
        LongArrayList cells = new LongArrayList();
        boolean[] tooMany = {false};
        ship.forEachBlock(state -> !state.isAir(), (pos, state) -> {
            if (tooMany[0]) return;
            if (cells.size() >= MAX_SWEPT_CELLS) {
                tooMany[0] = true;
                return;
            }
            cells.add(pos.asLong());
        });
        hull = new Hull(now, mass, tooMany[0] ? null : cells.toLongArray());
        known.put(ship.id(), hull);
        return hull;
    }

    private static void forget(ServerLevel level, long shipId) {
        Long2ObjectOpenHashMap<Hull> known = HULLS.get(level);
        if (known != null) known.remove(shipId);
    }

    /** Forgets the blocks of ships no longer loaded. */
    static void prune(ServerLevel level, List<ShipRef> loaded) {
        Long2ObjectOpenHashMap<Hull> known = HULLS.get(level);
        if (known == null || known.isEmpty()) return;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet ids = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(loaded.size());
        for (ShipRef ship : loaded) ids.add(ship.id());
        known.long2ObjectEntrySet().removeIf(entry -> !ids.contains(entry.getLongKey()));
    }
}
