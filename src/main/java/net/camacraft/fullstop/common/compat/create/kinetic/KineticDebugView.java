package net.camacraft.fullstop.common.compat.create.kinetic;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.client.render.debug.RaycastLineRenderer;
import net.camacraft.fullstop.common.compat.create.CreateCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FullStop's velocity debug view (F3+V) for the kinetic hazards: every cogwheel, large cogwheel and water
 * wheel near the camera draws its teeth (or paddle) ring and arrows along the way the teeth or paddles move. A
 * water wheel also draws a fainter ring where its catch ends; red when turning with water at it (it drowns),
 * yellow when it only sweeps, grey when idle (or slower than {@code gearMinRpm}). A single gear is yellow when
 * turning: it only pushes; red when its teeth move at {@code gearFlingSpeed} or faster: it flings. Where two gears mesh, the in-running mouth of their nip (where both gears' teeth run
 * into the mesh) is drawn as a wedge with an arrow into the mesh: red when fast enough to crush
 * ({@value KineticHazards#TEETH_BITE_RPM} RPM and up), yellow when it only draws in, grey when the pair stands
 * jammed (its network overstressed) or idle. Every body a part acts on gets a line: into the mesh from a body in
 * a nip (red), out along the push from a body touching a single gear's teeth (yellow; red where it is flung), to where a water wheel
 * pulls it (red held under, orange drawn in).
 *
 * <p>Client only, and only with Create (reached through {@code CreateDebugHooks}). Parts are found the way the
 * hazards find them ({@link HazardScanner}), in a box round the camera every half second; how fast they turn
 * is read every frame. The pulls themselves are the hazards' own geometry ({@link KineticGrip}).
 */
public final class KineticDebugView {

    /** Parts within this distance of the camera (blocks; the scanner's own box limit keeps it modest). */
    private static final double RANGE = 12.0;
    /** Bodies within this distance of the camera get their catch lines. */
    private static final double BODY_RANGE = 24.0;
    private static final int RESCAN_TICKS = 10;
    private static final int MAX_PARTS = 96;
    private static final int LINE_LIFE_TICKS = 5;
    private static final float RING_THICKNESS = 0.015f;
    private static final float CATCH_THICKNESS = 0.008f;
    private static final float PULL_THICKNESS = 0.02f;

    private static final float[] BITES = {1.0f, 0.0f, 0.0f};
    private static final float[] PULLS = {1.0f, 0.8f, 0.0f};
    private static final float[] IDLE = {0.6f, 0.6f, 0.6f};
    private static final float[] DRAWN_IN = {1.0f, 0.5f, 0.0f};

    private static final List<Hazard> PARTS = new ArrayList<>();
    /** Whether a water wheel has water at its paddles (found at each rescan). */
    private static final Map<Hazard, Boolean> WET = new HashMap<>();
    private static long scannedAt = Long.MIN_VALUE;
    private static ClientLevel scannedIn;

    private KineticDebugView() {
    }

    /** Queues this frame's rings, arrows and catch lines. */
    public static void update(ClientLevel level, float partialTick, Vec3 camera) {
        if (!CreateCompat.active()) return;
        FullStopConfig.ServerConfigValues cfg = FullStopConfig.SERVER;
        boolean gears = cfg.gearHazards.get();
        boolean wheels = cfg.waterWheelHazards.get();
        if (!gears && !wheels) return;
        long now = level.getGameTime();
        if (scannedIn != level || now - scannedAt >= RESCAN_TICKS || now < scannedAt) {
            PARTS.clear();
            WET.clear();
            HazardScanner.scan(level, new AABB(camera, camera).inflate(RANGE), gears, wheels, PARTS);
            PARTS.sort((a, b) -> Double.compare(a.pos().distSqr(BlockPos.containing(camera)), b.pos().distSqr(BlockPos.containing(camera))));
            if (PARTS.size() > MAX_PARTS) PARTS.subList(MAX_PARTS, PARTS.size()).clear();
            for (Hazard part : PARTS) {
                if (part.kind().wheel) WET.put(part, hasWater(level, part));
            }
            scannedAt = now;
            scannedIn = level;
        }
        if (PARTS.isEmpty()) return;

        double minRpm = cfg.gearMinRpm.get();
        List<Hazard.Live> turning = new ArrayList<>();
        List<Hazard.Live> driven = new ArrayList<>();
        for (Hazard part : PARTS) {
            Hazard.Live live = HazardScanner.resolveAny(level, part);
            if (live == null) continue;
            boolean active = live.turning(minRpm);
            float[] colour;
            if (!active) colour = IDLE;
            else if (part.kind().wheel) colour = Boolean.TRUE.equals(WET.get(part)) ? BITES : PULLS;
            else colour = flings(live, cfg) ? BITES : PULLS;
            String id = "kinetic_" + part.pos().asLong() + "_" + part.shipId() + "_";
            ring(id + "teeth_", live, live.radius(), colour, 0.85f, RING_THICKNESS);
            double scale = live.radius() / part.kind().radius;
            if (part.kind().wheel) {
                ring(id + "catch_", live, live.radius() + KineticGrip.CATCH_MARGIN * scale, colour, 0.35f, CATCH_THICKNESS);
            }
            if (active) {
                arrows(id + "arrow_", live, colour, scale);
                turning.add(live);
            }
            if (!part.kind().wheel && Math.abs(live.driveRpm()) >= minRpm) driven.add(live);
        }
        List<KineticGrip.Nip> nips = gears ? KineticHazards.nips(driven) : List.of();
        for (KineticGrip.Nip nip : nips) mouth(nip, minRpm);
        if (turning.isEmpty() && nips.isEmpty()) return;

        // the bodies the parts act on: in a nip, touching a single gear, or caught by a water wheel
        double bodyRangeSqr = BODY_RANGE * BODY_RANGE;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity body) || exempt(body) || body.distanceToSqr(camera) > bodyRangeSqr) continue;
            AABB box = body.getBoundingBox();
            Vec3 offset = body.getPosition(partialTick).subtract(body.position());
            Vec3 from = box.getCenter().add(offset);
            // a creative player is never caught, only pushed or flung by a single gear
            boolean creative = body instanceof Player p && p.isCreative();
            KineticGrip.NipCatch inNip = null;
            for (KineticGrip.Nip nip : creative ? List.<KineticGrip.Nip>of() : nips) {
                KineticGrip.NipCatch k = KineticGrip.inMouth(box, nip);
                if (k != null && (inNip == null || k.s() > inNip.s())) inNip = k;
            }
            if (inNip != null) {
                float[] c = inNip.nip().stalled() ? IDLE : BITES;
                RaycastLineRenderer.queueLine("kinetic_pull_" + body.getId(), from, inNip.nip().mesh(), c[0], c[1], c[2], 0.9f,
                        LINE_LIFE_TICKS, true, PULL_THICKNESS);
                continue;
            }
            KineticGrip.Catch touched = null;
            KineticGrip.Catch wheel = null;
            for (Hazard.Live live : turning) {
                if (live.hazard().kind().wheel) {
                    if (creative) continue;
                    KineticGrip.Catch k = KineticGrip.of(box, live);
                    if (k != null && (wheel == null || k.outside() < wheel.outside())) wheel = k;
                } else {
                    KineticGrip.Catch k = KineticGrip.touch(box, live);
                    if (k != null && (touched == null || k.outside() < touched.outside())) touched = k;
                }
            }
            if (touched != null) {
                // pushed off (flung, red, by a very fast gear): out from the gear and along its teeth
                boolean flung = flings(touched.part(), cfg);
                Vec3 push = touched.radialDir().scale(0.4).add(touched.tangent().scale(0.6)).normalize().scale(flung ? 1.2 : 0.6);
                float[] c = flung ? BITES : PULLS;
                RaycastLineRenderer.queueLine("kinetic_pull_" + body.getId(), from, from.add(push), c[0], c[1], c[2], 0.9f,
                        LINE_LIFE_TICKS, true, PULL_THICKNESS);
            } else if (wheel != null) {
                pullLine(body, wheel, partialTick);
            }
        }
    }

    /**
     * A nip's in-running mouth: a wedge from the mesh back between the two rims as far as the mouth reaches, and an
     * arrow along the teeth into the mesh.
     */
    private static void mouth(KineticGrip.Nip nip, double minRpm) {
        boolean running = !nip.stalled() && nip.rpm() >= minRpm;
        float[] c = !running ? IDLE : nip.rpm() >= KineticHazards.TEETH_BITE_RPM ? BITES : PULLS;
        String id = "kinetic_nip_" + nip.a().hazard().pos().asLong() + "_" + nip.b().hazard().pos().asLong() + "_";
        double depth = nip.depth();
        Vec3 back = nip.mesh().subtract(nip.along().scale(depth));
        Vec3 pa = back.add(nip.across().scale(nip.rimA(-depth)));
        Vec3 pb = back.add(nip.across().scale(nip.rimB(-depth)));
        RaycastLineRenderer.queueLine(id + "a", nip.mesh(), pa, c[0], c[1], c[2], 0.9f, LINE_LIFE_TICKS, false, RING_THICKNESS);
        RaycastLineRenderer.queueLine(id + "b", nip.mesh(), pb, c[0], c[1], c[2], 0.9f, LINE_LIFE_TICKS, false, RING_THICKNESS);
        RaycastLineRenderer.queueLine(id + "mouth", pa, pb, c[0], c[1], c[2], 0.9f, LINE_LIFE_TICKS, false, RING_THICKNESS);
        // the arrow: from the mouth into the mesh
        double head = 0.08 * nip.scale();
        Vec3 tip = nip.mesh();
        Vec3 tail = back;
        Vec3 backOfHead = tip.subtract(nip.along().scale(head));
        RaycastLineRenderer.queueLine(id + "arrow", tail, tip, c[0], c[1], c[2], 1.0f, LINE_LIFE_TICKS, false, RING_THICKNESS);
        RaycastLineRenderer.queueLine(id + "arrow_l", tip, backOfHead.add(nip.across().scale(head * 0.7)), c[0], c[1], c[2], 1.0f,
                LINE_LIFE_TICKS, false, RING_THICKNESS);
        RaycastLineRenderer.queueLine(id + "arrow_r", tip, backOfHead.subtract(nip.across().scale(head * 0.7)), c[0], c[1], c[2], 1.0f,
                LINE_LIFE_TICKS, false, RING_THICKNESS);
    }

    /** Whether a single gear's teeth move fast enough to fling a still body (gearFlingSpeed). */
    private static boolean flings(Hazard.Live part, FullStopConfig.ServerConfigValues cfg) {
        return !part.hazard().kind().wheel && KineticGrip.teethMps(part) >= cfg.gearFlingSpeed.get();
    }

    /** A line from a body a water wheel has caught to where it pulls it. */
    private static void pullLine(LivingEntity body, KineticGrip.Catch k, float partialTick) {
        Hazard.Live part = k.part();
        Vec3 offset = body.getPosition(partialTick).subtract(body.position());
        Vec3 from = body.getBoundingBox().getCenter().add(offset);
        Vec3 up = up(body);
        double height = k.radialDir().dot(up);
        boolean pinned = body.isInWater() && height < 0.35 && k.tangent().dot(up) <= 0.3;
        Vec3 alongAxis = part.axis().scale(k.axial());
        Vec3 target = pinned
                ? part.center().add(up.scale(-0.75 * part.radius())).add(alongAxis)
                : part.center().add(k.radialDir().scale(Math.min(k.radial(), part.radius()))).add(alongAxis);
        float[] c = pinned ? BITES : DRAWN_IN;
        RaycastLineRenderer.queueLine("kinetic_pull_" + body.getId(), from, target, c[0], c[1], c[2], 0.9f, LINE_LIFE_TICKS,
                true, PULL_THICKNESS);
    }

    /** A circle of {@code radius} round the part's axis, in its disc's plane. */
    private static void ring(String id, Hazard.Live part, double radius, float[] c, float alpha, float thickness) {
        Vec3[] uv = plane(part.axis());
        int n = part.hazard().kind().large() ? 32 : 20;
        Vec3 prev = null;
        for (int i = 0; i <= n; i++) {
            double a = Math.PI * 2.0 * i / n;
            Vec3 p = part.center().add(uv[0].scale(Math.cos(a) * radius)).add(uv[1].scale(Math.sin(a) * radius));
            if (prev != null) {
                RaycastLineRenderer.queueLine(id + i, prev, p, c[0], c[1], c[2], alpha, LINE_LIFE_TICKS, false, thickness);
            }
            prev = p;
        }
    }

    /** Arrows on the teeth ring along the way the teeth (paddles) move, longer the faster they turn. */
    private static void arrows(String id, Hazard.Live part, float[] c, double scale) {
        Vec3[] uv = plane(part.axis());
        int n = part.hazard().kind().large() ? 8 : 4;
        double rpm = Math.abs(part.rpm());
        double length = (0.2 + Math.min(0.4, rpm / 256.0 * 0.4)) * scale;
        double head = 0.08 * scale;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0 * (i + 0.5) / n;
            Vec3 radial = uv[0].scale(Math.cos(a)).add(uv[1].scale(Math.sin(a)));
            Vec3 tangent = part.axis().cross(radial);
            if (part.omega() < 0) tangent = tangent.scale(-1.0);
            Vec3 base = part.center().add(radial.scale(part.radius()));
            Vec3 tip = base.add(tangent.scale(length));
            Vec3 back = tip.subtract(tangent.scale(head));
            RaycastLineRenderer.queueLine(id + i + "_shaft", base, tip, c[0], c[1], c[2], 1.0f, LINE_LIFE_TICKS, false, RING_THICKNESS);
            RaycastLineRenderer.queueLine(id + i + "_l", tip, back.add(radial.scale(head * 0.7)), c[0], c[1], c[2], 1.0f, LINE_LIFE_TICKS,
                    false, RING_THICKNESS);
            RaycastLineRenderer.queueLine(id + i + "_r", tip, back.subtract(radial.scale(head * 0.7)), c[0], c[1], c[2], 1.0f,
                    LINE_LIFE_TICKS, false, RING_THICKNESS);
        }
    }

    /** Two unit vectors spanning the plane square to {@code axis}. */
    private static Vec3[] plane(Vec3 axis) {
        Vec3 other = Math.abs(axis.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = axis.cross(other).normalize();
        Vec3 v = axis.cross(u).normalize();
        return new Vec3[]{u, v};
    }

    /** Whether a water wheel (in the world) has water in the cells its paddles sweep; a wheel on a ship counts as wet. */
    private static boolean hasWater(ClientLevel level, Hazard part) {
        if (part.shipId() != Hazard.NO_SHIP) return true;
        int reach = part.kind().large() ? 2 : 1;
        BlockPos c = part.pos();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-reach, -reach, -reach), c.offset(reach, reach, reach))) {
            if (level.getFluidState(pos).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    private static boolean exempt(LivingEntity body) {
        if (body.isSpectator() || body.isPassenger() || body.noPhysics || body.isDeadOrDying()) return true;
        if (body instanceof ArmorStand) return true;
        // a creative player on foot is still pushed or flung by a single gear
        return body instanceof Player p && p.getAbilities().flying;
    }

    private static Vec3 up(LivingEntity body) {
        Vec3 down = Gravity.pullDirection(body);
        return down == null || down.lengthSqr() < 1.0E-8 ? new Vec3(0, 1, 0) : down.normalize().scale(-1.0);
    }
}
