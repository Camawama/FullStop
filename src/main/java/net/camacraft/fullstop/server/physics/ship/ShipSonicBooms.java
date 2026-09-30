package net.camacraft.fullstop.server.physics.ship;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.server.physics.SonicBooms;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Sonic booms of Valkyrien Skies ships ({@code shipSonicBooms}): a hull moving faster than sound anywhere - a fast
 * ship, or the tips of a fast rotor, whose centre may barely move - is a source for {@link SonicBooms}, from its fastest
 * point. Its own riders, seated on it or carried by it, hear no boom. A supersonic rotor also makes the continuous shock
 * train of {@link SonicBooms#train}: one shock per supersonic tip per turn, its tips counted as the separate directions
 * round the spin axis in which its outermost blocks are past the barrier (a two-arm rotor: 2; a single arm on a
 * bearing at one end: 1; a disc: its rim, 1), which its riders do hear (the hub is swept by the tips' shocks like
 * anywhere else) unless the ship as a whole flies supersonic.
 *
 * <p>How fast the hull goes is judged from the ship's latest physics frame ({@link ShipTracker}: {@code v + w x r}) at
 * the corners of its blocks' bounds first - a cheap upper bound - and, once that nears the barrier, at its real
 * outermost blocks: the corners of a turned or irregular ship's bounds stick out past any block (a disc's by 41%), and
 * read as supersonic a rim that isn't. A ship in its grace (a new one, or one that jumped: a hard crash) keeps its
 * stretch rather than ending it and booming again once it can be measured.</p>
 */
final class ShipSonicBooms {

    /** How long (ticks) a ship's outermost blocks are kept before being looked up again. */
    private static final int EXTREMES_TICKS = 100;
    /** Two supersonic tips within this angle of each other round the spin axis are one tip. */
    private static final double TIP_SPREAD = Math.toRadians(45.0);

    /** Each ship's outermost block corners (ship space), found when its hull first nears the barrier. */
    private static final Map<ServerLevel, Long2ObjectOpenHashMap<Extremes>> EXTREMES = new WeakHashMap<>();

    private record Extremes(long found, double mass, List<Vec3> points) {}

    private ShipSonicBooms() {
    }

    static void process(ServerLevel level, ShipRef ship, ShipTracker.State state, AABB bounds, long now) {
        if (!SERVER.shipSonicBooms.get()) return;
        SonicBooms.Key key = SonicBooms.Key.ship(ship.id());
        // a teleported or new ship's first frames can show any speed at all: unmeasured, its stretch goes on
        if (state.inGrace(now)) {
            SonicBooms.keepAlive(level, key);
            return;
        }
        Vec3[] fastest = fastestCorner(ship, state);
        if (fastest == null) {
            SonicBooms.keepAlive(level, key);
            return;
        }
        int tips = 0;
        if (fastest[1].lengthSqr() >= 0.64 * SonicBooms.SOUND_MPS * SonicBooms.SOUND_MPS) {
            Extremes extremes = extremes(level, ship, now);
            Vec3[] real = fastestOf(ship, state, extremes);
            if (real != null) {
                fastest = real;
                // while a stretch is live a tip keeps counting down to the stretch's own hysteresis, so jitter at the
                // barrier doesn't switch the train on and off
                double threshold = SonicBooms.isLive(level, key) ? SonicBooms.SUBSONIC_SHARE * SonicBooms.SOUND_MPS : SonicBooms.SOUND_MPS;
                tips = supersonicTips(ship, state, extremes, threshold);
            }
        }
        Predicate<ServerPlayer> aboard = player -> ship.equals(ShipEntities.mountedTo(player)) || ShipCompat.carriedBy(player, ship);
        if (!SonicBooms.observe(level, key, fastest[0], fastest[1], aboard)) return;
        // the shock train: one shock per supersonic tip per turn (a ship flying straight and fast makes none)
        double spin = state.angularVelocity().length();
        double rate = tips > 0 ? spin / (2.0 * Math.PI) * tips : 0.0;
        boolean ridersHear = state.velocity().length() < SonicBooms.SOUND_MPS;
        SonicBooms.train(level, key, state.position(), rate, ridersHear);
    }

