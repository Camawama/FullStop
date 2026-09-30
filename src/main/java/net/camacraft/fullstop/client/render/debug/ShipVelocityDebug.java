package net.camacraft.fullstop.client.render.debug;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.common.physics.ShipSweptContact;
import net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * FullStop's velocity debug view (F3+V) for Valkyrien Skies ships, in the same style as its
 * entity rays: from the corners, face centres and centre of mass of every moving ship's hull, the
 * path that point of the hull takes over the coming tick - along the ship's travel and round its
 * turn, so a spinning ship's rays curve along the circles its hull sweeps instead of shooting off
 * along their tangents (a fast rotor's tips cover many blocks a tick, and straight rays reached far
 * out into air the hull never goes near). Green when clear, red where it is about to strike terrain
 * or another ship at collision speed (2 m/s or more into the face), where the ray stops, yellow
 * where it only touches; fainter the slower it moves. The centre of mass ray is drawn through the
 * hull, so it shows from outside.
 *
 * <p>A ship turning about a fixed point - a rotor on a Clockwork bearing, pivoting at one end - has its centre of mass
 * going round that point, not on in a straight line: its rays are circles about the point. Taking the centre of mass
 * as travelling straight on while the hull turned about it made the rays of a fast rotor's tips spiral outward, far
 * from where the hull goes. The point is the ship's instantaneous rotation axis ({@code com + w x v / |w|^2}), taken
 * as a pivot when it stays put from one tick to the next; a freely turning ship's axis travels with it, and its rays
 * follow its centre of mass straight on as before.</p>
 *
 * <p>The client knows each ship's velocity and spin from VS's own sync; should they read zero
 * while the ship visibly moves (an older sync), they are taken from its last two client
 * transforms instead. A ship whose pose stops changing (made static, frozen) draws nothing, even
 * though the client keeps its last synced velocity. Positions use the ship's interpolated render pose, so the rays stay on the
 * drawn hull. A ship a size mod shrank (VS Genesis's space: 1/16) has its ray stubs and line widths shrunk with it.
 */
public final class ShipVelocityDebug {

    /** A ship slower than this everywhere (m/s) draws nothing: only moving ships are shown. */
    private static final double MIN_SPEED = 0.1;
    /** Only ships within this distance of the camera (blocks). */
    private static final double RANGE = 160.0;
    private static final double MIN_RAY_LENGTH = 0.15;
    private static final double TICK_SECONDS = 0.05;
    /** Longest turn (radians) one straight piece of an arc covers, and at most this many pieces. */
    private static final double MAX_SEGMENT_TURN = Math.toRadians(6.0);
    private static final int MAX_SEGMENTS = 24;
    private static final double SPEED_FOR_MAX_OPACITY = 20.0;
    private static final float MIN_SPEED_ALPHA = 0.2f;
    private static final int LINE_LIFE_TICKS = 5;
    /**
     * A ship whose pose hasn't changed for this many client ticks draws nothing, whatever velocity
     * the client still has for it: a ship made static (or frozen) mid-flight keeps its last synced
     * velocity, and its rays used to stay up, frozen.
     */
    private static final int STILL_TICKS = 4;
    /** Ships not seen for this long (client ticks) are forgotten. */
    private static final int FORGET_TICKS = 200;

    /** Each ship's last pose seen, and when it last changed (client game time). */
    private static final Long2ObjectOpenHashMap<Seen> SEEN = new Long2ObjectOpenHashMap<>();
    @Nullable
    private static ClientLevel seenIn;

    private record Seen(Vec3 position, Quaterniond rotation, long movedAt, long seenAt) {}

    /** Each ship's instantaneous rotation axis point this tick, and last tick's (for telling a pivot from free turning). */
    private static final Long2ObjectOpenHashMap<Axis> AXES = new Long2ObjectOpenHashMap<>();

    private record Axis(long tick, @Nullable Vec3 point, @Nullable Vec3 before) {}

    private ShipVelocityDebug() {
    }

    /** Queues this frame's rays for every moving ship near the camera. */
    public static void update(ClientLevel level, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (!Ships.available()) return;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        Entity viewer = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        if (viewer == null) return;
        long now = level.getGameTime();
        if (seenIn != level) {
            SEEN.clear();
            AXES.clear();
            seenIn = level;
        }
        SEEN.values().removeIf(seen -> now - seen.seenAt() > FORGET_TICKS);
        AXES.values().removeIf(axis -> now - axis.tick() > FORGET_TICKS);
        for (ShipRef ship : Ships.intersecting(level, new AABB(camera, camera).inflate(RANGE))) {
            if (moving(ship, now)) draw(level, ship, viewer);
        }
    }

    /**
     * Whether the ship's pose changed within the last {@link #STILL_TICKS} client ticks. A ship
     * first seen standing still counts as still until it moves.
     */
    private static boolean moving(ShipRef ship, long now) {
        Vec3 position = ship.position();
        Quaterniond rotation = ship.rotation(ShipRef.Pose.TICK);
        if (position == null || rotation == null) return false;
        Seen seen = SEEN.get(ship.id());
        long movedAt = Long.MIN_VALUE / 2;
        if (seen != null) {
            boolean changed = position.distanceToSqr(seen.position()) > 1.0e-10 || !rotation.equals(seen.rotation(), 1.0e-9);
            movedAt = changed ? now : seen.movedAt();
        }
        SEEN.put(ship.id(), new Seen(position, rotation, movedAt, now));
        return now - movedAt <= STILL_TICKS;
    }

    private static void draw(ClientLevel level, ShipRef ship, Entity viewer) {
        BoundingBox blocks = ship.shipyardBlocks();
        Vec3 centre = ship.position();
        if (blocks == null || centre == null) return;
        Vec3[] motion = motion(ship, centre);
        if (motion == null) return;
        Vec3 velocity = motion[0];
        Vec3 spin = motion[1];
        // a ship a size mod shrank: its fixed lengths (the stubs of slow points, the line width) shrink with it
        double size = ship.scale();
        if (!(size > 0.0) || !Double.isFinite(size)) size = 1.0;
        float thickness = (float) (RaycastLineRenderer.DEFAULT_THICKNESS * size);

        // Where the points are drawn (render pose) and where they are now (tick pose, for v + w x r).
        Vec3 comShip = ship.toShip(centre);
        if (comShip == null) return;
        List<Vec3> points = starts(blocks, comShip);
        double halfDiagonal = 0.5 * Math.sqrt(blocks.getXSpan() * (double) blocks.getXSpan()
                + blocks.getYSpan() * (double) blocks.getYSpan() + blocks.getZSpan() * (double) blocks.getZSpan());
        if (velocity.length() + spin.length() * halfDiagonal * size < MIN_SPEED * size) return;

        // the ship's motion over the coming tick, to follow each point along the arc its turn makes of it: round its
        // pivot if it turns about a fixed point, else round its centre of mass going straight on
        ShipSweptContact.Relative ahead = new ShipSweptContact.Relative(centre, velocity, spin, Vec3.ZERO);
        Vec3 pivot = pivot(ship.id(), centre, velocity, spin, level.getGameTime());
        Vec3 about = pivot != null ? pivot : centre;
        Vec3 carried = pivot != null ? spin.scale(spin.dot(velocity) / spin.lengthSqr()) : velocity;
        int segments = Mth.clamp((int) Math.ceil(spin.length() * TICK_SECONDS / MAX_SEGMENT_TURN), 1, MAX_SEGMENTS);
        for (int i = 0; i < points.size(); i++) {
            Vec3 local = points.get(i);
            Vec3 now = ship.toWorld(local);
            Vec3 drawn = ship.toWorld(local, ShipRef.Pose.RENDER);
            if (now == null || drawn == null) continue;
            Vec3 pointVelocity = velocity.add(spin.cross(now.subtract(centre)));
            double speed = pointVelocity.length();
            if (speed < 1.0e-4) continue;
            boolean centreRay = i == points.size() - 1;
            float speedAlpha = (float) Math.min(Math.max(speed / SPEED_FOR_MAX_OPACITY, MIN_SPEED_ALPHA), 1.0);
            String id = "ship_" + ship.id() + "_ray_" + i + "_";
            if (speed * TICK_SECONDS < MIN_RAY_LENGTH * size) {
                // too slow to show an arc: a short stub along its motion, as before
                Vec3 end = drawn.add(pointVelocity.scale((MIN_RAY_LENGTH + 0.01) * size / speed));
                segment(level, ship, viewer, id + 0, drawn, end, pointVelocity, speedAlpha, centreRay, thickness);
                continue;
            }
            // one tick of the point's travel, along the arc: its path in the world as the ship moves on and turns
            Vec3 from = drawn;
            for (int k = 1; k <= segments; k++) {
                double t = TICK_SECONDS * k / segments;
                Vec3 at = about.add(carried.scale(t)).add(ahead.turn(now.subtract(about), t));
                Vec3 to = drawn.add(at.subtract(now));
                Vec3 along = carried.add(spin.cross(at.subtract(about.add(carried.scale(t)))));
                // the point meets something there: the arc stops at it
                if (segment(level, ship, viewer, id + k, from, to, along, speedAlpha, centreRay, thickness)) break;
                from = to;
            }
        }
    }

    /**
     * The fixed point the ship turns about, or null when it turns freely (or hardly at all): its instantaneous rotation
     * axis point ({@code com + w x v / |w|^2}, the point of the axis nearest the centre of mass), when that stayed put
     * since last tick - by less than a quarter of how far the centre of mass goes in a tick. A rotor on a bearing turns
     * about the bearing, its axis still; a ship flying on while it spins carries its axis along with it.
     */
    @Nullable
    private static Vec3 pivot(long shipId, Vec3 centre, Vec3 velocity, Vec3 spin, long tick) {
        double spinSqr = spin.lengthSqr();
        Vec3 point = spinSqr < 1.0e-6 ? null : centre.add(spin.cross(velocity).scale(1.0 / spinSqr));
        Axis axis = AXES.get(shipId);
        if (axis == null || axis.tick() != tick) {
            axis = new Axis(tick, point, axis != null && axis.tick() == tick - 1 ? axis.point() : null);
            AXES.put(shipId, axis);
        }
        if (point == null || axis.point() == null || axis.before() == null) return null;
        double travel = velocity.length() * TICK_SECONDS;
        return axis.before().distanceTo(axis.point()) < Math.max(0.02, 0.25 * travel) ? axis.point() : null;
    }

    /**
     * One piece of a point's arc: green when clear, red where it runs into terrain or another ship at collision
     * speed, yellow where it only touches. Returns whether it ran into something.
     */
    private static boolean segment(ClientLevel level, ShipRef ship, Entity viewer, String id, Vec3 from, Vec3 to, Vec3 velocity,
                                   float speedAlpha, boolean centreRay, float thickness) {
        BlockHitResult hit = Ships.clip(level, new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, viewer), ship.id());
        float r = 0.0f, g = 1.0f, b = 0.0f, a = 0.5f;
        boolean blocked = hit.getType() == HitResult.Type.BLOCK;
        if (blocked) {
            Vec3 face = Ships.hitNormal(level, hit);
            boolean colliding = face != null && -velocity.dot(face) >= CommonCollisionDetector.MIN_APPROACH_SPEED_MPS;
            r = 1.0f;
            g = colliding ? 0.0f : 0.8f;
            a = colliding ? 1.0f : 0.7f;
            to = hit.getLocation();
        }
        RaycastLineRenderer.queueLine(id, from, to, r, g, b, a * speedAlpha, LINE_LIFE_TICKS, centreRay, thickness);
        return blocked;
    }

    /**
     * The ship's velocity (m/s) and spin (rad/s): VS's synced values, or, when those read zero
     * while the ship moved since last tick, what its last two client transforms show.
     */
    @Nullable
    private static Vec3[] motion(ShipRef ship, Vec3 centre) {
        Vec3 velocity = ship.velocity();
        Vec3 spin = ship.angularVelocity();
        if (velocity != null && spin != null && (velocity.lengthSqr() > 1.0e-8 || spin.lengthSqr() > 1.0e-8)) {
            return new Vec3[]{velocity, spin};
        }
        Vec3 comShip = ship.toShip(centre);
        Vec3 before = comShip == null ? null : ship.toWorld(comShip, ShipRef.Pose.PREV_TICK);
        Quaterniond now = ship.rotation(ShipRef.Pose.TICK);
        Quaterniond then = ship.rotation(ShipRef.Pose.PREV_TICK);
        if (before == null || now == null || then == null) return null;
        Vec3 derived = centre.subtract(before).scale(20.0);
        // the turn from last tick's rotation to this tick's, as a rotation vector per second
        Quaterniond turn = new Quaterniond(now).mul(new Quaterniond(then).conjugate()).normalize();
        if (turn.w < 0.0) turn.set(-turn.x, -turn.y, -turn.z, -turn.w);
        double angle = 2.0 * Math.acos(Math.min(1.0, turn.w));
        double sin = Math.sqrt(Math.max(0.0, 1.0 - turn.w * turn.w));
        Vector3d axis = sin > 1.0e-9 ? new Vector3d(turn.x / sin, turn.y / sin, turn.z / sin) : new Vector3d();
        return new Vec3[]{derived, new Vec3(axis.x, axis.y, axis.z).scale(angle * 20.0)};
    }

    /**
     * Ray starts in ship space: the hull's 8 corners, its 6 face centres and, last, the centre of
     * mass (the entity view's CORNERS_AND_CENTERS layout, on the ship's block bounds).
     */
    private static List<Vec3> starts(BoundingBox blocks, Vec3 comShip) {
        double minX = blocks.minX(), minY = blocks.minY(), minZ = blocks.minZ();
        double maxX = blocks.maxX() + 1.0, maxY = blocks.maxY() + 1.0, maxZ = blocks.maxZ() + 1.0;
        double cx = (minX + maxX) * 0.5, cy = (minY + maxY) * 0.5, cz = (minZ + maxZ) * 0.5;
        List<Vec3> points = new ArrayList<>(15);
        for (int corner = 0; corner < 8; corner++) {
            points.add(new Vec3((corner & 1) == 0 ? minX : maxX, (corner & 2) == 0 ? minY : maxY, (corner & 4) == 0 ? minZ : maxZ));
        }
        points.add(new Vec3(minX, cy, cz));
        points.add(new Vec3(maxX, cy, cz));
        points.add(new Vec3(cx, minY, cz));
        points.add(new Vec3(cx, maxY, cz));
        points.add(new Vec3(cx, cy, minZ));
        points.add(new Vec3(cx, cy, maxZ));
        points.add(comShip);
        return points;
    }
}
