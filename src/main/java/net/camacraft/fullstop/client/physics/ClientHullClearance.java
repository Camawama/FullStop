package net.camacraft.fullstop.client.physics;

import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the entities this client only draws against the moving hulls it draws, never inside them.
 *
 * <p>A mob (or another player) is moved by the server; this client eases it toward each place it is sent, over three
 * ticks. A ship is drawn from its own poses, which reach this client on their own. So a mob a slow hull pushes along -
 * against the hull's face on the server all the way - was drawn up to three ticks behind the face pushing it: at 4 m/s,
 * half a block inside the hull. Setting the mob back on this client once a tick didn't hold: the easing pulled it back
 * in, and the ship's pose had moved on again by the time the frame was drawn.</p>
 *
 * <p>So it is done where it is seen: in each frame, just before the entities are drawn, an entity whose drawn box has
 * sunk into a moving hull as that hull is drawn in this frame (its render pose) is drawn slid ahead of it, the way the
 * hull moves there, just far enough to clear it ({@link #clearance}), and put back right after the entities are drawn. Where the entity really is stays the server's
 * (and this client's own bookkeeping), and a pushed mob is drawn moving with the face exactly as smoothly as the ship
 * itself is drawn. Entities VS carries with the ship (on its deck), riders, and the local player (its own physics
 * answers hulls) are left alone.</p>
 */
public final class ClientHullClearance {

    /** No entity is drawn further than this from where it is (blocks, at the ship's size): past that it is no push. */
    private static final double MAX_SHIFT = 1.0;
    /** How far past a hull's bounds entities are looked for (blocks). */
    private static final double REACH = 0.5;

    private record Shift(Entity entity, double dx, double dy, double dz) {}

    private static final List<Shift> SHIFTED = new ArrayList<>();

    private ClientHullClearance() {
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        // the entities are drawn between these two stages (the terrain drawn between the sky and the entities never
        // reads an entity's place, and the block stages in between aren't posted when another renderer draws the terrain)
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            restore();
            shift(event.getPartialTick());
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            restore();
        }
    }

    /** Never left shifted past its frame, whatever happened in it. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) restore();
    }

    private static void shift(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) return;
        if (!FullStopConfig.SERVER_SPEC.isLoaded() || !ShipCompat.collisionsActive()) return;
        AABB around = minecraft.player.getBoundingBox().inflate(minecraft.options.getEffectiveRenderDistance() * 16.0);
        for (ShipRef ship : Ships.intersecting(level, around)) {
            if (!moving(ship)) continue;
            AABB hull = ship.worldAabb();
            org.joml.Matrix4d drawn = ship.worldToShip(ShipRef.Pose.RENDER);
            if (hull == null || drawn == null) continue;
            double scale = ship.scale(ShipRef.Pose.RENDER);
            List<Entity> near = level.getEntities((Entity) null, hull.inflate(REACH + 1.0),
                    e -> e instanceof LivingEntity && e != minecraft.player && !e.isPassenger() && !e.isVehicle()
                            && !e.noPhysics && !e.isSpectator());
            for (Entity entity : near) {
                if (ShipCompat.carriedBy(entity, ship)) continue; // on its deck: VS moves it with the ship
                // the entity's box where this frame draws it, against the hull where this frame draws it
                double x = Mth.lerp(partialTick, entity.xOld, entity.getX());
                double y = Mth.lerp(partialTick, entity.yOld, entity.getY());
                double z = Mth.lerp(partialTick, entity.zOld, entity.getZ());
                AABB box = entity.getBoundingBox().move(x - entity.getX(), y - entity.getY(), z - entity.getZ());
                if (!ship.touchesBlock(box, drawn)) continue;
                Vec3 out = clearance(ship, drawn, box, scale);
                if (out == null) continue;
                move(entity, out.x, out.y, out.z);
                SHIFTED.add(new Shift(entity, out.x, out.y, out.z));
            }
        }
    }

    /**
     * How far to draw a box set back out of a hull it has sunk into: along the way the hull moves where the box is (from
     * last tick's pose to this one's: the way it pushes), the least distance that clears the hull as drawn; null when
     * the hull isn't moving there or nothing within {@link #MAX_SHIFT} clears it. Not out through the nearest face of
     * one of its blocks: a hull is many blocks, and the face nearest a box that spans two of them is often the one
     * between them (it slid the mob along a rotor arm, still inside it).
     */
    private static Vec3 clearance(ShipRef ship, org.joml.Matrix4d drawn, AABB box, double scale) {
        Vec3 centre = box.getCenter();
        Vec3 before = ship.currentToPrevTick(centre);
        if (before == null) return null;
        Vec3 dir = centre.subtract(before);
        double moved = dir.length();
        if (!(moved > 1.0e-6)) return null;
        dir = dir.scale(1.0 / moved);
        double max = MAX_SHIFT * Math.max(scale, 1.0e-3);
        double step = max / 20.0;
        double clear = -1.0;
        for (int i = 1; i <= 20; i++) {
            if (!ship.touchesBlock(box.move(dir.scale(step * i)), drawn)) {
                clear = step * i;
                break;
            }
        }
        if (clear < 0.0) return null;
        double lo = clear - step, hi = clear;
        for (int i = 0; i < 10; i++) {
            double mid = 0.5 * (lo + hi);
            if (ship.touchesBlock(box.move(dir.scale(mid)), drawn)) lo = mid;
            else hi = mid;
        }
        return dir.scale(hi);
    }

    private static void restore() {
        for (Shift shift : SHIFTED) move(shift.entity(), -shift.dx(), -shift.dy(), -shift.dz());
        SHIFTED.clear();
    }

    /** Moves both ends of the entity's interpolation (and so where it is drawn) by the same amount. */
    private static void move(Entity entity, double dx, double dy, double dz) {
        entity.setPosRaw(entity.getX() + dx, entity.getY() + dy, entity.getZ() + dz);
        entity.xOld += dx;
        entity.yOld += dy;
        entity.zOld += dz;
        entity.xo += dx;
        entity.yo += dy;
        entity.zo += dz;
    }

    /** Whether the ship moved or turned from last tick's pose to this one's (the velocity VS syncs to a client can read nil). */
    private static boolean moving(ShipRef ship) {
        Vec3 now = ship.position();
        Vec3 anchor = now == null ? null : ship.toShip(now);
        Vec3 before = anchor == null ? null : ship.toWorld(anchor, ShipRef.Pose.PREV_TICK);
        org.joml.Quaterniond turnNow = ship.rotation(ShipRef.Pose.TICK);
        org.joml.Quaterniond turnBefore = ship.rotation(ShipRef.Pose.PREV_TICK);
        if (before == null || turnNow == null || turnBefore == null) return false;
        return now.distanceToSqr(before) > 1.0e-8 || Math.abs(Math.abs(turnNow.dot(turnBefore)) - 1.0) > 1.0e-9;
    }
}