    /** The corner of the ship's blocks' bounds moving fastest and its velocity ({@code {point, velocity}}, world, m/s). */
    @Nullable
    private static Vec3[] fastestCorner(ShipRef ship, ShipTracker.State state) {
        BoundingBox blocks = ship.shipyardBlocks();
        if (blocks == null) return null;
        Vec3[] best = null;
        double bestSpeed = -1.0;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 local = new Vec3((corner & 1) == 0 ? blocks.minX() : blocks.maxX() + 1.0,
                    (corner & 2) == 0 ? blocks.minY() : blocks.maxY() + 1.0,
                    (corner & 4) == 0 ? blocks.minZ() : blocks.maxZ() + 1.0);
            Vec3 world = ship.toWorld(local);
            if (world == null) return null;
            Vec3 velocity = state.velocityAt(world);
            double speed = velocity.lengthSqr();
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = new Vec3[]{world, velocity};
            }
        }
        return best;
    }

    /** The ship's outermost blocks, found now or recently (again after a while, or when its mass changed: blocks lost). */
    private static Extremes extremes(ServerLevel level, ShipRef ship, long now) {
        Long2ObjectOpenHashMap<Extremes> known = EXTREMES.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        Extremes extremes = known.get(ship.id());
        double mass = ship.mass();
        if (extremes == null || now - extremes.found() > EXTREMES_TICKS || Math.abs(mass - extremes.mass()) > 0.01 * Math.max(1.0, mass)) {
            extremes = new Extremes(now, mass, outermost(ship));
            known.put(ship.id(), extremes);
        }
        return extremes;
    }

    /** The outermost block corner moving fastest ({@code {point, velocity}}), or null when none can be placed. */
    @Nullable
    private static Vec3[] fastestOf(ShipRef ship, ShipTracker.State state, Extremes extremes) {
        Vec3[] best = null;
        double bestSpeed = -1.0;
        for (Vec3 local : extremes.points()) {
            Vec3 world = ship.toWorld(local);
            if (world == null) continue;
            Vec3 velocity = state.velocityAt(world);
            double speed = velocity.lengthSqr();
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = new Vec3[]{world, velocity};
            }
        }
        return best;
    }

    /**
     * How many separate tips of the hull are past {@code threshold} (m/s): its outermost corners moving that fast,
     * grouped by their direction from the centre of mass round the spin axis ({@link #TIP_SPREAD}). 0 when the ship
     * hardly spins (then its speed is its travel, and there is no train).
     */
    private static int supersonicTips(ShipRef ship, ShipTracker.State state, Extremes extremes, double threshold) {
        Vec3 w = state.angularVelocity();
        double spin = w.length();
        if (spin < 1.0e-3) return 0;
        Vec3 axis = w.scale(1.0 / spin);
        Vec3 e1 = (Math.abs(axis.y) < 0.9 ? axis.cross(new Vec3(0.0, 1.0, 0.0)) : axis.cross(new Vec3(1.0, 0.0, 0.0))).normalize();
        Vec3 e2 = axis.cross(e1);
        Vec3 com = state.position();
        List<Double> angles = new ArrayList<>();
        for (Vec3 local : extremes.points()) {
            Vec3 world = ship.toWorld(local);
            if (world == null) continue;
            if (state.velocityAt(world).lengthSqr() < threshold * threshold) continue;
            Vec3 r = world.subtract(com);
            angles.add(Math.atan2(r.dot(e2), r.dot(e1)));
        }
        if (angles.isEmpty()) return 0;
        if (angles.size() == 1) return 1;
        angles.sort(null);
        int gaps = 0;
        for (int i = 1; i < angles.size(); i++) {
            if (angles.get(i) - angles.get(i - 1) > TIP_SPREAD) gaps++;
        }
        if (angles.get(0) + 2.0 * Math.PI - angles.get(angles.size() - 1) > TIP_SPREAD) gaps++;
        return Math.max(1, gaps);
    }

    /**
     * The ship's outermost block corners (ship space): for each of 26 directions, the corner of its blocks reaching
     * farthest that way. The fastest point of a turning hull is one of these, or next to one.
     */
    private static List<Vec3> outermost(ShipRef ship) {
        double[][] dirs = new double[26][];
        int n = 0;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    dirs[n++] = new double[]{x, y, z};
                }
            }
        }
        double[] reach = new double[26];
        Vec3[] corner = new Vec3[26];
        java.util.Arrays.fill(reach, Double.NEGATIVE_INFINITY);
        ship.forEachBlock(state -> !state.isAir(), (pos, state) -> {
            for (int i = 0; i < 26; i++) {
                double[] d = dirs[i];
                double cx = pos.getX() + (d[0] > 0 ? 1.0 : 0.0), cy = pos.getY() + (d[1] > 0 ? 1.0 : 0.0), cz = pos.getZ() + (d[2] > 0 ? 1.0 : 0.0);
                double r = cx * d[0] + cy * d[1] + cz * d[2];
                if (r > reach[i]) {
                    reach[i] = r;
                    corner[i] = new Vec3(cx, cy, cz);
                }
            }
        });
        List<Vec3> points = new ArrayList<>(26);
        for (Vec3 c : corner) {
            if (c != null && !points.contains(c)) points.add(c);
        }
        return points;
    }

    /** Forgets the outermost blocks of ships no longer loaded. */
    static void prune(ServerLevel level, List<ShipRef> loaded) {
        Long2ObjectOpenHashMap<Extremes> known = EXTREMES.get(level);
        if (known == null || known.isEmpty()) return;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet ids = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(loaded.size());
        for (ShipRef ship : loaded) ids.add(ship.id());
        known.long2ObjectEntrySet().removeIf(entry -> !ids.contains(entry.getLongKey()));
    }
}
