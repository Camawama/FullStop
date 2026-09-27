package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.camacraft.fullstop.server.physics.interaction.BlockAlterRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
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
 * <p>Only natural leaves go unless {@code sweepPlacedLeaves} is on: a player's hedge or leaf roof
 * stays. Each block costs its toughness times {@code breakCostMultiplier} out of the ship's
 * energy (a leaf about 15 kJ; flowers and grass nothing), and {@code sweepDrag} times what it
 * spent comes out of the ship's momentum. The same rules as any block FullStop breaks: a piloted
 * ship is its pilot acting, an unpiloted one follows mobGriefing, and minigame hosts and
 * protection mods are asked for every block.
 *
 * <p>Cost is bounded: only ships that move; only the region the hull swept since last tick;
 * chunk sections whose palette holds no sweepable block are skipped whole; chunks are never
 * loaded; and each ship reads at most {@link #MAX_READS_PER_SHIP} blocks, tests at most
 * {@link #MAX_PROBES_PER_SHIP} against its hull and breaks at most {@link #MAX_BREAKS_PER_SHIP}
 * per tick ({@code sweepMaxBlocksPerTick} for the whole level), leading edge first.
 */
public final class ShipVegetationSweep {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final int MAX_READS_PER_SHIP = 8192;
    private static final int MAX_PROBES_PER_SHIP = 256;
    private static final int MAX_BREAKS_PER_SHIP = 32;
    /** How far out from a block's centre (toward its corners) the corner probes sit. */
    private static final double CORNER = 0.35;
    /** Most moments of the last tick's travel a block is tested at (one per half block). */
    private static final int MAX_TIME_SAMPLES = 4;

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

        // The swept region: where the hull is, and where it was a tick ago.
        Vec3 step = velocity.scale(0.05);
        AABB box = bounds.expandTowards(step.scale(-1.0));
        int x0 = Mth.floor(box.minX), x1 = Mth.floor(box.maxX);
        int y0 = Math.max(Mth.floor(box.minY), level.getMinBuildHeight());
        int y1 = Math.min(Mth.floor(box.maxY), level.getMaxBuildHeight() - 1);
        int z0 = Mth.floor(box.minZ), z1 = Mth.floor(box.maxZ);
        if (y0 > y1) return;

        double energySpeed = Math.max(velocity.length(), 0.5 * spin);
        double energy = 0.5 * EntityStackUtils.kgToUnits(massKg) * (energySpeed / 20.0) * (energySpeed / 20.0)
                * SERVER.terrainEnergyFraction.get();
        double costMultiplier = SERVER.breakCostMultiplier.get();
        boolean drops = SERVER.terrainBlockDrops.get();
        int samples = Mth.clamp((int) Math.ceil(step.length() / 0.5), 1, MAX_TIME_SAMPLES);

        Probe probe = new Probe(level, ship, step, samples);
        // Who breaks, asked once there is something to break: the pilot, or mobGriefing.
        Entity cause = null;
        Boolean allowed = null;
        int reads = 0;
        int probes = 0;
        int broken = 0;
        double spent = 0.0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Leading edge first, so a capped tick still clears what the ship is running into.
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
                                if (!ShipMaterials.sweepable(block) || block.hasBlockEntity()) continue;
                                if (++probes > MAX_PROBES_PER_SHIP) break sweep;
                                pos.set(x, y, z);
                                if (!probe.hullAt(pos)) continue;
                                Vec3 hullV = ship.velocityAt(Vec3.atCenterOf(pos));
                                if (hullV == null || hullV.length() < minSpeed) continue;

                                double cost = ShipMaterials.toughness(level, pos, block) * costMultiplier;
                                if (!Double.isFinite(cost)) continue;
                                if (cost > energy) break sweep; // out of energy for this tick
                                if (allowed == null) {
                                    cause = pilot.get();
                                    allowed = cause != null || !SERVER.unpilotedFollowsMobGriefing.get()
                                            || ForgeEventFactory.getMobGriefingEvent(level, null);
                                }
                                if (!allowed) break sweep;
                                if (!BlockAlterRules.mayAlter(level, pos, cause)
                                        || !BlockAlterRules.breakEventAllows(level, pos, block, cause)) {
                                    continue;
                                }
                                if (!level.destroyBlock(pos, drops, cause)) continue;
                                energy -= cost;
                                spent += cost;
                                broken++;
                                if (broken >= MAX_BREAKS_PER_SHIP || --budget.left <= 0) break sweep;
                            }
                        }
                    }
                }
            }
        }

        if (broken == 0) return;
        double speed = velocity.length();
        double drag = SERVER.sweepDrag.get();
        if (spent > 0.0 && drag > 0.0 && speed > 1.0e-3) {
            // What the plants cost comes out of the ship's momentum along its motion.
            double squared = speed * speed - 2.0 * spent * EntityStackUtils.JOULES_PER_ENERGY_UNIT * drag / massKg;
            double after = Math.sqrt(Math.max(0.0, squared));
            ShipImpulses.applyCapped(level, ship, velocity.scale(-massKg * (speed - after) / speed), state.position);
        }
        if (SERVER.debugShipCollisionLog.get()) {
            LOGGER.info("[FullStop] ship {} ({} kg at {} m/s) swept {} block(s) for {} units ({} read, {} probed)",
                    ship.id(), Math.round(massKg), String.format(java.util.Locale.ROOT, "%.1f", speed), broken,
                    String.format(java.util.Locale.ROOT, "%.2f", spent), Math.min(reads, MAX_READS_PER_SHIP),
                    Math.min(probes, MAX_PROBES_PER_SHIP));
        }
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
