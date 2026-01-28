package net.camacraft.fullstop.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.physics.Physics;
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

    // Line Properties
    private static final float LINE_WIDTH = 5.0f;
    private static final int LINE_LIFE_TICKS = 5; // How long a line persists without updates
    private static final float SMOOTHING_FACTOR = 0.5f; // 0.0 = no smoothing, 1.0 = full smoothing (laggy)

    // Velocity Opacity Scaling
    private static final double SPEED_FOR_MAX_OPACITY = 20.0; // Speed in m/s for full opacity
    private static final float MIN_SPEED_ALPHA = 0.2f; // Minimum alpha multiplier when stationary

    private static final Map<String, Line> ACTIVE_LINES = new HashMap<>();
    private static final List<QueuedLine> PENDING_LINES = new ArrayList<>();

    private record QueuedLine(String id, Vec3 start, Vec3 end, float r, float g, float b, float a, int life) {}
    private record Line(String id, Vec3 start, Vec3 end, Vec3 prevStart, Vec3 prevEnd,
                        float r, float g, float b, float a, int life) {}

    private RaycastLineRenderer() {}

    /** Queue or update a line with a stable ID. */
    public static void queueLine(String id, Vec3 start, Vec3 end,
                                 float r, float g, float b, float a, int lifeTicks) {
        synchronized (PENDING_LINES) {
            PENDING_LINES.add(new QueuedLine(id, start, end, r, g, b, a, lifeTicks));
        }
    }

    /**
     * Calculates and queues debug rays for the given entity.
     * Uses Physics logic to determine ray positions and directions.
     */
    public static void updateDebugRays(Entity entity) {
        FullStopCapability fullstop = FullStopCapability.grabCapability(entity);
        if (fullstop == null) return;

        // Only tick the capability on the client if it's the local player,
        // because other entities have their data synced from the server.
        // Ticking them here might overwrite the synced data with incorrect client-side calculations.
        if (entity == Minecraft.getInstance().player) {
            if (entity.tickCount != fullstop.getLastTick()) {
                fullstop.tick(entity);
                fullstop.setLastTick(entity.tickCount);
            }
        }

        // Calculate opacity based on speed
        double speed = fullstop.getCurrentScaledVelocity().length();
        float speedAlphaMultiplier = (float) Math.min(Math.max(speed / SPEED_FOR_MAX_OPACITY, MIN_SPEED_ALPHA), 1.0);

        Vec3 direction = Physics.getRayDirection(fullstop);
        double rayLength = Physics.getRayLength(entity, fullstop);
        List<Vec3> rayStarts = Physics.getRayStarts(entity);
        Level level = entity.level();

        int index = 0;
        for (Vec3 start : rayStarts) {
            Vec3 end = start.add(direction.scale(rayLength));
            ClipContext ctx = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity);
            BlockHitResult blockHit = level.clip(ctx);
            boolean hitBlock = blockHit.getType() == HitResult.Type.BLOCK;

            float r = hitBlock ? HIT_R : MISS_R;
            float g = hitBlock ? HIT_G : MISS_G;
            float b = hitBlock ? HIT_B : MISS_B;
            float a = (hitBlock ? HIT_A : MISS_A) * speedAlphaMultiplier;

            // Unique ID per entity per ray to prevent flashing
            String id = "entity_" + entity.getId() + "_ray_" + index;
            
            // Queue line with configured life
            queueLine(id, start, end, r, g, b, a, LINE_LIFE_TICKS);
            index++;
        }
    }

    /** Called each frame from RenderLevelStageEvent. */
    public static void render(PoseStack poseStack, float partialTick) {
        // Process pending lines
        synchronized (PENDING_LINES) {
            if (!PENDING_LINES.isEmpty()) {
                for (QueuedLine queued : PENDING_LINES) {
                    Line existing = ACTIVE_LINES.get(queued.id);
                    if (existing != null) {
                        // Smooth transition: lerp from existing current position to new target
                        Vec3 smoothedStart = existing.start.lerp(queued.start, 1.0 - SMOOTHING_FACTOR);
                        Vec3 smoothedEnd = existing.end.lerp(queued.end, 1.0 - SMOOTHING_FACTOR);

                        ACTIVE_LINES.put(queued.id, new Line(
                                queued.id,
                                smoothedStart, smoothedEnd,
                                existing.start, existing.end, // Keep old "current" as "prev" for frame interpolation
                                queued.r, queued.g, queued.b, queued.a,
                                queued.life
                        ));
                    } else {
                        // New line (no previous)
                        ACTIVE_LINES.put(queued.id, new Line(
                                queued.id,
                                queued.start, queued.end,
                                queued.start, queued.end,
                                queued.r, queued.g, queued.b, queued.a,
                                queued.life
                        ));
                    }
                }
                PENDING_LINES.clear();
            }
        }

        if (ACTIVE_LINES.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        RenderSystem.lineWidth(LINE_WIDTH);
        // RenderSystem.disableDepthTest(); // Make lines visible through blocks

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buffer = tess.getBuilder();
        Matrix4f matrix = poseStack.last().pose();

        buffer.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);

        for (Line line : ACTIVE_LINES.values()) {
            // Fade out as life decreases
            float fade = Math.min(1.0f, (float) line.life / LINE_LIFE_TICKS); 

            Vec3 s = line.prevStart.lerp(line.start, partialTick);
            Vec3 e = line.prevEnd.lerp(line.end, partialTick);

            Vec3 sRel = s.subtract(camPos);
            Vec3 eRel = e.subtract(camPos);

            buffer.vertex(matrix, (float) sRel.x, (float) sRel.y, (float) sRel.z)
                    .color(line.r, line.g, line.b, line.a * fade)
                    .endVertex();
            buffer.vertex(matrix, (float) eRel.x, (float) eRel.y, (float) eRel.z)
                    .color(line.r, line.g, line.b, line.a * fade)
                    .endVertex();
        }

        tess.end();

        // RenderSystem.enableDepthTest(); // Re-enable depth test
        RenderSystem.lineWidth(1.0f); // Reset line width
        RenderSystem.disableBlend();

        // Update lifetime & remove dead lines
        Iterator<Map.Entry<String, Line>> it = ACTIVE_LINES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Line> entry = it.next();
            Line l = entry.getValue();
            int nextLife = l.life - 1;
            if (nextLife <= 0) {
                it.remove();
                continue;
            }

            // Update life
            entry.setValue(new Line(
                    l.id,
                    l.start, l.end,
                    l.start, l.end, // For static lines, prev = current
                    l.r, l.g, l.b, l.a,
                    nextLife
            ));
        }
    }

    public static void clear() {
        synchronized (PENDING_LINES) {
            PENDING_LINES.clear();
        }
        ACTIVE_LINES.clear();
    }
}
