package net.camacraft.fullstop.common.compat.create.contraption;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ContraptionHandler;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.common.compat.create.CreateCompat;
import net.camacraft.fullstop.server.physics.SonicBooms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Sonic booms of Create's contraptions ({@code contraptionSonicBooms}): a moving contraption whose fastest point passes
 * the speed of sound is a source for {@link SonicBooms}, as a Valkyrien Skies hull is - one boom per supersonic stretch
 * for everyone its shock reaches, when it reaches them; its riders, seated on it or standing on it, hear none. A
 * bearing's contraption whose tips stay supersonic also makes a supersonic rotor's shock train: one shock per supersonic
 * tip per turn, its tips counted as the separate directions round the bearing's axis in which its outermost blocks are
 * past the barrier ({@link SonicBooms#supersonicTips}), heard by its riders too (they are at the hub). Each listener hears
 * the train from where the arm's supersonic part sweeps nearest them (the ring between the radius where the blades pass
 * the barrier and the tips): standing by the far end of a long arm, the cracks come from the tips going by, not from the
 * bearing. At Create's top
 * speed (256 RPM, 26.8 rad/s) an arm's tip passes 343 m/s from 12.8 blocks out.
 *
 * <p>The fastest point is judged at the corners of the box its blocks fill (a cheap upper bound) and, once that nears
 * the barrier, at its real outermost blocks ({@link SonicBooms.Outermost}, kept per contraption): the corners of a
 * cross-shaped windmill's box stick out past its sails. Contraptions on a Valkyrien Skies ship are left to the ship.
 * Server only.</p>
 */
public final class ContraptionSonicBooms {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Each contraption's outermost block corners (its frame) and how many blocks it had when they were found. */
    private record Extremes(int blocks, List<Vec3> points) {}

    private static final Map<Contraption, Extremes> EXTREMES = new WeakHashMap<>();
    /** Whether each contraption's stretch was live last tick (for the debug log). */
    private static final Map<AbstractContraptionEntity, Boolean> WAS_LIVE = new WeakHashMap<>();

    private ContraptionSonicBooms() {
    }

    private static boolean enabled() {
        return CreateCompat.active() && SERVER.contraptionSonicBooms.get();
    }

    /** Each server level tick: every loaded contraption that may be supersonic. */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || !enabled()) return;
        Map<Integer, WeakReference<AbstractContraptionEntity>> loaded = ContraptionHandler.loadedContraptions.get(level);
        if (loaded == null || loaded.isEmpty()) return;
        List<AbstractContraptionEntity> contraptions = new ArrayList<>(loaded.size());
        for (WeakReference<AbstractContraptionEntity> ref : loaded.values()) {
            AbstractContraptionEntity c = ref.get();
            if (c != null && c.isAlive()) contraptions.add(c);
        }
        for (AbstractContraptionEntity c : contraptions) {
            try {
                process(level, c);
            } catch (RuntimeException e) {
                // a contraption mid-assembly or mid-sync: judged again next tick
                LOGGER.debug("[FullStop] contraption {}: no sonic check this tick: {}", c.getId(), e.toString());
            }
        }
    }

    static void process(ServerLevel level, AbstractContraptionEntity c) {
        Contraption contraption = c.getContraption();
        if (contraption == null || contraption.getBlocks().isEmpty()) return;
        if (Ships.available() && Ships.managing(c) != null) return;
        SonicBooms.Key key = SonicBooms.Key.entity(c);
        AABB box = ContraptionTerrainImpacts.blockBounds(contraption);
        if (box == null) return;
        // the corners of its blocks' box first: no point of it is faster
        Vec3[] fastest = fastestOf(c, corners(box));
        if (fastest == null) return;
        double c2 = SonicBooms.SOUND_MPS * SonicBooms.SOUND_MPS;
        boolean live = SonicBooms.isLive(level, key);
        if (!live && fastest[1].lengthSqr() < 0.64 * c2) {
            SonicBooms.observe(level, key, fastest[0], fastest[1], p -> false);
            return;
        }
        List<Vec3> outer = extremes(contraption);
        Vec3[] real = fastestOf(c, outer);
        if (real != null) fastest = real;
        boolean followed = SonicBooms.observe(level, key, fastest[0], fastest[1], aboard(c));
        if (!followed) return;
        // a bearing's arm: the shock train of its supersonic tips (one shock per tip per turn)
        Vec3 spin = spin(c);
        int tips = 0;
        if (spin != null && real != null) {
            double threshold = SonicBooms.isLive(level, key) ? SonicBooms.SUBSONIC_SHARE * SonicBooms.SOUND_MPS : SonicBooms.SOUND_MPS;
            List<Vec3[]> points = new ArrayList<>(outer.size());
            for (Vec3 local : outer) {
                points.add(new Vec3[]{ContraptionImpacts.toGlobal(c, local, 1.0), ContraptionImpacts.velocityAt(c, local, 1.0)});
            }
            tips = SonicBooms.supersonicTips(points, hub(c), spin, threshold);
            // where its shock cones are drawn: its supersonic tips (MachCones on the client)
            SonicBooms.coneTips(level, key, SonicBooms.coneTipsOf(outer, points, threshold));
        }
        double rate = spin != null && tips > 0 ? spin.length() / (2.0 * Math.PI) * tips : 0.0;
        // the hub of a bearing stays put: its riders are swept by the tips' shocks like anyone near
        Vec3 hub = hub(c);
        if (spin != null && spin.lengthSqr() > 1.0E-12) {
            // the ring its supersonic blades sweep: in the plane of its fastest point, from where the blades pass the
            // barrier out to that point; each listener hears the shocks from where it sweeps nearest them
            Vec3 axis = spin.normalize();
            Vec3 d = fastest[0].subtract(hub);
            double along = d.dot(axis);
            double tipRadius = d.subtract(axis.scale(along)).length();
            double threshold = SonicBooms.isLive(level, key) ? SonicBooms.SUBSONIC_SHARE * SonicBooms.SOUND_MPS : SonicBooms.SOUND_MPS;
            double inner = threshold / spin.length();
            SonicBooms.train(level, key, hub, rate, fastest[1].length(), true, hub.add(axis.scale(along)), axis, inner, tipRadius);
        } else {
            SonicBooms.train(level, key, hub, rate, fastest[1].length(), true);
        }
        if (SERVER.debugContraptionLog.get()) debug(level, c, key, fastest[1].length(), tips, rate);
    }

    /** Who hears none of its booms: whoever rides it or stands on it (they move with it, inside its cone). */
    private static Predicate<ServerPlayer> aboard(AbstractContraptionEntity c) {
        return player -> player.getRootVehicle() == c.getRootVehicle() || c.collidingEntities.containsKey(player);
    }

    /**
     * A bearing's contraption's spin (rad/s, world, about its axis) over the last tick, or null for any contraption that
     * doesn't turn about a fixed axis (pistons, pulleys, gantries slide; carts and trains ride: they boom, no train).
     */
    @Nullable
    private static Vec3 spin(AbstractContraptionEntity c) {
        if (!(c instanceof ControlledContraptionEntity controlled)) return null;
        Direction.Axis axis = controlled.getRotationAxis();
        if (axis == null) return null;
        double degreesPerTick = Mth.wrapDegrees(controlled.getAngle(1.0f) - controlled.getAngle(0.0f));
        double radPerSecond = Math.toRadians(degreesPerTick) * 20.0;
        Vec3 unit = Vec3.atLowerCornerOf(Direction.get(Direction.AxisDirection.POSITIVE, axis).getNormal());
        return unit.scale(radPerSecond);
    }

    /** Where a contraption turns about (its anchor block's centre), or simply where it is. */
    private static Vec3 hub(AbstractContraptionEntity c) {
        return c.getAnchorVec().add(0.5, 0.5, 0.5);
    }

    /** Of {@code locals} (the contraption's frame), the one moving fastest: {@code {world point, velocity m/s}}. */
    @Nullable
    private static Vec3[] fastestOf(AbstractContraptionEntity c, List<Vec3> locals) {
        Vec3[] best = null;
        double bestSpeed = -1.0;
        for (Vec3 local : locals) {
            Vec3 velocity = ContraptionImpacts.velocityAt(c, local, 1.0);
            double speed = velocity.lengthSqr();
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = new Vec3[]{ContraptionImpacts.toGlobal(c, local, 1.0), velocity};
            }
        }
        return best;
    }

    private static List<Vec3> corners(AABB box) {
        List<Vec3> out = new ArrayList<>(8);
        for (int corner = 0; corner < 8; corner++) {
            out.add(new Vec3((corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY,
                    (corner & 4) == 0 ? box.minZ : box.maxZ));
        }
        return out;
    }

    /** Its outermost block corners, found again whenever its block count changes (a block knocked off). */
    private static List<Vec3> extremes(Contraption contraption) {
        int blocks = contraption.getBlocks().size();
        Extremes known = EXTREMES.get(contraption);
        if (known != null && known.blocks() == blocks) return known.points();
        SonicBooms.Outermost outermost = new SonicBooms.Outermost();
        for (Map.Entry<BlockPos, ?> entry : contraption.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            outermost.add(pos.getX(), pos.getY(), pos.getZ());
        }
        List<Vec3> points = outermost.points();
        EXTREMES.put(contraption, new Extremes(blocks, points));
        return points;
    }

    /** {@code debugContraptionLog}: a contraption's supersonic stretch beginning or ending, with its train. */
    private static void debug(ServerLevel level, AbstractContraptionEntity c, SonicBooms.Key key, double speed, int tips, double rate) {
        boolean live = SonicBooms.isLive(level, key);
        Boolean was = WAS_LIVE.put(c, live);
        if (was != null && was == live) return;
        if (live) {
            LOGGER.info("[FullStop] contraption {} went supersonic: fastest point {} m/s of {}, {} supersonic tip(s), shock train {} pulses/s",
                    c.getId(), fmt(speed), fmt(SonicBooms.SOUND_MPS), tips, fmt(rate));
        } else if (was != null) {
            LOGGER.info("[FullStop] contraption {} is subsonic again (fastest point {} m/s)", c.getId(), fmt(speed));
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
