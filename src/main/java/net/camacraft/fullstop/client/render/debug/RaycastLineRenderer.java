package net.camacraft.fullstop.client.render.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.math.RaycastUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.*;

/**
 * Debug line renderer for raycasts — persistent lines with per-ID smoothing and fade.
 * Must be called from RenderLevelStageEvent on the client.
 *
 * This version renders rays as camera-facing quads (triangles) instead of GL lines,
 * so they remain visible when the camera is aligned with the ray direction.
 * Lifetime is also decremented on game ticks (not frames) to avoid "blinding fast" flashing at high FPS.
 */
public final class RaycastLineRenderer {

    // --- CONFIGURATION VARIABLES ---
    // Colors (RGB 0.0 - 1.0)
    private static final float HIT_R = 1.0f;
    private static final float HIT_G = 0.0f;
    private static final float HIT_B = 0.0f;
    private static final float HIT_A = 1.0f; // Alpha for hit

    private static final float MISS_R = 0.0f;
    private static final float MISS_G = 1.0f;
    private static final float MISS_B = 0.0f;
    private static final float MISS_A = 0.5f; // Alpha for miss (more transparent)

    // Touching a block without a collision-grade approach speed (e.g. standing
    // flush against a wall — the rays' 0.15 minimum reach always grazes it).
    private static final float TOUCH_R = 1.0f;
    private static final float TOUCH_G = 0.8f;
    private static final float TOUCH_B = 0.0f;
    private static final float TOUCH_A = 0.7f;

    // Valkyrien Skies ship blocks, told apart: a collision-grade hit on a ship the entity really
    // meets this tick (MAGENTA), touching one (LAVENDER), and a ship block that is only in the
    // ray's way - the hull moves on before the entity gets there, so it is no impact (BLUE).
    private static final float SHIP_HIT_R = 1.0f;
    private static final float SHIP_HIT_G = 0.0f;
    private static final float SHIP_HIT_B = 1.0f;
    private static final float SHIP_TOUCH_R = 0.75f;
    private static final float SHIP_TOUCH_G = 0.55f;
    private static final float SHIP_TOUCH_B = 1.0f;
    private static final float SHIP_AHEAD_R = 0.2f;
    private static final float SHIP_AHEAD_G = 0.45f;
    private static final float SHIP_AHEAD_B = 1.0f;
    private static final float SHIP_AHEAD_A = 0.6f;

    // Line Properties
    private static final int LINE_LIFE_TICKS = 5; // How long a line persists without updates (now truly ticks)

    // Ray thickness in world units (used for quad rendering) for a full-size body. Tweak to taste. A body or ship a
    // size mod shrank draws its rays thinner in proportion (see queueLine's thickness).
    public static final float DEFAULT_THICKNESS = 0.03f;
    private static final float RAY_THICKNESS = DEFAULT_THICKNESS;

    // Velocity Opacity Scaling
    private static final double SPEED_FOR_MAX_OPACITY = 20.0; // Speed in m/s for full opacity
    private static final float MIN_SPEED_ALPHA = 0.2f; // Minimum alpha multiplier when stationary

    private static final Map<String, Line> ACTIVE_LINES = new HashMap<>();
    private static final List<QueuedLine> PENDING_LINES = new ArrayList<>();

    // Track tick time for frame-independent lifetime decay
    private static long lastLifeUpdateGameTime = -1L;

    private record QueuedLine(String id, Vec3 start, Vec3 end, float r, float g, float b, float a, int life,
                              boolean throughWalls, float thickness) {}
    private record Line(String id, Vec3 start, Vec3 end, Vec3 prevStart, Vec3 prevEnd,
                        float r, float g, float b, float a, int life, boolean throughWalls, float thickness) {}

    private RaycastLineRenderer() {}

    /** Queue or update a line with a stable ID. */
    public static void queueLine(String id, Vec3 start, Vec3 end,
                                 float r, float g, float b, float a, int lifeTicks) {
        queueLine(id, start, end, r, g, b, a, lifeTicks, false);
    }

