package net.camacraft.fullstop.server.physics.pressure;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.liquid.LiquidGravity;
import net.camacraft.camafy.liquid.Liquids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * How deep a body is in the liquid it is in: the height of the liquid over its eyes, in blocks.
 *
 * <p>The surface is the top of the highest still liquid (source blocks) joined to the liquid at the body's eyes,
 * measured along the liquid's own up ({@link LiquidGravity#downAt}; a ship's liquid along the ship's). So the open
 * sea and a flooded shaft of the same depth read the same, a pool three blocks deep reads three wherever it lies in
 * the world, and a body under an overhang or beside an air pocket deep in the sea reads the sea's depth, since its
 * water is joined to the sea round the rock.
 *
 * <p>How it is found. First straight up through the sources over the eyes: where that column ends under the open
 * sky (the chunk's height map), or reaches {@code limit} blocks, that is the answer and nothing more is read. Else
 * a search climbs from the eyes through joined sources, highest first, until it meets a surface under the open sky,
 * rises {@code limit} blocks, or has looked at {@link #SEARCH_BLOCKS} blocks or {@link #SEARCH_RADIUS} blocks to
 * a side. Only loaded chunks are read ({@code getChunkNow}); what is not loaded is not liquid.
 *
 * <p>A reading is kept per body and measured again once a second, when the body has moved two blocks sideways or
 * risen past the surface it found, or when its world changed; between measurements the depth follows the body's
 * own height against the surface found. Measured (gametest {@code PressureDepthTests}, 100 bodies on the floor of
 * an 11x11 basin 20 deep): under the open sky 24 block looks and 1.5 microseconds a body a second; under a roof,
 * where every measurement is the whole search, 788 block looks and 54 microseconds a body a second.
 *
 * <p>What it gets wrong, on purpose or by its limits:
 * <ul>
 *   <li>Flowing and falling liquid carries no depth: a waterfall does not press on who stands under it, and neither
 *       does a shaft filled by one bucket at its top (falling water all the way down).</li>
 *   <li>Under a roof wider than the search reaches (some nine blocks to the way up in a flooded hall, never more
 *       than {@link #SEARCH_RADIUS}), the depth read is the roof's, not the sea's.</li>
 *   <li>A flooded cave that touches nothing higher reads its own depth, however deep under the sea it lies.</li>
 *   <li>The search ends at the first surface under the open sky: a pool joined from below to a higher one reads
 *       its own surface.</li>
 *   <li>Liquid held in a waterlogged block of another kind (Camafy's fluid-logging records) is not seen; vanilla
 *       waterlogging is.</li>
 * </ul>
 */
public final class LiquidDepth {
    private LiquidDepth() {}

    /** Blocks the search looks at before it settles for the highest it reached. */
    static final int SEARCH_BLOCKS = 256;
    /** How far to a side of the body the search goes. */
    static final int SEARCH_RADIUS = 24;
    /** The most blocks a measurement rises, whatever is asked. */
    static final int MAX_LIMIT = 1024;
    /** Ticks a reading is kept (and up to seven more by the body's id, so a crowd is not measured in one tick). */
    private static final int KEEP_TICKS = 20;
    /** Blocks sideways a body moves before its reading is measured again. */
    private static final double KEEP_SIDEWAYS = 2.0;

    /** A reading: the depth in blocks and the liquid's density against water's. NONE while the eyes are in no liquid. */
    public record Reading(double depth, double relativeDensity) {
        public static final Reading NONE = new Reading(0.0, 0.0);
        public boolean submerged() { return relativeDensity > 0.0; }
    }

    private static final class Kept {
        ResourceKey<Level> dimension;
        long ship;
        Direction up;
        /** The surface found, along {@code up} (in the ship's space for a ship's liquid). */
        double surface;
        double anchorX, anchorY, anchorZ;
        double density;
        double scale;
        long tick;
    }

    /** Server thread only (living ticks). Weak keys: a body that is gone drops its reading. */
    private static final Map<LivingEntity, Kept> KEPT = new WeakHashMap<>();

    /** Counted for the cost tests: full measurements and block looks since the server started. */
    private static long measurements, blockLooks;

    public static long measurements() { return measurements; }

    public static long blockLooks() { return blockLooks; }

    /**
     * The depth of the liquid over {@code living}'s eyes. The measurement stops once it has risen {@code limit}
     * blocks of water's weight (more blocks of a lighter liquid), so deeper reads as the limit or a little over.
     * {@link Reading#NONE} where its eyes are in no liquid.
     */
    public static Reading of(LivingEntity living, int limit) {
        Level level = living.level();
        Vec3 eye = living.getEyePosition();
        Kept kept = KEPT.get(living);
        long now = level.getGameTime();
        if (kept != null && kept.dimension == level.dimension() && now - kept.tick < KEEP_TICKS + (living.getId() & 7) && now >= kept.tick) {
            Vec3 at = eye;
            if (kept.ship != ShipRef.NO_ID) {
                ShipRef ship = Ships.byId(level, kept.ship);
                at = ship == null ? null : ship.toShip(eye);
            }
            if (at != null) {
                double along = along(kept.up, at.x, at.y, at.z);
                double moved = sideways(kept.up, at.x - kept.anchorX, at.y - kept.anchorY, at.z - kept.anchorZ);
                if (along <= kept.surface && moved <= KEEP_SIDEWAYS) {
                    return new Reading((kept.surface - along) * kept.scale, kept.density);
                }
            }
        }
        kept = measure(living, level, eye, limit, kept);
        if (kept == null) {
            KEPT.remove(living);
            return Reading.NONE;
        }
        kept.tick = now;
        KEPT.put(living, kept);
        double along = along(kept.up, kept.anchorX, kept.anchorY, kept.anchorZ);
        return new Reading(Math.max(0.0, kept.surface - along) * kept.scale, kept.density);
    }

    /** Forgets a body's reading: its next one is measured afresh. */
    public static void forget(LivingEntity living) {
        KEPT.remove(living);
    }

    private static Kept measure(LivingEntity living, Level level, Vec3 eye, int limit, Kept reuse) {
        measurements++;
        Chunks chunks = new Chunks(level);
        // the world's own liquid at the eyes
        BlockPos eyeBlock = BlockPos.containing(eye);
        Direction up = LiquidGravity.downAt(level, eyeBlock).getOpposite();
        BlockPos start = startIn(chunks, eyeBlock, eye, up);
        long shipId = ShipRef.NO_ID;
        double scale = 1.0;
        Vec3 at = eye;
        if (start == null && Ships.available()) {
            // a ship's liquid: measured in the ship's own blocks, along the ship's axis nearest the world's up there
            for (ShipRef ship : Ships.intersecting(level, new AABB(eye, eye).inflate(0.5))) {
                Vec3 inShip = ship.toShip(eye);
                if (inShip == null) continue;
                BlockPos shipBlock = BlockPos.containing(inShip);
                Vec3 shipUp = ship.dirToShip(Vec3.atLowerCornerOf(up.getNormal()));
                Direction axis = shipUp == null ? Direction.UP : Direction.getNearest(shipUp.x, shipUp.y, shipUp.z);
                BlockPos found = startIn(chunks, shipBlock, inShip, axis);
                if (found == null) continue;
                start = found;
                up = axis;
                shipId = ship.id();
                scale = Math.max(0.0, ship.scale());
                at = inShip;
                break;
            }
        }
        if (start == null) return null;
        FluidState liquid = chunks.fluid(start);
        double density = Math.max(0.01, Liquids.density(liquid.getType()) / 1000.0);
        // a liquid lighter than water needs more of itself for the same weight
        int blocks = (int) Math.min(MAX_LIMIT, Math.ceil(Math.max(1, limit) / Math.min(1.0, density)));
        double surface = surface(chunks, start, up, blocks, shipId == ShipRef.NO_ID && up == Direction.UP);
        Kept kept = reuse != null ? reuse : new Kept();
        kept.dimension = level.dimension();
        kept.ship = shipId;
        kept.up = up;
        kept.surface = surface;
        kept.anchorX = at.x;
        kept.anchorY = at.y;
        kept.anchorZ = at.z;
        kept.density = density;
        kept.scale = scale;
        return kept;
    }

    /**
     * The block to measure from: the eyes' own block where they are under its liquid's surface, the block under them
     * where they are just over a block boundary; a still block if either is one, else whatever liquid there is.
     * Null where the eyes are in no liquid.
     */
    private static BlockPos startIn(Chunks chunks, BlockPos eyeBlock, Vec3 eye, Direction up) {
        FluidState here = chunks.fluid(eyeBlock);
        if (here.isEmpty()) return null;
        // vanilla's eye-in-fluid test: the liquid's own height within its block, an eighth of a block under the eyes
        if (up == Direction.UP && chunks.fluid(eyeBlock.above()).isEmpty()
                && eyeBlock.getY() + here.getOwnHeight() < eye.y - 0.111) return null;
        if (here.isSource()) return eyeBlock;
        // flowing liquid at the eyes: still liquid right beside or under it carries the depth, if there is any
        BlockPos under = eyeBlock.relative(up.getOpposite());
        if (chunks.fluid(under).isSource()) return under;
        for (Direction side : LiquidGravity.sidesOf(up.getOpposite())) {
            BlockPos beside = eyeBlock.relative(side);
            if (chunks.fluid(beside).isSource()) return beside;
        }
        return eyeBlock;
    }

    /** The top of the highest still liquid joined to {@code start}, along {@code up}. */
    private static double surface(Chunks chunks, BlockPos start, Direction up, int limit, boolean skyKnown) {
        if (!chunks.fluid(start).isSource()) return alongBlock(up, start) + 1.0;
        // straight up first
        BlockPos.MutableBlockPos cursor = start.mutable();
        int risen = 0;
        while (risen < limit) {
            cursor.move(up);
            if (!chunks.fluid(cursor).isSource()) {
                cursor.move(up.getOpposite());
                break;
            }
            risen++;
        }
        if (risen >= limit || (skyKnown && chunks.openSkyOver(cursor))) return alongBlock(up, cursor) + 1.0;
        return climb(chunks, start, up, limit, skyKnown);
    }

    /** The search: joined sources, the highest first (a bucket per height over the start), within the limits. */
    private static double climb(Chunks chunks, BlockPos start, Direction up, int limit, boolean skyKnown) {
        Direction down = up.getOpposite();
        Direction[] sides = LiquidGravity.sidesOf(down);
        long startKey = start.asLong();
        int startAlong = alongBlock(up, start);
        // buckets by height over the start: [0, limit] upward, and what lies under the start in one bucket of its own
        LongArrayFIFOQueue[] rising = new LongArrayFIFOQueue[limit + 1];
        LongArrayFIFOQueue sunk = new LongArrayFIFOQueue();
        LongOpenHashSet seen = new LongOpenHashSet(SEARCH_BLOCKS * 2);
        rising[0] = new LongArrayFIFOQueue();
        rising[0].enqueue(startKey);
        seen.add(startKey);
        int top = 0;
        int best = 0;
        int looked = 0;
        BlockPos.MutableBlockPos node = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        while (looked < SEARCH_BLOCKS) {
            long key;
            while (top > 0 && (rising[top] == null || rising[top].isEmpty())) top--;
            if (rising[top] != null && !rising[top].isEmpty()) key = rising[top].dequeueLong();
            else if (!sunk.isEmpty()) key = sunk.dequeueLong();
            else break;
            looked++;
            node.set(key);
            int height = alongBlock(up, node) - startAlong;
            if (height > best) best = height;
            if (best >= limit) break;
            next.setWithOffset(node, up);
            boolean liquidOver = chunks.fluid(next).isSource();
            if (!liquidOver && skyKnown && height >= best && chunks.openSkyOver(node)) break;
            if (liquidOver && seen.add(next.asLong())) {
                int h = height + 1;
                if (h >= 0) {
                    bucket(rising, h).enqueue(next.asLong());
                    if (h > top) top = h;
                } else {
                    sunk.enqueueFirst(next.asLong());
                }
            }
            for (Direction side : sides) {
                next.setWithOffset(node, side);
                if (sideways(up, next.getX() - start.getX(), next.getY() - start.getY(), next.getZ() - start.getZ()) > SEARCH_RADIUS) continue;
                if (seen.contains(next.asLong()) || !chunks.fluid(next).isSource()) continue;
                seen.add(next.asLong());
                if (height >= 0) bucket(rising, height).enqueue(next.asLong());
                else sunk.enqueue(next.asLong());
            }
            next.setWithOffset(node, down);
            // down only a little way: round the lip of a wall or a dome, not to the sea floor
            if (height > -8 && !seen.contains(next.asLong()) && chunks.fluid(next).isSource()) {
                seen.add(next.asLong());
                int h = height - 1;
                if (h >= 0) bucket(rising, h).enqueue(next.asLong());
                else sunk.enqueue(next.asLong());
            }
        }
        return startAlong + best + 1.0;
    }

    private static LongArrayFIFOQueue bucket(LongArrayFIFOQueue[] rising, int height) {
        LongArrayFIFOQueue queue = rising[height];
        if (queue == null) rising[height] = queue = new LongArrayFIFOQueue();
        return queue;
    }

    private static int alongBlock(Direction up, BlockPos pos) {
        return up.getAxis().choose(pos.getX(), pos.getY(), pos.getZ()) * up.getAxisDirection().getStep();
    }

    private static double along(Direction up, double x, double y, double z) {
        return up.getAxis().choose(x, y, z) * up.getAxisDirection().getStep();
    }

    /** The larger of the two parts of a step that lie across {@code up}. */
    private static double sideways(Direction up, double dx, double dy, double dz) {
        return switch (up.getAxis()) {
            case X -> Math.max(Math.abs(dy), Math.abs(dz));
            case Y -> Math.max(Math.abs(dx), Math.abs(dz));
            case Z -> Math.max(Math.abs(dx), Math.abs(dy));
        };
    }

    /** Block looks through loaded chunks only, the last chunk kept. */
    private static final class Chunks {
        private final Level level;
        private LevelChunk last;
        private int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;

        Chunks(Level level) {
            this.level = level;
        }

        private LevelChunk chunk(int chunkX, int chunkZ) {
            if (chunkX != lastX || chunkZ != lastZ) {
                lastX = chunkX;
                lastZ = chunkZ;
                last = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            }
            return last;
        }

        FluidState fluid(BlockPos pos) {
            blockLooks++;
            if (level.isOutsideBuildHeight(pos)) return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
            LevelChunk chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState() : chunk.getFluidState(pos);
        }

        /** Whether nothing but air lies over this block (the chunk's height map: its highest block that is not air). */
        boolean openSkyOver(BlockPos pos) {
            LevelChunk chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk != null && chunk.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX() & 15, pos.getZ() & 15) <= pos.getY();
        }
    }
}
