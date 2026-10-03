package net.camacraft.fullstop.common.compat.create.contraption;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.client.render.debug.RaycastLineRenderer;
import net.camacraft.fullstop.server.physics.ship.ShipContactPhysics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * FullStop's velocity debug view (F3+V) for moving Create contraptions, in the style of the ship view
 * ({@code ShipVelocityDebug}): from the corners, face centres and centre of the box every moving contraption's
 * blocks fill ({@link ContraptionTerrainImpacts#blockBounds}, not Create's {@code bounds}, which a bearing widens to
 * the whole circle its blocks sweep), the path that point takes over the coming tick, following Create's own pose (a bearing's
 * arm curves round its axis, a gantry, piston, pulley or cart goes straight on, a train or minecart
 * contraption turns with its cart). Green where the path is clear, red where it runs into a body fast enough
 * to strike it ({@link ContraptionImpacts}: at least {@code pushMinSpeed} into it and over
 * {@code gentlePushSpeed}), yellow where it only pushes; the path stops at the body. Fainter the slower the
 * point moves. Bodies riding or standing on the contraption are never in its way, as in the hit test.
 * A path also turns red, and stops, where it runs into the terrain or a Valkyrien Skies ship's block at
 * {@code contraptionTerrainMinSpeed} or faster ({@link ContraptionTerrainImpacts}: the contraption breaks it, or
 * is broken or jammed by it); slower, it passes through blocks as in Create and stays green.
 *
 * <p>Client only, and only with Create (reached through {@code CreateDebugHooks}). The coming tick is
 * Create's interpolation carried on past the current tick ({@code applyRotation} and the anchor at a
 * partial tick above 1), which is how the contraption moves on while its speed holds. Contraptions on a
 * Valkyrien Skies ship draw nothing: FullStop leaves their hits to the ship.
 */
public final class ContraptionDebugView {

    /** Only contraptions within this distance of the camera (blocks). */
    private static final double RANGE = 96.0;
    /** At most this many contraptions, nearest first. */
    private static final int MAX_CONTRAPTIONS = 24;
    /** A contraption slower than this everywhere (m/s) draws nothing. */
    private static final double MIN_SPEED = 0.1;
    private static final double MIN_RAY_LENGTH = 0.15;
    /** Longest turn one straight piece of a path covers, and at most this many pieces. */
    private static final double MAX_SEGMENT_TURN = Math.toRadians(6.0);
    private static final int MAX_SEGMENTS = 16;
    private static final double SPEED_FOR_MAX_OPACITY = 20.0;
    private static final float MIN_SPEED_ALPHA = 0.2f;
    private static final int LINE_LIFE_TICKS = 5;
    /** Line colours: the ship view's green / yellow / red. */
    private static final float[] CLEAR = {0.0f, 1.0f, 0.0f, 0.5f};
    private static final float[] PUSH = {1.0f, 0.8f, 0.0f, 0.7f};
    private static final float[] STRIKE = {1.0f, 0.0f, 0.0f, 1.0f};

    private ContraptionDebugView() {
    }

