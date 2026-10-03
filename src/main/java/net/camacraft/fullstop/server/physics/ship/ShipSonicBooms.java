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
import org.slf4j.Logger;

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

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** How long (ticks) a ship's outermost blocks are kept before being looked up again. */
    private static final int EXTREMES_TICKS = 100;

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
        boolean followed = SonicBooms.observe(level, key, fastest[0], fastest[1], aboard);
        // the shock train: one shock per supersonic tip per turn (a ship flying straight and fast makes none)
        double spin = state.angularVelocity().length();
        double rate = followed && tips > 0 ? spin / (2.0 * Math.PI) * tips : 0.0;
        boolean ridersHear = state.velocity().length() < SonicBooms.SOUND_MPS;
        if (followed) SonicBooms.train(level, key, state.position(), rate, fastest[1].length(), ridersHear);
        if (SERVER.debugShipCollisionLog.get()) {
            debug(level, ship, key, now, fastest[1].length(), spin, tips, rate, followed);
        }
    }

    /** What the debug log tracks per ship: whether its train was on last tick, and when it last said why there is none. */
    private static final class Debug {
        boolean trainOn;
        long lastQuiet = Long.MIN_VALUE / 2;
    }

    private static final Map<ServerLevel, Long2ObjectOpenHashMap<Debug>> DEBUG = new WeakHashMap<>();

    /**
     * {@code debugShipCollisionLog}: the train switching on or off, and, every three seconds while the hull's fastest
     * point is past 60% of the speed of sound without a train, why there is none (the owner's rotor: "I'm not hearing
     * it": tips at 214 m/s of 343 make none).
     */
    private static void debug(ServerLevel level, ShipRef ship, SonicBooms.Key key, long now, double speed, double spin, int tips,
                              double rate, boolean followed) {
        Long2ObjectOpenHashMap<Debug> known = DEBUG.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        Debug d = known.get(ship.id());
        if (d == null) known.put(ship.id(), d = new Debug());
        boolean live = followed && SonicBooms.isLive(level, key);
        boolean on = live && rate > 0.0;
        double c = SonicBooms.SOUND_MPS;
        if (on != d.trainOn) {
            d.trainOn = on;
            if (on) {
                LOGGER.info("[FullStop] ship {}: shock train ON: {} pulses/s ({} rad/s x {} supersonic tip(s)); fastest point {} m/s of {}",
                        ship.id(), fmt(rate), fmt(spin), tips, fmt(speed), fmt(c));
            } else {
                LOGGER.info("[FullStop] ship {}: shock train OFF (fastest point {} m/s of {}, {} tip(s) past the barrier, supersonic stretch live: {})",
                        ship.id(), fmt(speed), fmt(c), tips, live);
            }
        }
        if (!on && speed >= 0.6 * c && now - d.lastQuiet >= 60) {
            d.lastQuiet = now;
            String why = speed < c
                    ? "a tip must pass " + fmt(c) + " m/s: its spin (rad/s) times its distance from the axis (a 3x3x16 arm about its middle at "
                            + "256 RPM: 26.8 x 8 = 214)"
                    : tips == 0 ? "the ship flies fast but hardly spins: one boom as it passes, no train"
                    : !live ? "over the barrier, but its stretch hasn't begun (a spike of a tick booms nothing) or no air here" : "?";
            LOGGER.info("[FullStop] ship {}: no shock train: fastest point {} m/s of {} ({}%), spin {} rad/s, {} tip(s) past the barrier, stretch "
                    + "live: {}; {}", ship.id(), fmt(speed), fmt(c), Math.round(100.0 * speed / c), fmt(spin), tips, live, why);
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
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
     * grouped by their direction from the centre of mass round the spin axis ({@link SonicBooms#supersonicTips}).
     */
    private static int supersonicTips(ShipRef ship, ShipTracker.State state, Extremes extremes, double threshold) {
        List<Vec3[]> points = new ArrayList<>(extremes.points().size());
        for (Vec3 local : extremes.points()) {
            Vec3 world = ship.toWorld(local);
            if (world != null) points.add(new Vec3[]{world, state.velocityAt(world)});
        }
        return SonicBooms.supersonicTips(points, state.position(), state.angularVelocity(), threshold);
    }

    /**
     * The ship's outermost block corners (ship space): for each of 26 directions, the corner of its blocks reaching
     * farthest that way. The fastest point of a turning hull is one of these, or next to one.
     */
    private static List<Vec3> outermost(ShipRef ship) {
        SonicBooms.Outermost outermost = new SonicBooms.Outermost();
        ship.forEachBlock(state -> !state.isAir(), (pos, state) -> outermost.add(pos.getX(), pos.getY(), pos.getZ()));
        return outermost.points();
    }

    /** Forgets the outermost blocks of ships no longer loaded. */
    static void prune(ServerLevel level, List<ShipRef> loaded) {
        Long2ObjectOpenHashMap<Extremes> known = EXTREMES.get(level);
        Long2ObjectOpenHashMap<Debug> debug = DEBUG.get(level);
        if ((known == null || known.isEmpty()) && (debug == null || debug.isEmpty())) return;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet ids = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(loaded.size());
        for (ShipRef ship : loaded) ids.add(ship.id());
        if (known != null) known.long2ObjectEntrySet().removeIf(entry -> !ids.contains(entry.getLongKey()));
        if (debug != null) debug.long2ObjectEntrySet().removeIf(entry -> !ids.contains(entry.getLongKey()));
    }
}