    /**
     * As above; a line drawn {@code throughWalls} shows even inside blocks (a ship's centre-of-mass
     * ray, which starts inside its hull).
     */
    public static void queueLine(String id, Vec3 start, Vec3 end,
                                 float r, float g, float b, float a, int lifeTicks, boolean throughWalls) {
        queueLine(id, start, end, r, g, b, a, lifeTicks, throughWalls, RAY_THICKNESS);
    }

    /**
     * As above, {@code thickness} blocks wide: the rays of a body or ship a size mod shrank are drawn to its size
     * ({@link #DEFAULT_THICKNESS} times its scale).
     */
    public static void queueLine(String id, Vec3 start, Vec3 end,
                                 float r, float g, float b, float a, int lifeTicks, boolean throughWalls, float thickness) {
        synchronized (PENDING_LINES) {
            PENDING_LINES.add(new QueuedLine(id, start, end, r, g, b, a, lifeTicks, throughWalls, thickness));
        }
    }

    /**
     * Calculates and queues debug rays for the given entity. Recomputed every
     * FRAME from the interpolated render position — per-tick updates made the
     * rays visibly stutter (this is a debug view; the extra clips are accepted).
     */
    public static void updateDebugRays(Entity entity, float partialTick) {
        FullStopCapability fullstop = FullStopCapability.grabCapability(entity);
        if (fullstop == null) return;

        // Client-side capabilities of non-player entities are only consumed by this
        // debug view (gameplay is server-side), so ticking them here is safe and
        // keeps the debug rays moving for mobs.
        if (entity.tickCount != fullstop.getLastTick()) {
            fullstop.tick(entity);
            fullstop.setLastTick(entity.tickCount);
        }

        // Calculate opacity based on speed
        double speed = fullstop.getCurrentScaledVelocity().length();
        float speedAlphaMultiplier = (float) Math.min(Math.max(speed / SPEED_FOR_MAX_OPACITY, MIN_SPEED_ALPHA), 1.0);
        // lines as thick as suits the body's size (a 1/16 body's rays are a sixteenth as thick)
        float thickness = (float) (RAY_THICKNESS * RaycastUtil.sizeFactor(entity));

        Vec3 direction = RaycastUtil.getRayDirection(fullstop);
        double rayLength = RaycastUtil.getRayLength(entity, fullstop);
        // Same ray layout the server uses, so the debug view matches reality.
        List<Vec3> rayStarts = RaycastUtil.getRayStarts(entity,
                net.camacraft.fullstop.FullStopConfig.SERVER.raycastMode.get(), fullstop.getFrame());
        Level level = entity.level();

        // Ray starts come from the tick-resolution bounding box; shifting them by
        // the render-interpolated offset keeps the rays glued to the visual entity.
        Vec3 renderOffset = entity.getPosition(partialTick).subtract(entity.position());
        Vec3 preImpactVelocity = fullstop.getPreImpactScaledVelocity();
        // Same swept box the gameplay classifier uses for its seam-graze test.
        net.minecraft.world.phys.AABB sweptBox = entity.getBoundingBox().expandTowards(direction.scale(rayLength));

        int index = 0;
        for (Vec3 tickStart : rayStarts) {
            Vec3 start = tickStart.add(renderOffset);
            Vec3 end = start.add(direction.scale(rayLength));
            // SOURCE_ONLY to match the gameplay detector (NONE hid water-skip /
            // belly-flop hits from the debug view).
            ClipContext ctx = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, entity);
            BlockHitResult blockHit = level.clip(ctx);
            boolean hitBlock = blockHit.getType() == HitResult.Type.BLOCK;

            // Red only when the gameplay classifier would count this hit — i.e.
            // the face is opposed AND approached with collision-grade speed.
            // Standing flush against a wall (the rays' minimum reach always
            // touches it) shows YELLOW: touching, not colliding.
            boolean collisionGrade = false;
            if (hitBlock) {
                // The classifier's own face rules (approach speed, seam-graze
                // cross-section (in ship space for ship blocks), floor-ahead,
                // buried face), judged in the entity's gravity frame, so the
                // debug view can never disagree with gameplay.
                collisionGrade = net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector.opposesAsSurface(
                        level, entity, fullstop.getFrame(), direction, preImpactVelocity, sweptBox,
                        RaycastUtil.effectiveFloorY(entity), blockHit.getBlockPos(), blockHit.getDirection())
                        && !net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector.isBuriedFace(
                                level, blockHit.getBlockPos(), level.getBlockState(blockHit.getBlockPos()), blockHit.getDirection());
            }

            float r = hitBlock ? (collisionGrade ? HIT_R : TOUCH_R) : MISS_R;
            float g = hitBlock ? (collisionGrade ? HIT_G : TOUCH_G) : MISS_G;
            float b = hitBlock ? (collisionGrade ? HIT_B : TOUCH_B) : MISS_B;
            float a = (hitBlock ? (collisionGrade ? HIT_A : TOUCH_A) : MISS_A) * speedAlphaMultiplier;
            if (hitBlock && net.camacraft.fullstop.common.compat.ShipCompat.isShipBlock(level, blockHit.getBlockPos())) {
                // The same real-contact test the gameplay classifier applies to ship blocks.
                boolean contact = net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector.shipHitIsContact(
                        level, entity, fullstop, blockHit.getBlockPos());
                if (!contact) {
                    r = SHIP_AHEAD_R;
                    g = SHIP_AHEAD_G;
                    b = SHIP_AHEAD_B;
                    a = SHIP_AHEAD_A * speedAlphaMultiplier;
                } else if (collisionGrade) {
                    r = SHIP_HIT_R;
                    g = SHIP_HIT_G;
                    b = SHIP_HIT_B;
                } else {
                    r = SHIP_TOUCH_R;
                    g = SHIP_TOUCH_G;
                    b = SHIP_TOUCH_B;
                }
            }

            // Unique ID per entity per ray to prevent flashing
            String id = "entity_" + entity.getId() + "_ray_" + index;

            // Queue line with configured life
            queueLine(id, start, end, r, g, b, a, LINE_LIFE_TICKS, false, thickness);
            index++;
        }
    }

    /** Called each frame from RenderLevelStageEvent. */
    public static void render(PoseStack poseStack, float partialTick) {
        // Process pending lines
        synchronized (PENDING_LINES) {
            if (!PENDING_LINES.isEmpty()) {
                for (QueuedLine queued : PENDING_LINES) {
                    // Lines are recomputed per frame at their exact interpolated
                    // positions, so no cross-update smoothing or prev-position
                    // lerp is wanted — both just made the rays trail the entity.
                    ACTIVE_LINES.put(queued.id, new Line(
                            queued.id,
                            queued.start, queued.end,
                            queued.start, queued.end,
                            queued.r, queued.g, queued.b, queued.a,
                            queued.life, queued.throughWalls, queued.thickness
                    ));
                }
                PENDING_LINES.clear();
            }
        }

        if (ACTIVE_LINES.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();

        // Tick-based lifetime decay (prevents FPS-dependent flashing)
        if (mc.level != null) {
            long gt = mc.level.getGameTime();
            if (lastLifeUpdateGameTime == -1L) lastLifeUpdateGameTime = gt;

            long dt = gt - lastLifeUpdateGameTime;
            if (dt > 0) {
                Iterator<Map.Entry<String, Line>> it = ACTIVE_LINES.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Line> entry = it.next();
                    Line l = entry.getValue();

                    int nextLife = l.life - (int) dt;
                    if (nextLife <= 0) {
                        it.remove();
                        continue;
                    }

                    // Update life; keep prev=current to avoid extra interpolation jumps across ticks
                    entry.setValue(new Line(
                            l.id,
                            l.start, l.end,
                            l.start, l.end,
                            l.r, l.g, l.b, l.a,
                            nextLife, l.throughWalls, l.thickness
                    ));
                }
                lastLifeUpdateGameTime = gt;
            }
        }

        if (ACTIVE_LINES.isEmpty()) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // Depth-tested lines first, then the ones drawn through walls.
        drawLines(poseStack, partialTick, camPos, false);
        RenderSystem.disableDepthTest();
        drawLines(poseStack, partialTick, camPos, true);
        RenderSystem.enableDepthTest();

        RenderSystem.disableBlend();
    }

    private static void drawLines(PoseStack poseStack, float partialTick, Vec3 camPos, boolean throughWalls) {
        boolean any = false;
        for (Line line : ACTIVE_LINES.values()) {
            if (line.throughWalls == throughWalls) {
                any = true;
                break;
            }
        }
        if (!any) return;

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buffer = tess.getBuilder();
        Matrix4f matrix = poseStack.last().pose();

        // Render as camera-facing quads so rays are visible even when viewed head-on
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (Line line : ACTIVE_LINES.values()) {
            if (line.throughWalls != throughWalls) continue;
            // Fade out as life decreases
            float fade = Math.min(1.0f, (float) line.life / (float) LINE_LIFE_TICKS);

            Vec3 s = line.prevStart.lerp(line.start, partialTick);
            Vec3 e = line.prevEnd.lerp(line.end, partialTick);

            Vec3 seg = e.subtract(s);
            double segLen = seg.length();
            if (segLen < 1.0e-6) continue;

            Vec3 segN = seg.scale(1.0 / segLen);

            // Use camera->midpoint as "view" direction
            Vec3 mid = s.add(e).scale(0.5);
            Vec3 view = camPos.subtract(mid);
            double viewLen = view.length();
            if (viewLen < 1.0e-6) {
                view = new Vec3(0, 1, 0);
                viewLen = 1.0;
            }
            Vec3 viewN = view.scale(1.0 / viewLen);

            // Perpendicular vector for thickness
            Vec3 perp = segN.cross(viewN);
            double perpLen = perp.length();
            if (perpLen < 1.0e-6) {
                // Segment and view nearly parallel; choose a stable fallback axis
                perp = segN.cross(new Vec3(0, 1, 0));
                perpLen = perp.length();
                if (perpLen < 1.0e-6) {
                    perp = segN.cross(new Vec3(1, 0, 0));
                    perpLen = perp.length();
                }
            }
            Vec3 perpN = perp.scale(1.0 / Math.max(perpLen, 1.0e-6));
            Vec3 off = perpN.scale(line.thickness);

            // Quad corners
            Vec3 s1 = s.add(off);
            Vec3 s2 = s.subtract(off);
            Vec3 e1 = e.add(off);
            Vec3 e2 = e.subtract(off);

            // Camera-relative
            Vec3 s1r = s1.subtract(camPos);
            Vec3 s2r = s2.subtract(camPos);
            Vec3 e1r = e1.subtract(camPos);
            Vec3 e2r = e2.subtract(camPos);

            float a = line.a * fade;

            // Two triangles: (s1, e1, e2) and (s1, e2, s2)
            buffer.vertex(matrix, (float) s1r.x, (float) s1r.y, (float) s1r.z).color(line.r, line.g, line.b, a).endVertex();
            buffer.vertex(matrix, (float) e1r.x, (float) e1r.y, (float) e1r.z).color(line.r, line.g, line.b, a).endVertex();
            buffer.vertex(matrix, (float) e2r.x, (float) e2r.y, (float) e2r.z).color(line.r, line.g, line.b, a).endVertex();

            buffer.vertex(matrix, (float) s1r.x, (float) s1r.y, (float) s1r.z).color(line.r, line.g, line.b, a).endVertex();
            buffer.vertex(matrix, (float) e2r.x, (float) e2r.y, (float) e2r.z).color(line.r, line.g, line.b, a).endVertex();
            buffer.vertex(matrix, (float) s2r.x, (float) s2r.y, (float) s2r.z).color(line.r, line.g, line.b, a).endVertex();
        }

        tess.end();
    }

    public static void clear() {
        synchronized (PENDING_LINES) {
            PENDING_LINES.clear();
        }
        ACTIVE_LINES.clear();
        lastLifeUpdateGameTime = -1L;
    }
}