    /** Queues this frame's paths for every moving contraption near the camera. */
    public static void update(ClientLevel level, float partialTick, Vec3 camera) {
        if (!ContraptionImpacts.enabled() && !ContraptionTerrainImpacts.enabled()) return;
        List<AbstractContraptionEntity> near = new ArrayList<>();
        double rangeSqr = RANGE * RANGE;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof AbstractContraptionEntity c && c.isAlive() && c.distanceToSqr(camera) <= rangeSqr) near.add(c);
        }
        if (near.isEmpty()) return;
        near.sort(Comparator.comparingDouble(c -> c.distanceToSqr(camera)));
        for (int i = 0; i < near.size() && i < MAX_CONTRAPTIONS; i++) {
            AbstractContraptionEntity c = near.get(i);
            try {
                draw(level, c, partialTick);
            } catch (RuntimeException e) {
                // a contraption mid-sync (no blocks yet) or a modded one with odd poses: skip it this frame
            }
        }
    }

    private static void draw(ClientLevel level, AbstractContraptionEntity c, float partialTick) {
        Contraption contraption = c.getContraption();
        if (contraption == null || contraption.getBlocks().isEmpty()) return;
        if (Ships.available() && Ships.managing(c) != null) return;
        // the box its blocks fill: Create's own bounds are widened round a bearing's (a cart's, a train's) axis to the
        // whole circle its blocks sweep, and paths from their corners started far out from the contraption
        AABB bounds = ContraptionTerrainImpacts.blockBounds(contraption);
        if (bounds == null) return;
        List<Vec3> points = starts(bounds);
        double pt = Mth.clamp(partialTick, 0.0f, 1.0f);

        // how far its fastest point goes in the coming tick: nothing drawn for a contraption standing still
        double fastest = 0.0;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 p = points.get(corner);
            fastest = Math.max(fastest, ContraptionImpacts.toGlobal(c, p, pt + 1.0).distanceTo(ContraptionImpacts.toGlobal(c, p, pt)));
        }
        if (fastest * 20.0 < MIN_SPEED) return;

        // the bodies it may meet this coming tick: near its swept bounds, not riding it or standing on it
        AABB swept = sweptBox(c, bounds, pt).inflate(0.5);
        List<Entity> bodies = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, swept, e -> true)) {
            Entity root = entity.getRootVehicle();
            if (root == c || root instanceof AbstractContraptionEntity || bodies.contains(root)) continue;
            if (root.isSpectator() || !ShipContactPhysics.isBody(root)) continue;
            if (root == c.getRootVehicle() || c.collidingEntities.containsKey(root)) continue;
            bodies.add(root);
        }

        // the ships its paths may run into (contraptionShipImpacts)
        List<ShipRef> ships = ContraptionTerrainImpacts.enabled() && SERVER.contraptionShipImpacts.get() && Ships.available()
                ? Ships.intersecting(level, swept) : List.of();
        Obstacles obstacles = new Obstacles(level, bodies, ships);

        // where one of its blocks is about to strike the terrain or a ship: a red path from that block's centre (the
        // corners' paths can pass beside the block they would hit; the hit test follows the blocks themselves)
        if (ContraptionTerrainImpacts.enabled()) blockStrikes(c, contraption, pt, obstacles);

        for (int i = 0; i < points.size(); i++) {
            Vec3 local = points.get(i);
            Vec3 drawn = ContraptionImpacts.toGlobal(c, local, pt);
            Vec3 velocity = pointVelocity(c, local, pt);
            double speed = velocity.length();
            if (speed < 1.0e-4) continue;
            boolean centreRay = i == points.size() - 1;
            float speedAlpha = (float) Math.min(Math.max(speed / SPEED_FOR_MAX_OPACITY, MIN_SPEED_ALPHA), 1.0);
            String id = "contraption_" + c.getId() + "_ray_" + i + "_";
            if (speed * 0.05 < MIN_RAY_LENGTH) {
                // too slow to show a path: a short stub along its motion
                Vec3 end = drawn.add(velocity.scale((MIN_RAY_LENGTH + 0.01) / speed));
                segment(id + 0, drawn, end, velocity, obstacles, speedAlpha, centreRay);
                continue;
            }
            // the point's coming tick in pieces short enough to follow a turn
            Vec3 end = ContraptionImpacts.toGlobal(c, local, pt + 1.0);
            Vec3 endVelocity = pointVelocity(c, local, pt + 1.0);
            double turn = angle(velocity, endVelocity);
            int segments = Mth.clamp((int) Math.ceil(turn / MAX_SEGMENT_TURN), 1, MAX_SEGMENTS);
            if (segments == 1 && end.distanceTo(drawn) > 4.0) segments = Math.min(MAX_SEGMENTS, (int) Math.ceil(end.distanceTo(drawn) / 4.0));
            Vec3 from = drawn;
            for (int k = 1; k <= segments; k++) {
                double f = pt + (double) k / segments;
                Vec3 to = ContraptionImpacts.toGlobal(c, local, f);
                Vec3 along = pointVelocity(c, local, f);
                if (segment(id + k, from, to, along, obstacles, speedAlpha, centreRay)) break;
                from = to;
            }
        }
    }

    /**
     * One piece of a point's path: green when clear, red where it meets a body fast enough to strike it, yellow
     * where it only pushes. Returns whether it met a body (the path stops there).
     */
    private static boolean segment(String id, Vec3 from, Vec3 to, Vec3 velocity, Obstacles obstacles, float speedAlpha,
                                   boolean centreRay) {
        Entity met = null;
        Vec3 at = null;
        double best = Double.POSITIVE_INFINITY;
        // the terrain or a ship's block in its way, at a speed that hits it (slower, it passes through as in Create)
        Vec3 block = velocity.length() >= SERVER.contraptionTerrainMinSpeed.get() ? obstacles.firstBlock(from, to) : null;
        for (Entity body : obstacles.bodies()) {
            AABB box = body.getBoundingBox();
            Optional<Vec3> hit = box.contains(from) ? Optional.of(from) : box.clip(from, to);
            if (hit.isEmpty()) continue;
            double d = hit.get().distanceToSqr(from);
            if (d < best) {
                best = d;
                met = body;
                at = hit.get();
            }
        }
        float[] colour = CLEAR;
        if (block != null && (met == null || block.distanceToSqr(from) < best)) {
            RaycastLineRenderer.queueLine(id, from, block, STRIKE[0], STRIKE[1], STRIKE[2], STRIKE[3] * speedAlpha, LINE_LIFE_TICKS,
                    centreRay, RaycastLineRenderer.DEFAULT_THICKNESS);
            return true;
        }
        if (met != null) {
            Vec3 own = met.position().subtract(met.xo, met.yo, met.zo).scale(20.0);
            Vec3 dir = to.subtract(from);
            double len = dir.length();
            Vec3 into = len > 1.0e-6 ? dir.scale(1.0 / len) : velocity.normalize();
            double hullIn = velocity.dot(into);
            double approach = velocity.subtract(own).dot(into);
            boolean strikes = hullIn >= SERVER.pushMinSpeed.get() && approach > 0.0 && !ShipContactPhysics.gentle(met, approach);
            colour = strikes ? STRIKE : PUSH;
            to = at;
        }
        RaycastLineRenderer.queueLine(id, from, to, colour[0], colour[1], colour[2], colour[3] * speedAlpha, LINE_LIFE_TICKS,
                centreRay, RaycastLineRenderer.DEFAULT_THICKNESS);
        return met != null;
    }

    /** What a contraption's paths may run into this frame: bodies near it, the terrain, ships near it. */
    private record Obstacles(ClientLevel level, List<Entity> bodies, List<ShipRef> ships) {
        /** Where the piece {@code from -> to} first enters a solid world cell or a ship's block (sampled under half a block), or null. */
        @Nullable
        Vec3 firstBlock(Vec3 from, Vec3 to) {
            boolean terrain = SERVER.contraptionTerrainImpacts.get();
            if (!terrain && ships.isEmpty()) return null;
            double length = from.distanceTo(to);
            int steps = Mth.clamp((int) Math.ceil(length / 0.45), 1, 64);
            BlockPos startCell = BlockPos.containing(from);
            for (int s = 1; s <= steps; s++) {
                Vec3 p = from.add(to.subtract(from).scale((double) s / steps));
                BlockPos cell = BlockPos.containing(p);
                if (terrain && !cell.equals(startCell) && level.isLoaded(cell)
                        && ContraptionTerrainImpacts.solid(level, cell, level.getBlockState(cell))) {
                    return p;
                }
                for (ShipRef ship : ships) {
                    Vec3 inShip = ship.toShip(p);
                    if (inShip == null) continue;
                    BlockPos shipCell = BlockPos.containing(inShip);
                    if (level.isLoaded(shipCell) && ContraptionTerrainImpacts.solid(level, shipCell, level.getBlockState(shipCell))) return p;
                }
            }
            return null;
        }
    }

    /** At most this many of a contraption's outer blocks are followed for terrain strikes, fastest first. */
    private static final int MAX_STRIKE_BLOCKS = 48;

    /** Red paths from the centres of its outer blocks that are about to run into the terrain or a ship at hit speed. */
    private static void blockStrikes(AbstractContraptionEntity c, Contraption contraption, double pt, Obstacles obstacles) {
        double min = SERVER.contraptionTerrainMinSpeed.get();
        List<BlockPos> outer = ContraptionTerrainImpacts.outerBlocks(contraption);
        List<Vec3> fast = new ArrayList<>();
        for (BlockPos local : outer) {
            Vec3 centre = Vec3.atCenterOf(local);
            if (pointVelocity(c, centre, pt).length() >= min) fast.add(centre);
        }
        fast.sort(Comparator.comparingDouble(p -> -pointVelocity(c, p, pt).length()));
        for (int i = 0; i < fast.size() && i < MAX_STRIKE_BLOCKS; i++) {
            Vec3 centre = fast.get(i);
            Vec3 from = ContraptionImpacts.toGlobal(c, centre, pt);
            int segments = 4;
            for (int k = 1; k <= segments; k++) {
                Vec3 to = ContraptionImpacts.toGlobal(c, centre, pt + (double) k / segments);
                Vec3 hit = obstacles.firstBlock(from, to);
                if (hit != null) {
                    String id = "contraption_" + c.getId() + "_block_" + i;
                    RaycastLineRenderer.queueLine(id, ContraptionImpacts.toGlobal(c, centre, pt), hit, STRIKE[0], STRIKE[1], STRIKE[2],
                            STRIKE[3], LINE_LIFE_TICKS, true, RaycastLineRenderer.DEFAULT_THICKNESS);
                    break;
                }
                from = to;
            }
        }
    }

    /** A point's velocity (m/s, world) at fraction {@code f} of the tick, carried on past the current tick. */
    private static Vec3 pointVelocity(AbstractContraptionEntity c, Vec3 local, double f) {
        double eps = 0.02;
        return ContraptionImpacts.toGlobal(c, local, f + eps).subtract(ContraptionImpacts.toGlobal(c, local, f - eps))
                .scale(20.0 / (2.0 * eps));
    }

    private static double angle(Vec3 a, Vec3 b) {
        double la = a.length(), lb = b.length();
        if (la < 1.0e-9 || lb < 1.0e-9) return 0.0;
        return Math.acos(Mth.clamp(a.dot(b) / (la * lb), -1.0, 1.0));
    }

    /** The world box its bounds' corners sweep over the coming tick (sampled at quarters). */
    private static AABB sweptBox(AbstractContraptionEntity c, AABB bounds, double pt) {
        AABB out = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 p = corner(bounds, corner);
            for (int q = 0; q <= 4; q++) {
                Vec3 at = ContraptionImpacts.toGlobal(c, p, pt + q * 0.25);
                out = out == null ? new AABB(at, at) : out.minmax(new AABB(at, at));
            }
        }
        return out;
    }

    /** Path starts in the contraption's frame: its bounds' 8 corners, 6 face centres and, last, its centre. */
    private static List<Vec3> starts(AABB b) {
        List<Vec3> points = new ArrayList<>(15);
        for (int corner = 0; corner < 8; corner++) points.add(corner(b, corner));
        Vec3 c = b.getCenter();
        points.add(new Vec3(b.minX, c.y, c.z));
        points.add(new Vec3(b.maxX, c.y, c.z));
        points.add(new Vec3(c.x, b.minY, c.z));
        points.add(new Vec3(c.x, b.maxY, c.z));
        points.add(new Vec3(c.x, c.y, b.minZ));
        points.add(new Vec3(c.x, c.y, b.maxZ));
        points.add(c);
        return points;
    }

    private static Vec3 corner(AABB box, int corner) {
        return new Vec3((corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY,
                (corner & 4) == 0 ? box.minZ : box.maxZ);
    }
}
