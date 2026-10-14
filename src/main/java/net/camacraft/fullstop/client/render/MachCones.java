package net.camacraft.fullstop.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.network.MachConePacket;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The shock cone of anything faster than sound, drawn (the owner, 2026-10-10: "if an object sustains its high speed it
 * would make the cone of the sonic boom ... similar to what's seen when a jet flies by faster than the speed of sound.
 * The cone would scale in size depending on the entity or vs ship size. It would be a transparent cone that sorta has a
 * foggy texture").
 *
 * <p>The server says which sources are in a supersonic stretch and how fast they go ({@link MachConePacket}, every half
 * second while it lasts, to players within 320 blocks); this draws, every frame and where the source is drawn, a faint
 * shell of vapour opening backwards from its nose along its motion at the Mach angle, {@code asin(1/M)}: wide just past
 * the barrier, slimmer the faster it goes. For an entity or a ship flying supersonic as a whole the cone trails the whole
 * body, as long as twelve times its half-size; for a rotor or a Create contraption it trails each supersonic tip (where
 * the server found them, in the body's own frame, placed where the body is drawn now), as long as a few blocks. Its
 * texture is soft noise drawn sliding back along the cone, brightest near the nose and fading out behind. It is lit as
 * the world is where it is (the owner, 2026-10-12: at midnight "the sonic cone was still visible as if it was light
 * out"): the lightmap's own colour for the block and sky light of its cell, so night, dusk, rain, gamma, night vision
 * and True Darkness all show on it, with Colorful Lighting's colour for the block light when there is one; and it fades
 * into the fog as everything else does. It fades in and out over a fifth of a second, and belongs to the level it was
 * started in (a cone was still drawn after its ship had been taken to VS Genesis's space).</p>
 *
 * <p>Cheap: a few hundred translucent quads per cone and nothing at all with none in flight. {@code machCones} (client)
 * turns it off, {@code machConeOpacity} sets how visible it is.</p>
 */
@Mod.EventBusSubscriber(modid = FullStop.MODID, value = Dist.CLIENT)
public final class MachCones {

    private static final ResourceLocation TEXTURE = new ResourceLocation(FullStop.MODID, "textures/misc/mach_cone.png");
    /** Without word from the server for this long (ticks) a cone fades out. */
    private static final int LOST_TICKS = 30;
    private static final int SEGMENTS = 24, RINGS = 6;
    /** The cone's slant length against its source's half-size (whole bodies) and for one tip (blocks, at full scale). */
    private static final double BODY_LENGTH = 12.0, TIP_LENGTH = 7.0;
    private static final double MIN_LENGTH = 2.0, MAX_LENGTH = 64.0;
    /** Fade in or out over this long (ms). */
    private static final float FADE_MS = 200.0f;

    private record Key(boolean ship, long id) {}

    private static final class Cone {
        final Key key;
        /** The level the cone was started in: another one (a dimension change, a new world) drops it. */
        final ClientLevel level;
        float mach = 1.0f;
        List<Vec3> tips = List.of();
        long lastPacket;
        boolean on;
        float fade;
        /** Where each drawn point was last frame (world), for its direction of motion. */
        Vec3[] last = new Vec3[0];
        Vec3[] dir = new Vec3[0];

        Cone(Key key, ClientLevel level) {
            this.key = key;
            this.level = level;
        }
    }

    private static final Map<Key, Cone> CONES = new HashMap<>();
    private static long lastFrameMillis = -1L;
    private static int drawnLastFrame;
    private static int drawnTotal;
    /** The last cone drawn: nose, direction, slant length, Mach number, opacity (for the harness). */
    private static String lastDrawn = "";
    /** The light the last cone was drawn in: block and sky light, and the colour (for the harness). */
    private static String lastLight = "";
    /** This frame's fog (blocks from the camera), read once before the cones are drawn. */
    private static float fogStart = Float.MAX_VALUE, fogEnd = Float.MAX_VALUE;
    private static boolean fogCylinder;

    private MachCones() {
    }

    /** A start, update or stop from the server. */
    public static void on(MachConePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Key key = new Key(packet.ship(), packet.sourceId());
        Cone cone = CONES.get(key);
        if (!packet.active()) {
            if (cone != null) cone.on = false;
            return;
        }
        if (cone == null || cone.level != mc.level) CONES.put(key, cone = new Cone(key, mc.level));
        cone.on = true;
        cone.mach = Math.max(1.0f, packet.mach());
        cone.tips = packet.tips();
        cone.lastPacket = mc.level.getGameTime();
    }

    /** Cones drawn in the last frame, and in all frames so far (for the harness). */
    public static int drawnLastFrame() {
        return drawnLastFrame;
    }

    public static int drawnTotal() {
        return drawnTotal;
    }

    public static String lastDrawn() {
        return lastDrawn;
    }

    public static String lastLight() {
        return lastLight;
    }

    /** How many sources have a cone (drawn or fading), for the harness. */
    public static int sources() {
        return CONES.size();
    }

    private static boolean enabled() {
        return !FullStopConfig.CLIENT_SPEC.isLoaded() || FullStopConfig.CLIENT.machCones.get();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // after the particles (and the translucent blocks), as F3+V's rays
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        long millis = Util.getMillis();
        float dtMs = lastFrameMillis < 0 ? 0.0f : Math.min(250.0f, millis - lastFrameMillis);
        lastFrameMillis = millis;
        drawnLastFrame = 0;
        if (CONES.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !enabled()) {
            CONES.clear();
            return;
        }
        float partial = event.getPartialTick();
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack poses = event.getPoseStack();
        // drawn at once, as Full Stop's F3+V rays are: textured, coloured, blended, both faces, no depth write
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorTexShader);
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, TEXTURE);
        fogStart = com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart();
        fogEnd = com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd();
        fogCylinder = com.mojang.blaze3d.systems.RenderSystem.getShaderFogShape() == com.mojang.blaze3d.shaders.FogShape.CYLINDER;
        com.mojang.blaze3d.vertex.BufferBuilder out = com.mojang.blaze3d.vertex.Tesselator.getInstance().getBuilder();
        out.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_TEX);
        double opacity = FullStopConfig.CLIENT_SPEC.isLoaded() ? FullStopConfig.CLIENT.machConeOpacity.get() : 0.6;
        float time = (millis % 100_000L) / 1000.0f;
        long now = level.getGameTime();
        boolean any = false;
        Iterator<Cone> it = CONES.values().iterator();
        while (it.hasNext()) {
            Cone cone = it.next();
            if (cone.level != level) {
                it.remove();
                continue;
            }
            boolean live = cone.on && now >= cone.lastPacket && now - cone.lastPacket <= LOST_TICKS;
            cone.fade = Mth.clamp(cone.fade + (live ? 1.0f : -1.0f) * dtMs / FADE_MS, 0.0f, 1.0f);
            if (!live && cone.fade <= 0.0f) {
                it.remove();
                continue;
            }
            List<Vec3[]> placed = place(level, cone, partial, dtMs);
            for (Vec3[] p : placed) {
                if (!draw(level, poses, out, cam, p[0], p[1], p[2].x, cone.mach, (float) (opacity * cone.fade), time)) continue;
                lastDrawn = String.format(java.util.Locale.ROOT, "nose(%.1f,%.1f,%.1f) dir(%.2f,%.2f,%.2f) len %.1f mach %.2f op %.2f cam %.1f",
                        p[0].x, p[0].y, p[0].z, p[1].x, p[1].y, p[1].z, p[2].x, cone.mach, opacity * cone.fade, p[0].distanceTo(cam));
                drawnLastFrame++;
                any = true;
            }
        }
        if (any) com.mojang.blaze3d.vertex.Tesselator.getInstance().end();
        else out.end().release();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        drawnTotal += drawnLastFrame;
    }

    /** Where each of the cone's noses is drawn now: {@code {nose (world), direction of motion (unit), {half-size, 0, 0}}}. */
    private static List<Vec3[]> place(ClientLevel level, Cone cone, float partial, float dtMs) {
        List<Vec3> points = new ArrayList<>();
        List<Vec3> known = new ArrayList<>();
        double size;
        if (cone.key.ship()) {
            ShipRef ship = ShipRef.of(level, cone.key.id());
            if (ship == null) return List.of();
            double scale = ship.scale();
            if (cone.tips.isEmpty()) {
                Vec3 centre = ship.position();
                Vec3 local = centre == null ? null : ship.toShip(centre);
                Vec3 drawn = local == null ? null : ship.toWorld(local, ShipRef.Pose.RENDER);
                if (drawn == null) return List.of();
                AABB box = ship.worldAabb();
                size = box == null ? 2.0 : Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize())) * 0.5;
                points.add(drawn);
                known.add(ship.velocityAt(drawn));
            } else {
                size = 0.6 * scale;
                for (Vec3 tip : cone.tips) {
                    Vec3 drawn = ship.toWorld(tip, ShipRef.Pose.RENDER);
                    if (drawn == null) continue;
                    points.add(drawn);
                    known.add(ship.velocityAt(drawn));
                }
            }
            size = Math.max(size, 0.25);
            return finish(cone, points, known, size, cone.tips.isEmpty() ? BODY_LENGTH : TIP_LENGTH / 0.6, dtMs);
        }
        Entity entity = level.getEntity((int) cone.key.id());
        if (entity == null) return List.of();
        if (!cone.tips.isEmpty() && net.camacraft.fullstop.common.compat.create.CreateCompat.LOADED) {
            for (Vec3 tip : cone.tips) {
                Vec3[] at = net.camacraft.fullstop.client.compat.create.ContraptionCones.tipAt(entity, tip, partial);
                if (at == null) continue;
                points.add(at[0]);
                known.add(at[1]);
            }
            return finish(cone, points, known, 0.6, TIP_LENGTH / 0.6, dtMs);
        }
        Vec3 drawn = entity.getPosition(partial).add(0.0, entity.getBbHeight() * 0.5, 0.0);
        Vec3 motion = new Vec3(entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo).scale(20.0);
        points.add(drawn);
        known.add(motion);
        size = Math.max(entity.getBbWidth(), entity.getBbHeight()) * 0.5;
        return finish(cone, points, known, Math.max(size, 0.2), BODY_LENGTH, dtMs);
    }

    /**
     * Each point's direction of motion: the velocity known for it when there is one, else how it moved since the last
     * frame; eased a little from frame to frame. The nose sits half a size ahead of the point, along its motion.
     */
    private static List<Vec3[]> finish(Cone cone, List<Vec3> points, List<Vec3> known, double size, double lengthPerSize, float dtMs) {
        if (cone.last.length != points.size()) {
            cone.last = new Vec3[points.size()];
            cone.dir = new Vec3[points.size()];
        }
        List<Vec3[]> placed = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            Vec3 p = points.get(i);
            Vec3 v = known.get(i);
            Vec3 d = null;
            if (v != null && v.lengthSqr() > 1.0) d = v.normalize();
            else if (cone.last[i] != null && dtMs > 0.0f && p.distanceToSqr(cone.last[i]) > 1.0E-6) d = p.subtract(cone.last[i]).normalize();
            cone.last[i] = p;
            if (d == null) d = cone.dir[i];
            if (d == null) continue;
            Vec3 eased = cone.dir[i] == null ? d : cone.dir[i].scale(0.35).add(d.scale(0.65));
            if (eased.lengthSqr() < 1.0E-8) continue;
            cone.dir[i] = eased.normalize();
            double length = Mth.clamp(size * lengthPerSize, MIN_LENGTH, MAX_LENGTH);
            placed.add(new Vec3[]{p.add(cone.dir[i].scale(size * 0.5)), cone.dir[i], new Vec3(length, 0.0, 0.0)});
        }
        return placed;
    }

    /** One cone: its nose at {@code nose}, opening back against {@code dir} at the Mach angle, {@code length} along its slant. */
    private static boolean draw(ClientLevel level, PoseStack poses, VertexConsumer out, Vec3 cam, Vec3 nose, Vec3 dir, double length,
                                float mach, float opacity, float time) {
        if (opacity <= 0.001f) return false;
        double halfAngle = Mth.clamp(Math.asin(1.0 / Math.max(1.0001, mach)), Math.toRadians(12.0), Math.toRadians(78.0));
        double along = length * Math.cos(halfAngle), across = length * Math.sin(halfAngle);
        Vec3 back = dir.scale(-1.0);
        Vec3 e1 = (Math.abs(back.y) < 0.9 ? back.cross(new Vec3(0, 1, 0)) : back.cross(new Vec3(1, 0, 0))).normalize();
        Vec3 e2 = back.cross(e1).normalize();
        float[] light = worldLight(level, BlockPos.containing(nose));
        float r = 0.93f * light[0], g = 0.96f * light[1], b = light[2];
        // vapour shows by the light it scatters: in the dark it all but vanishes (it would only darken the sky behind it)
        opacity *= 0.15f + 0.85f * Mth.clamp(luminance(light) * 1.25f, 0.0f, 1.0f);
        Matrix4f pose = poses.last().pose();
        org.joml.Matrix3f normalPose = poses.last().normal();
        Vec3 o = nose.subtract(cam);
        float scroll = time * 0.6f;
        for (int j = 0; j < RINGS; j++) {
            double t0 = (double) j / RINGS, t1 = (double) (j + 1) / RINGS;
            float a0 = opacity * shape(t0), a1 = opacity * shape(t1);
            for (int i = 0; i < SEGMENTS; i++) {
                double th0 = 2.0 * Math.PI * i / SEGMENTS, th1 = 2.0 * Math.PI * (i + 1) / SEGMENTS;
                float u0 = 3.0f * i / SEGMENTS, u1 = 3.0f * (i + 1) / SEGMENTS;
                float v0 = (float) (t0 * 2.0) - scroll, v1 = (float) (t1 * 2.0) - scroll;
                vertex(out, pose, normalPose, o, back, e1, e2, along, across, t0, th0, r, g, b, a0, u0, v0);
                vertex(out, pose, normalPose, o, back, e1, e2, along, across, t1, th0, r, g, b, a1, u0, v1);
                vertex(out, pose, normalPose, o, back, e1, e2, along, across, t1, th1, r, g, b, a1, u1, v1);
                vertex(out, pose, normalPose, o, back, e1, e2, along, across, t0, th1, r, g, b, a0, u1, v0);
            }
        }
        return true;
    }

    /**
     * The light at {@code at} as the world's blocks and bodies there are lit: the lightmap's colour (this frame's, with
     * everything that changes it) for the cell's block and sky light. With Colorful Lighting the cell's light may come
     * in its coloured format (its documented contract: red bits 0-7, green 8-15, sky 16-19, blue 20-27, 0xF on top), and
     * the block light's share of the lightmap colour then takes that colour.
     */
    private static float[] worldLight(ClientLevel level, BlockPos at) {
        int packed = net.minecraft.client.renderer.LevelRenderer.getLightColor(level, at);
        int block, sky;
        float hr = 1.0f, hg = 1.0f, hb = 1.0f;
        if (packed >>> 28 == 0xF) {
            int r8 = packed & 0xFF, g8 = (packed >>> 8) & 0xFF, b8 = (packed >>> 20) & 0xFF;
            sky = (packed >>> 16) & 0xF;
            int max = Math.max(r8, Math.max(g8, b8));
            block = Mth.clamp(Math.round(max / 17.0f), 0, 15);
            if (max > 0) {
                hr = r8 / (float) max;
                hg = g8 / (float) max;
                hb = b8 / (float) max;
            }
        } else {
            block = Mth.clamp(net.minecraft.client.renderer.LightTexture.block(packed), 0, 15);
            sky = Mth.clamp(net.minecraft.client.renderer.LightTexture.sky(packed), 0, 15);
        }
        float[] lit = lightmap(level, block, sky);
        if (block > 0 && (hr < 1.0f || hg < 1.0f || hb < 1.0f)) {
            float all = luminance(lit), skyOnly = luminance(lightmap(level, 0, sky));
            float share = all > 1.0E-4f ? Mth.clamp((all - skyOnly) / all, 0.0f, 1.0f) : 0.0f;
            lit[0] *= 1.0f - share + share * hr;
            lit[1] *= 1.0f - share + share * hg;
            lit[2] *= 1.0f - share + share * hb;
        }
        lastLight = String.format(java.util.Locale.ROOT, "block %d sky %d rgb(%.3f,%.3f,%.3f)", block, sky, lit[0], lit[1], lit[2]);
        return lit;
    }

    /** The lightmap's pixel for this block and sky light (red, green, blue, 0-1). */
    private static float[] lightmap(ClientLevel level, int block, int sky) {
        try {
            com.mojang.blaze3d.platform.NativeImage pixels = ((net.camacraft.fullstop.client.mixin.LightTextureAccessor)
                    Minecraft.getInstance().gameRenderer.lightTexture()).fullstop$lightPixels();
            int abgr = pixels.getPixelRGBA(block, sky);
            return new float[]{(abgr & 0xFF) / 255.0f, ((abgr >>> 8) & 0xFF) / 255.0f, ((abgr >>> 16) & 0xFF) / 255.0f};
        } catch (RuntimeException noLightmap) {
            // (no lightmap to read: the level's own darkening, as an estimate)
            float lit = Math.max(Math.max(0, sky - level.getSkyDarken()), block) / 15.0f;
            return new float[]{lit, lit, lit};
        }
    }

    private static float luminance(float[] rgb) {
        return 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2];
    }

    /** How much of a point at {@code p} (from the camera) shows through this frame's fog: 1 nearer than its start, 0 past its end. */
    private static float fogVisibility(Vec3 p) {
        if (!(fogStart < fogEnd)) return 1.0f;
        double d = fogCylinder ? Math.max(Math.sqrt(p.x * p.x + p.z * p.z), Math.abs(p.y)) : p.length();
        if (d <= fogStart) return 1.0f;
        if (d >= fogEnd) return 0.0f;
        float t = (float) ((d - fogStart) / (fogEnd - fogStart));
        return 1.0f - t * t * (3.0f - 2.0f * t);
    }

    /** The shell's opacity along its slant (0 at the nose, 1 at the far end): rising just behind the nose, fading out behind. */
    private static float shape(double t) {
        double rise = Mth.clamp(t / 0.12, 0.0, 1.0);
        rise = rise * rise * (3.0 - 2.0 * rise);
        return (float) (rise * Math.pow(1.0 - t, 1.2));
    }

    private static void vertex(VertexConsumer out, Matrix4f pose, org.joml.Matrix3f normalPose, Vec3 o, Vec3 back, Vec3 e1, Vec3 e2,
                               double along, double across, double t, double theta, float r, float g, float b, float a, float u, float v) {
        double c = Math.cos(theta), s = Math.sin(theta);
        Vec3 radial = e1.scale(c).add(e2.scale(s));
        Vec3 p = o.add(back.scale(along * t)).add(radial.scale(across * t));
        // a shock shell shows most where it is seen edge-on (as in a schlieren photograph): the surface's normal against
        // the line of sight (the camera is at the origin here)
        Vec3 normal = radial.scale(along).subtract(back.scale(across));
        double nl = normal.length(), pl = p.length();
        double facing = nl > 1.0E-6 && pl > 1.0E-6 ? Math.abs(normal.dot(p)) / (nl * pl) : 0.0;
        float edge = (float) (0.45 + 0.55 * (1.0 - facing) * (1.0 - facing));
        out.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, Math.min(1.0f, a * edge * 1.6f) * fogVisibility(p)).uv(u, v).endVertex();
    }

    /** For tests: whether a cone of this source is known. */
    public static boolean has(boolean ship, long id) {
        return CONES.containsKey(new Key(ship, id));
    }

    @Nullable
    static Cone get(boolean ship, long id) {
        return CONES.get(new Key(ship, id));
    }
}
