package net.camacraft.fullstop.server.physics.ship;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.camacraft.fullstop.server.physics.interaction.BlockAlterRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.slf4j.Logger;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * A moving ship ploughs through tree canopies and soft plants: blocks in
 * {@code fullstop:ship_sweepable} (leaves, saplings, flowers, tall grass, vines, sugar cane,
 * berry bushes...) that the hull passes through break, with drops. Valkyrien Skies doesn't
 * collide ships with leaves at all (and plants have no collision to begin with), so without this
 * a ship ghosts through a forest; it never reports these contacts either, so the hull is checked
 * against the vegetation here, once per tick, for every ship moving at {@code sweepMinSpeed} or
 * faster. A ship barely drifting slides through untouched.
 *
 * <p>Each of the ship's blocks is followed along the path it really took over the tick - the
 * ship's poses of last tick and this one, interpolated, as {@link ShipHullClash} follows them
 * through another hull - and every world block the cube passed through is looked at. So the tips
 * of a spinning arm, which move ten blocks a tick along an arc, clear what they swept between two
 * poses, not only what they happen to overlap at tick time (the owner's spinning arm passed
 * through the leaves it was placed among: only the cells under the arm at each tick were ever
 * tested, and, those leaves being placed, none of them gave). A hull too big to list its blocks
 * ({@link ShipHullClash}: more than 16384) is swept the old way, over the region its bounds moved
 * through.</p>
 *
 * <p>Natural leaves go at the sweep speed; leaves a player placed (a hedge, a leaf roof) at
 * {@code sweepPlacedLeavesMinSpeed} (8 m/s) or faster, or at any sweeping speed with
 * {@code sweepPlacedLeaves} on ({@link ShipMaterials#sweepableAt}). Each block costs its toughness
 * times {@code breakCostMultiplier} out of the ship's energy (a leaf about 15 kJ; flowers and grass
 * nothing), and {@code sweepDrag} times what it spent comes out of the ship's momentum. The same
 * rules as any block FullStop breaks: a piloted ship is its pilot acting, an unpiloted one follows
 * mobGriefing, and minigame hosts and protection mods are asked for every block.
 *
 * <p>Cost is bounded: only ships that move; each world cell is read once per ship per tick; chunk
 * sections whose palette holds no sweepable block are skipped whole (a cached check per section);
 * chunks are never loaded; at most {@link #MAX_PROBE_OPS} probes, {@link #MAX_READS_PER_SHIP} reads
 * and {@link #MAX_BREAKS_PER_SHIP} breaks per ship and tick ({@code sweepMaxBlocksPerTick} for the
 * whole level). What a capped tick leaves, the next pass of the hull clears.
 */
public final class ShipVegetationSweep {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final int MAX_READS_PER_SHIP = 8192;
    private static final int MAX_PROBES_PER_SHIP = 256;
    private static final int MAX_BREAKS_PER_SHIP = 32;
    /** How far out from a block's centre (toward its corners) the corner probes sit. */
    private static final double CORNER = 0.35;
    /** Most moments of the last tick's travel a block is tested at in the region sweep (one per half block). */
    private static final int MAX_TIME_SAMPLES = 4;
    /** Blocks of travel between two samples of a hull block's arc: nothing a block wide is stepped over. */
    private static final double STEP = 0.5;
    private static final int MAX_SAMPLES = 64;
    /** The tick's poses are kept at this many evenly spaced moments: matrix work per ship, not per sample. */
    private static final int POSE_STEPS = 64;
    /** Most world cells probed along the arcs per ship per tick (a probe: one set lookup, a read only for a new cell). */
    private static final int MAX_PROBE_OPS = 32768;
    private static final byte UNKNOWN = 0, MAY_HAVE = 1, NONE = 2;

    private ShipVegetationSweep() {
    }

    /** The per-level cap on vegetation blocks broken this tick (sweepMaxBlocksPerTick). */
    static final class Budget {
        private int left;

        Budget(int blocks) {
            this.left = blocks;
        }
    }

    static void sweep(ServerLevel level, ShipRef ship, ShipTracker.State state, AABB bounds, long now,
                      Budget budget, ShipPhysicsServer.Pilot pilot) {
        if (budget.left <= 0 || !SERVER.breakTerrain.get() || !SERVER.sweepVegetation.get()) return;
        if (state.inGrace(now) || ship.isStatic()) return;
        double massKg = state.mass();
        if (!Double.isFinite(massKg)) return;

        double minSpeed = SERVER.sweepMinSpeed.get();
        Vec3 velocity = state.velocity();
        double spin = state.angularVelocity().length() * halfDiagonal(bounds);
        if (velocity.length() + spin < minSpeed) return;

        double energySpeed = Math.max(velocity.length(), 0.5 * spin);
        double energy = 0.5 * EntityStackUtils.kgToUnits(massKg) * (energySpeed / 20.0) * (energySpeed / 20.0)
                * SERVER.terrainEnergyFraction.get();
        Sweeper sweeper = new Sweeper(level, ship, budget, pilot, minSpeed, energy);

        ShipHullClash.Poses poses = ShipHullClash.Poses.of(ship);
        long[] cells = poses == null ? null : ShipHullClash.hull(level, ship, now).cells();
        if (poses != null && cells != null) {
            sweeper.alongArcs(poses, cells);
        } else {
            sweeper.region(bounds, velocity);
        }
        sweeper.finish(velocity, spin, massKg, state.position());
    }

    /** One ship's sweep this tick: what it may spend, who breaks, and what it broke. */
    private static final class Sweeper {
        final ServerLevel level;
        final ShipRef ship;
        final Budget budget;
        final ShipPhysicsServer.Pilot pilot;
        final double minSpeed;
        final double costMultiplier = SERVER.breakCostMultiplier.get();
        final boolean drops = SERVER.terrainBlockDrops.get();
        double energy;
        // Who breaks, asked once there is something to break: the pilot, or mobGriefing.
        @Nullable
        Entity cause;
        @Nullable
        Boolean allowed;
        int reads;
        int probes;
        int broken;
        double spent;
        String how = "";

        Sweeper(ServerLevel level, ShipRef ship, Budget budget, ShipPhysicsServer.Pilot pilot, double minSpeed, double energy) {
            this.level = level;
            this.ship = ship;
            this.budget = budget;
            this.pilot = pilot;
            this.minSpeed = minSpeed;
            this.energy = energy;
        }

        /**
         * A sweepable world block the hull passed through: broken if the hull moves fast enough there and the
         * ship can afford and is allowed it. False when the sweep must stop (out of energy or budget, or not
         * allowed to break anything).
         */
        boolean tryBreak(BlockPos.MutableBlockPos pos, BlockState block) {
            Vec3 hullV = ship.velocityAt(Vec3.atCenterOf(pos));
            double hullSpeed = hullV == null ? 0.0 : hullV.length();
            if (hullSpeed < minSpeed || !ShipMaterials.sweepableAt(block, hullSpeed)) return true;
            double cost = ShipMaterials.toughness(level, pos, block) * costMultiplier;
            if (!Double.isFinite(cost)) return true;
            if (cost > energy) return false; // out of energy for this tick
            if (allowed == null) {
                cause = pilot.get();
                allowed = cause != null || !SERVER.unpilotedFollowsMobGriefing.get()
                        || ForgeEventFactory.getMobGriefingEvent(level, null);
            }
            if (!allowed) return false;
            if (!BlockAlterRules.mayAlter(level, pos, cause) || !BlockAlterRules.breakEventAllows(level, pos, block, cause)) {
                return true;
            }
            if (!level.destroyBlock(pos, drops, cause)) return true;
            energy -= cost;
            spent += cost;
            broken++;
            return broken < MAX_BREAKS_PER_SHIP && --budget.left > 0;
        }

        /**
         * Every block of the hull followed along its arc over the tick, the world cells its cube passed through
         * (its centre and eight inset corners, every half block of travel) read once each.
         */
        void alongArcs(ShipHullClash.Poses poses, long[] cells) {
            how = "along its blocks' arcs";
            Matrix4d[] poseAt = new Matrix4d[POSE_STEPS + 1];
            Matrix4d nowPose = poseAt(poses, poseAt, POSE_STEPS);
            Matrix4d prevPose = poseAt(poses, poseAt, 0);
            LongOpenHashSet visited = new LongOpenHashSet();
            Long2ObjectOpenHashMap<LevelChunk> chunks = new Long2ObjectOpenHashMap<>();
            Long2ByteOpenHashMap sections = new Long2ByteOpenHashMap();
            Vector3d scratch = new Vector3d();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
            int ops = 0;
            for (long packed : cells) {
                Vec3 centre = new Vec3(BlockPos.getX(packed) + 0.5, BlockPos.getY(packed) + 0.5, BlockPos.getZ(packed) + 0.5);
                Vec3 pNow = at(nowPose, centre, scratch);
                Vec3 pPrev = at(prevPose, centre, scratch);
                int samples = Mth.clamp((int) Math.ceil(pNow.distanceTo(pPrev) / STEP), 1, MAX_SAMPLES);
                int lastStep = -1;
                for (int k = 0; k <= samples; k++) {
                    int step = (int) Math.round((double) k / samples * POSE_STEPS);
                    if (step == lastStep) continue;
                    lastStep = step;
                    Vec3 p = k == 0 ? pPrev : k == samples ? pNow : at(poseAt(poses, poseAt, step), centre, scratch);
                    for (int probe = 0; probe < 9; probe++) {
                        if (++ops > MAX_PROBE_OPS || reads > MAX_READS_PER_SHIP) return;
                        double px = p.x, py = p.y, pz = p.z;
                        if (probe > 0) {
                            int corner = probe - 1;
                            px += (corner & 1) == 0 ? -CORNER : CORNER;
                            py += (corner & 2) == 0 ? -CORNER : CORNER;
                            pz += (corner & 4) == 0 ? -CORNER : CORNER;
                        }
                        int x = Mth.floor(px), y = Mth.floor(py), z = Mth.floor(pz);
                        if (y < minY || y >= maxY || !visited.add(BlockPos.asLong(x, y, z))) continue;
                        LevelChunkSection section = section(chunks, sections, pos, x, y, z);
                        if (section == null) continue;
                        reads++;
                        BlockState block = section.getBlockState(x & 15, y & 15, z & 15);
                        if (!ShipMaterials.sweepCandidate(block)) continue;
                        probes++;
                        pos.set(x, y, z);
                        if (!tryBreak(pos, block)) return;
                    }
                }
            }
        }

        /**
         * The chunk section holding a world cell, if the chunk is loaded and ticking, not a shipyard's, and the
         * section's palette holds something sweepable (each answer kept for the tick); else null.
         */
        @Nullable
        private LevelChunkSection section(Long2ObjectOpenHashMap<LevelChunk> chunks, Long2ByteOpenHashMap sections,
                                          BlockPos.MutableBlockPos pos, int x, int y, int z) {
            int cx = x >> 4, cz = z >> 4;
            long chunkKey = ChunkPos.asLong(cx, cz);
            LevelChunk chunk = chunks.get(chunkKey);
            if (chunk == null) {
                if (chunks.containsKey(chunkKey)) return null;
                chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null && (Ships.chunkInShipyard(level, cx, cz) || !level.isPositionEntityTicking(pos.set(x, y, z)))) {
                    chunk = null;
                }
                chunks.put(chunkKey, chunk);
                if (chunk == null) return null;
            }
            int index = chunk.getSectionIndexFromSectionY(y >> 4);
            if (index < 0 || index >= chunk.getSectionsCount()) return null;
            long sectionKey = SectionPos.asLong(cx, y >> 4, cz);
            byte known = sections.get(sectionKey);
            LevelChunkSection section = chunk.getSection(index);
            if (known == UNKNOWN) {
                boolean may = !section.hasOnlyAir() && section.maybeHas(s -> s.is(FullStopTags.SHIP_SWEEPABLE));
                sections.put(sectionKey, may ? MAY_HAVE : NONE);
                return may ? section : null;
            }
            return known == MAY_HAVE ? section : null;
        }

        /**
         * The old sweep, for a hull too big to list: the region the bounds moved through, each sweepable block
         * in it tested against the hull at its centre now, at earlier moments of the tick's travel, and at its
         * corners; leading edge first.
         */
        void region(AABB bounds, Vec3 velocity) {
            how = "over the region its bounds moved through";
            Vec3 step = velocity.scale(0.05);
            AABB box = bounds.expandTowards(step.scale(-1.0));
            int x0 = Mth.floor(box.minX), x1 = Mth.floor(box.maxX);
            int y0 = Math.max(Mth.floor(box.minY), level.getMinBuildHeight());
            int y1 = Math.min(Mth.floor(box.maxY), level.getMaxBuildHeight() - 1);
            int z0 = Mth.floor(box.minZ), z1 = Mth.floor(box.maxZ);
            if (y0 > y1) return;
            int samples = Mth.clamp((int) Math.ceil(step.length() / 0.5), 1, MAX_TIME_SAMPLES);
            Probe probe = new Probe(level, ship, step, samples);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            sweep:
            for (int i = 0, cxs = (x1 >> 4) - (x0 >> 4); i <= cxs; i++) {
                int cx = lead(x0 >> 4, x1 >> 4, velocity.x, i);
                for (int j = 0, czs = (z1 >> 4) - (z0 >> 4); j <= czs; j++) {
                    int cz = lead(z0 >> 4, z1 >> 4, velocity.z, j);
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null || Ships.chunkInShipyard(level, cx, cz)) continue;
                    if (!level.isPositionEntityTicking(pos.set(cx << 4, y0, cz << 4))) continue;
                    for (int k = 0, sys = (y1 >> 4) - (y0 >> 4); k <= sys; k++) {
                        int sy = lead(y0 >> 4, y1 >> 4, velocity.y, k);
                        int index = chunk.getSectionIndexFromSectionY(sy);
                        if (index < 0 || index >= chunk.getSectionsCount()) continue;
                        LevelChunkSection section = chunk.getSection(index);
                        if (section.hasOnlyAir() || !section.maybeHas(s -> s.is(FullStopTags.SHIP_SWEEPABLE))) continue;

                        int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15);
                        int ay = Math.max(y0, sy << 4), by = Math.min(y1, (sy << 4) + 15);
                        int az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                        for (int a = 0; a <= bx - ax; a++) {
                            int x = lead(ax, bx, velocity.x, a);
                            for (int b = 0; b <= by - ay; b++) {
                                int y = lead(ay, by, velocity.y, b);
                                for (int c = 0; c <= bz - az; c++) {
                                    int z = lead(az, bz, velocity.z, c);
                                    if (++reads > MAX_READS_PER_SHIP) break sweep;
                                    BlockState block = section.getBlockState(x & 15, y & 15, z & 15);
                                    if (!ShipMaterials.sweepCandidate(block)) continue;
                                    if (++probes > MAX_PROBES_PER_SHIP) break sweep;
                                    pos.set(x, y, z);
                                    if (!probe.hullAt(pos)) continue;
                                    if (!tryBreak(pos, block)) break sweep;
                                }
                            }
                        }
                    }
                }
            }
        }

        /** What the plants cost comes out of the ship's momentum along its motion; and the debug line. */
        void finish(Vec3 velocity, double spin, double massKg, Vec3 at) {
            if (broken == 0) return;
            double speed = velocity.length();
            double drag = SERVER.sweepDrag.get();
            if (spent > 0.0 && drag > 0.0 && speed > 1.0e-3) {
                double squared = speed * speed - 2.0 * spent * EntityStackUtils.JOULES_PER_ENERGY_UNIT * drag / massKg;
                double after = Math.sqrt(Math.max(0.0, squared));
                ShipImpulses.applyCapped(level, ship, velocity.scale(-massKg * (speed - after) / speed), at);
            }
            if (SERVER.debugShipCollisionLog.get()) {
                LOGGER.info("[FullStop] ship {} ({} kg at {} m/s, its hull's far corners at {} m/s) swept {} block(s) {} for {} units ({} read, {} sweepable)",
                        ship.id(), Math.round(massKg), String.format(java.util.Locale.ROOT, "%.1f", speed),
                        String.format(java.util.Locale.ROOT, "%.1f", spin), broken, how,
                        String.format(java.util.Locale.ROOT, "%.2f", spent), Math.min(reads, MAX_READS_PER_SHIP), probes);
            }
        }
    }

    /** The ship-to-world matrix at moment {@code step} of {@link #POSE_STEPS}, kept once made. */
    private static Matrix4d poseAt(ShipHullClash.Poses poses, Matrix4d[] cache, int step) {
        Matrix4d m = cache[step];
        if (m == null) m = cache[step] = poses.shipToWorld((double) step / POSE_STEPS);
        return m;
    }

    private static Vec3 at(Matrix4d shipToWorld, Vec3 local, Vector3d scratch) {
        shipToWorld.transformPosition(scratch.set(local.x, local.y, local.z));
        return new Vec3(scratch.x, scratch.y, scratch.z);
    }

    /** The {@code i}-th of {@code min..max}, counted from the end {@code velocity} runs toward. */
    private static int lead(int min, int max, double velocity, int i) {
        return velocity > 0 ? max - i : min + i;
    }

    private static double halfDiagonal(AABB box) {
        return 0.5 * Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
                + box.getZsize() * box.getZsize());
    }

    /**
     * Whether a solid hull block overlaps a terrain block: its centre now, its centre at earlier
     * moments of the last tick (a fast hull may have passed clean through it), then its corners.
     * The region sweep's test.
     */
    private static final class Probe {
        private final ServerLevel level;
        private final ShipRef ship;
        private final Vec3 step;
        private final int samples;
        private final Vector3d scratch = new Vector3d();
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        Probe(ServerLevel level, ShipRef ship, Vec3 step, int samples) {
            this.level = level;
            this.ship = ship;
            this.step = step;
            this.samples = samples;
        }

        boolean hullAt(BlockPos pos) {
            double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
            // A world point the hull covered a moment t ago lies, in the ship's current frame,
            // where that point plus t's travel does now.
            for (int i = 0; i < samples; i++) {
                double f = (double) i / samples;
                if (solid(cx + step.x * f, cy + step.y * f, cz + step.z * f)) return true;
            }
            for (int corner = 0; corner < 8; corner++) {
                if (solid(cx + ((corner & 1) == 0 ? -CORNER : CORNER),
                        cy + ((corner & 2) == 0 ? -CORNER : CORNER),
                        cz + ((corner & 4) == 0 ? -CORNER : CORNER))) {
                    return true;
                }
            }
            return false;
        }

        private boolean solid(double x, double y, double z) {
            scratch.set(x, y, z);
            if (!ship.toShip(scratch)) return false;
            cursor.set(Mth.floor(scratch.x), Mth.floor(scratch.y), Mth.floor(scratch.z));
            LevelChunk chunk = level.getChunkSource().getChunkNow(cursor.getX() >> 4, cursor.getZ() >> 4);
            if (chunk == null) return false;
            BlockState hull = chunk.getBlockState(cursor);
            return !hull.isAir() && !hull.is(FullStopTags.SHIP_SWEEPABLE)
                    && !hull.getCollisionShape(level, cursor).isEmpty();
        }
    }
}
