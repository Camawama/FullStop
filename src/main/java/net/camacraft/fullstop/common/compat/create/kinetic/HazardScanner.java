package net.camacraft.fullstop.common.compat.create.kinetic;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlock;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlock;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Finds the spinning parts of Create near a body ({@link #scan}) and reads how they turn this tick
 * ({@link #resolve}). The only class of the kinetic hazards that names Create's types: it is loaded
 * only with Create installed ({@code CreateCompat}).
 *
 * <p>Cheap by construction: a chunk section is only searched when its palette may hold a cogwheel or
 * a water wheel ({@code LevelChunkSection.maybeHas}, a look at a handful of palette entries), so a
 * body far from any of them costs a few palette checks a few times a second. Encased cogwheels and
 * gearboxes are never hazards (they're encased); shafts are safe too.
 */
final class HazardScanner {

    /** The farthest a part reaches for a body beyond its own radius: the large water wheel's paddles and the catch margin. */
    static final double MAX_REACH = Hazard.Kind.LARGE_WATER_WHEEL.radius + KineticGrip.CATCH_MARGIN + 0.25;
    /** Most block positions searched in one box (a huge, scaled-down ship's box is clipped to this many). */
    private static final int MAX_POSITIONS = 32768;
    private static final double TWO_PI_PER_MINUTE_PER_TICK = Math.PI * 2.0 / 60.0 / 20.0;

    private HazardScanner() {
    }

    /** Every hazard within {@link #MAX_REACH} of {@code body} (world blocks and blocks of ships), into {@code out}. */
    static void scan(Level level, AABB body, boolean gears, boolean wheels, List<Hazard> out) {
        scanBox(level, body.inflate(MAX_REACH), Hazard.NO_SHIP, gears, wheels, out);
        if (!Ships.available()) return;
        AABB reach = body.inflate(MAX_REACH);
        if (!Ships.anyIntersecting(level, reach)) return;
        for (ShipRef ship : Ships.intersecting(level, reach)) {
            double scale = ship.scale();
            if (!(scale > 1.0E-4)) continue;
            // the parts' reach is in the ship's own blocks: a 1/16 ship's gear reaches 1/16 as far in the world
            AABB shipBox = ship.worldBoxToShip(body.inflate(MAX_REACH * Math.min(scale, 1.0)));
            if (shipBox != null) scanBox(level, shipBox, ship.id(), gears, wheels, out);
        }
    }

    private static void scanBox(Level level, AABB box, long shipId, boolean gears, boolean wheels, List<Hazard> out) {
        int x0 = Mth.floor(box.minX), y0 = Mth.floor(box.minY), z0 = Mth.floor(box.minZ);
        int x1 = Mth.floor(box.maxX), y1 = Mth.floor(box.maxY), z1 = Mth.floor(box.maxZ);
        long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (volume > MAX_POSITIONS) {
            // clip around the centre: a scaled-down ship's box can be enormous in its own blocks
            int cx = (x0 + x1) >> 1, cy = (y0 + y1) >> 1, cz = (z0 + z1) >> 1;
            int half = 15;
            x0 = Math.max(x0, cx - half); x1 = Math.min(x1, cx + half);
            y0 = Math.max(y0, cy - half); y1 = Math.min(y1, cy + half);
            z0 = Math.max(z0, cz - half); z1 = Math.min(z1, cz + half);
        }
        y0 = Math.max(y0, level.getMinBuildHeight());
        y1 = Math.min(y1, level.getMaxBuildHeight() - 1);
        if (y0 > y1) return;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int scx = x0 >> 4; scx <= x1 >> 4; scx++) {
            for (int scz = z0 >> 4; scz <= z1 >> 4; scz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(scx, scz, false);
                if (chunk == null) continue;
                for (int scy = y0 >> 4; scy <= y1 >> 4; scy++) {
                    int index = chunk.getSectionIndexFromSectionY(scy);
                    if (index < 0 || index >= chunk.getSectionsCount()) continue;
                    LevelChunkSection section = chunk.getSection(index);
                    if (section.hasOnlyAir() || !section.maybeHas(HazardScanner::isHazardState)) continue;
                    int bx0 = Math.max(x0, SectionPos.sectionToBlockCoord(scx)), bx1 = Math.min(x1, SectionPos.sectionToBlockCoord(scx) + 15);
                    int by0 = Math.max(y0, SectionPos.sectionToBlockCoord(scy)), by1 = Math.min(y1, SectionPos.sectionToBlockCoord(scy) + 15);
                    int bz0 = Math.max(z0, SectionPos.sectionToBlockCoord(scz)), bz1 = Math.min(z1, SectionPos.sectionToBlockCoord(scz) + 15);
                    for (int x = bx0; x <= bx1; x++) {
                        for (int y = by0; y <= by1; y++) {
                            for (int z = bz0; z <= bz1; z++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                Hazard.Kind kind = kindOf(state);
                                if (kind == null || (kind.wheel ? !wheels : !gears)) continue;
                                out.add(new Hazard(kind, cursor.set(x, y, z).immutable(), shipId));
                            }
                        }
                    }
                }
            }
        }
    }

    static boolean isHazardState(BlockState state) {
        Block block = state.getBlock();
        return block instanceof CogWheelBlock || block instanceof WaterWheelBlock || block instanceof LargeWaterWheelBlock;
    }

    @Nullable
    static Hazard.Kind kindOf(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CogWheelBlock cog) return cog.isLargeCog() ? Hazard.Kind.LARGE_COGWHEEL : Hazard.Kind.COGWHEEL;
        if (block instanceof WaterWheelBlock) return Hazard.Kind.WATER_WHEEL;
        if (block instanceof LargeWaterWheelBlock) return Hazard.Kind.LARGE_WATER_WHEEL;
        return null;
    }

    /**
     * The part as it is now: null when it's gone, changed kind, or isn't turning. A part on a ship is
     * moved to the ship's pose, scaled with it, and carries the ship's velocity at its centre.
     */
    @Nullable
    static Hazard.Live resolve(Level level, Hazard hazard) {
        Hazard.Live live = resolveAny(level, hazard);
        return live == null || live.rpm() == 0.0 ? null : live;
    }

    /**
     * The part as it is now, turning or not (F3+V draws idle parts too): null when it's gone or changed kind.
     */
    @Nullable
    static Hazard.Live resolveAny(Level level, Hazard hazard) {
        BlockPos pos = hazard.pos();
        if (!level.isLoaded(pos)) return null;
        BlockState state = level.getBlockState(pos);
        if (kindOf(state) != hazard.kind()) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof KineticBlockEntity kinetic)) return null;
        double rpm = kinetic.getSpeed();
        double driveRpm = kinetic.getTheoreticalSpeed();
        if (!Double.isFinite(rpm) || !Double.isFinite(driveRpm)) return null;
        Direction.Axis axis = ((IRotate) state.getBlock()).getRotationAxis(state);
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 axisVec = new Vec3(Direction.get(Direction.AxisDirection.POSITIVE, axis).step());
        double scale = 1.0;
        Vec3 base = Vec3.ZERO;
        if (hazard.shipId() != Hazard.NO_SHIP) {
            ShipRef ship = Ships.byId(level, hazard.shipId());
            if (ship == null || !ship.exists()) return null;
            center = ship.toWorld(center);
            axisVec = ship.dirToWorld(axisVec);
            if (center == null || axisVec == null || axisVec.lengthSqr() < 1.0E-8) return null;
            axisVec = axisVec.normalize();
            scale = ship.scale();
            Vec3 at = ship.velocityAt(center);
            if (at != null) base = at.scale(1.0 / 20.0);
        }
        Hazard.Kind kind = hazard.kind();
        // Create's positive speed is a RIGHT-HANDED turn about the axis' positive direction (counterclockwise seen
        // from its positive end): its renderers turn a part by +speed about the positive axis (Flywheel's
        // rotating.vert, KineticBlockEntityRenderer.kineticRotationTransform), and its water wheel calls the motion
        // VecHelper.rotate(normal, +90, axis) gives a positive one. So the teeth move the way they are drawn.
        return new Hazard.Live(hazard, center, axisVec, kind.radius * scale, kind.halfWidth * scale,
                rpm * TWO_PI_PER_MINUTE_PER_TICK, rpm, base, driveRpm, kinetic.isOverStressed(), axis);
    }

    /**
     * A body wedged in the nip of {@code gear} and its partner loads their network: {@code impactPerRpm} stress units per
     * RPM (as Create counts a consumer's impact), held for as long as it is refreshed each tick ({@code ContraptionStress}).
     * Server only.
     */
    static void jam(Level level, Hazard gear, Object holder, double impactPerRpm) {
        if (level.isClientSide() || !(impactPerRpm > 0.0) || !level.isLoaded(gear.pos())) return;
        if (level.getBlockEntity(gear.pos()) instanceof KineticBlockEntity kinetic) {
            net.camacraft.fullstop.common.compat.create.contraption.ContraptionStress.holdImpact(kinetic, holder, impactPerRpm);
        }
    }

    /** The extra stress impact (SU per RPM) bodies jammed in its gears put on {@code gear}'s block now (for the tests). */
    static double jamImpact(Level level, Hazard gear) {
        return level.getBlockEntity(gear.pos()) instanceof KineticBlockEntity kinetic
                ? net.camacraft.fullstop.common.compat.create.contraption.ContraptionStress.heldImpact(kinetic) : 0.0;
    }

    /** Whether {@code gear}'s network is overstressed (for the tests). */
    static boolean overstressed(Level level, Hazard gear) {
        return level.getBlockEntity(gear.pos()) instanceof KineticBlockEntity kinetic && kinetic.isOverStressed();
    }
}
