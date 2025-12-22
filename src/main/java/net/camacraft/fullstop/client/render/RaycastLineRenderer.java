package net.camacraft.fullstop.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.*;

/**
 * Debug line renderer for raycasts — persistent lines with per-ID smoothing and fade.
 * Must be called from RenderLevelStageEvent on the client.
 */
public final class RaycastLineRenderer {

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

    /** Called each frame from RenderLevelStageEvent. */
    public static void render(PoseStack poseStack, float partialTick) {
        // Process pending lines
        synchronized (PENDING_LINES) {
            if (!PENDING_LINES.isEmpty()) {
                for (QueuedLine queued : PENDING_LINES) {
                    Line existing = ACTIVE_LINES.get(queued.id);
                    if (existing != null) {
                        // Preserve previous positions for lerp
                        ACTIVE_LINES.put(queued.id, new Line(
                                queued.id,
                                queued.start, queued.end,
                                existing.start, existing.end,
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
        RenderSystem.lineWidth(5.0f);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buffer = tess.getBuilder();
        Matrix4f matrix = poseStack.last().pose();

        buffer.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);

        for (Line line : ACTIVE_LINES.values()) {
            float lifeRatio = Math.max(0f, Math.min(1f, line.life / 20f));
            float fade = (float) Math.pow(lifeRatio, 1.2);

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

        RenderSystem.lineWidth(5.0f);
        RenderSystem.disableBlend();

        // Update lifetime & shift positions
        Iterator<Map.Entry<String, Line>> it = ACTIVE_LINES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Line> entry = it.next();
            Line l = entry.getValue();
            int nextLife = l.life - 1;
            if (nextLife <= 0) {
                it.remove();
                continue;
            }

            entry.setValue(new Line(
                    l.id,
                    l.start, l.end,
                    l.start, l.end,
                    l.r, l.g, l.b, l.a,
                    nextLife
            ));
        }
    }
}
