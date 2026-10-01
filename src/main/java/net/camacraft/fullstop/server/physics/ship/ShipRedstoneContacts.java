package net.camacraft.fullstop.server.physics.ship;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.common.mixin.ShipButtonAccessor;
import net.camacraft.fullstop.common.mixin.ShipPlateInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Ships work redstone ({@code shipRedstone}): a hull touching a button presses it and holds it
 * down while it stays (an arrow does the same to a wooden one), and a hull resting on a pressure
 * plate holds it down - any plate, a stone one included: a ship is heavy - a weighted plate by the
 * ship's mass ({@code shipWeightedPlateKgPerSignal} kilograms per level on a gold plate, a tonne: VS's
 * oak plank weighs 500 kg; ten times that on an iron one, as vanilla's fifteen and a hundred and fifty entities go). Vanilla
 * only counts entities in a plate's touch box and arrows in a button's, and a ship is neither.
 *
 * <p>Every ten ticks (sooner when the hull has left the region it last looked at) each loaded ship
 * lists the buttons and plates in the world within a block of its bounds (chunk sections whose
 * palette holds none are skipped whole; the shipyard's chunks are never looked at). Each tick the
 * hull is tested against each one - the button's own shape, a plate's touch box (the lowest
 * quarter of its block) - with Camafy's exact oriented-box test. A button the hull reaches is
 * pressed (the click sounds) and the block's own re-checks keep it down while the hull stays
 * ({@link net.camacraft.fullstop.common.mixin.ShipButtonHoldMixin}); a plate the hull rests on
 * counts the ship's mass into its signal
 * ({@link net.camacraft.fullstop.common.mixin.ShipPressurePlateMixin},
 * {@link net.camacraft.fullstop.common.mixin.ShipWeightedPlateMixin}), checked at once and then by
 * the plate's own ticks. When the hull leaves, the block is ticked a tick later and lets go as it
 * would for an entity.</p>
 */
public final class ShipRedstoneContacts {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final int RESCAN_TICKS = 10;
    /** How far past its own shape a button is felt (VS keeps a hull a hair off the wall it presses against). */
    private static final double BUTTON_TOLERANCE = 0.05;
    /** Most world cells read per scan of one ship's surroundings. */
    private static final int MAX_SCAN_READS = 4096;
    /** A pressure plate's touch box within its block (vanilla's {@code BasePressurePlateBlock.TOUCH_AABB}). */
    private static final AABB PLATE_TOUCH = new AABB(0.0625, 0.0, 0.0625, 0.9375, 0.25, 0.9375);

    /** What one ship keeps: the buttons and plates near it, and which it holds. */
    private static final class Watch {
        long scanned = Long.MIN_VALUE / 2;
        @Nullable
        AABB scannedRegion;
        final List<BlockPos> buttons = new ArrayList<>();
        final List<BlockPos> plates = new ArrayList<>();
        final Set<BlockPos> heldButtons = new HashSet<>();
        /** The plates this hull rests on, and the mass (kg) it last counted into each. */
        final Object2DoubleOpenHashMap<BlockPos> heldPlates = new Object2DoubleOpenHashMap<>();
    }

    private static final Map<ServerLevel, Long2ObjectOpenHashMap<Watch>> WATCHES = new WeakHashMap<>();
    /** Per level: how many hulls hold each button, and the mass (kg) of the hulls resting on each plate. */
    private static final Map<ServerLevel, Object2IntOpenHashMap<BlockPos>> HELD_BUTTONS = new WeakHashMap<>();
    private static final Map<ServerLevel, Object2DoubleOpenHashMap<BlockPos>> PLATE_MASS = new WeakHashMap<>();

    private ShipRedstoneContacts() {
    }

    // ------------------------------------------------------------------
    // For the block mixins
    // ------------------------------------------------------------------

    /** Whether a hull holds the button at {@code pos} down. */
    public static boolean holdsButton(ServerLevel level, BlockPos pos) {
        Object2IntOpenHashMap<BlockPos> held = HELD_BUTTONS.get(level);
        return held != null && held.getInt(pos) > 0;
    }

    /** The mass (kg) of the hulls resting on the plate at {@code pos}; 0 for none. */
    public static double plateMassKg(ServerLevel level, BlockPos pos) {
        Object2DoubleOpenHashMap<BlockPos> mass = PLATE_MASS.get(level);
        return mass == null ? 0.0 : mass.getDouble(pos);
    }

    /**
     * The signal the hulls resting on a weighted plate give it: one level per
     * {@code shipWeightedPlateKgPerSignal} kilograms on a plate that takes {@code maxWeight} 15 entities (gold),
     * ten times as much per level at 150 (iron), at least 1 while anything rests on it, at most 15.
     */
    public static int weightedSignal(ServerLevel level, BlockPos pos, int maxWeight) {
        double mass = plateMassKg(level, pos);
        if (!(mass > 0.0)) return 0;
        double kgPerLevel = SERVER.shipWeightedPlateKgPerSignal.get() * Math.max(1, maxWeight) / 15.0;
        return Mth.clamp((int) Math.ceil(mass / kgPerLevel), 1, 15);
    }

    // ------------------------------------------------------------------
    // The ship tick
    // ------------------------------------------------------------------

    static void process(ServerLevel level, ShipRef ship, ShipTracker.State state, AABB bounds, long now) {
        Long2ObjectOpenHashMap<Watch> watches = WATCHES.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        if (!SERVER.shipRedstone.get()) {
            Watch gone = watches.remove(ship.id());
            if (gone != null) releaseAll(level, gone);
            return;
        }
        Watch w = watches.get(ship.id());
        if (w == null) watches.put(ship.id(), w = new Watch());
        if (now - w.scanned >= RESCAN_TICKS || w.scannedRegion == null || !contains(w.scannedRegion, bounds)) {
            scan(level, w, bounds, now);
        }
        double mass = state.mass();
        if (!Double.isFinite(mass) || mass < 0.0) mass = 0.0;

        Object2IntOpenHashMap<BlockPos> heldButtons = HELD_BUTTONS.computeIfAbsent(level, l -> new Object2IntOpenHashMap<>());
        for (Iterator<BlockPos> it = w.buttons.iterator(); it.hasNext(); ) {
            BlockPos pos = it.next();
            BlockState s = ShipTerrainImpacts.loadedState(level, pos);
            if (s == null || !(s.getBlock() instanceof ButtonBlock button)) {
                it.remove();
                if (w.heldButtons.remove(pos)) letGoButton(level, heldButtons, pos, s);
                continue;
            }
            AABB box = s.getShape(level, pos).bounds().move(pos).inflate(BUTTON_TOLERANCE);
            boolean touching = ship.touchesBlock(box);
            boolean held = w.heldButtons.contains(pos);
            if (touching && !held) {
                w.heldButtons.add(pos);
                heldButtons.addTo(pos, 1);
                if (!s.getValue(ButtonBlock.POWERED)) {
                    button.press(s, level, pos);
                    level.playSound(null, pos, ((ShipButtonAccessor) button).fullstop$type().buttonClickOn(), SoundSource.BLOCKS);
                    level.gameEvent(null, GameEvent.BLOCK_ACTIVATE, pos);
                    if (SERVER.debugShipCollisionLog.get()) {
                        LOGGER.info("[FullStop] ship {} pressed the button at {}", ship.id(), pos.toShortString());
                    }
                }
            } else if (!touching && held) {
                w.heldButtons.remove(pos);
                letGoButton(level, heldButtons, pos, s);
            }
        }

        Object2DoubleOpenHashMap<BlockPos> plateMass = PLATE_MASS.computeIfAbsent(level, l -> new Object2DoubleOpenHashMap<>());
        for (Iterator<BlockPos> it = w.plates.iterator(); it.hasNext(); ) {
            BlockPos pos = it.next();
            BlockState s = ShipTerrainImpacts.loadedState(level, pos);
            if (s == null || !(s.getBlock() instanceof BasePressurePlateBlock plate)) {
                it.remove();
                if (w.heldPlates.containsKey(pos)) letGoPlate(level, plateMass, w.heldPlates.removeDouble(pos), pos, s);
                continue;
            }
            boolean touching = ship.touchesBlock(PLATE_TOUCH.move(pos));
            boolean held = w.heldPlates.containsKey(pos);
            if (touching) {
                double had = held ? w.heldPlates.getDouble(pos) : 0.0;
                w.heldPlates.put(pos, mass);
                add(plateMass, pos, mass - had);
                if (!held) {
                    // pressed at once, as an entity stepping on presses it; the plate's own ticks re-check it after
                    ((ShipPlateInvoker) plate).fullstop$checkPressed(null, level, pos, s, s.getSignal(level, pos, Direction.UP));
                    if (SERVER.debugShipCollisionLog.get()) {
                        LOGGER.info("[FullStop] ship {} ({} kg) rests on the pressure plate at {}", ship.id(), Math.round(mass), pos.toShortString());
                    }
                }
            } else if (held) {
                letGoPlate(level, plateMass, w.heldPlates.removeDouble(pos), pos, s);
            }
        }
    }

    private static boolean contains(AABB region, AABB bounds) {
        return bounds.minX >= region.minX && bounds.maxX <= region.maxX && bounds.minY >= region.minY && bounds.maxY <= region.maxY
                && bounds.minZ >= region.minZ && bounds.maxZ <= region.maxZ;
    }

    private static void add(Object2DoubleOpenHashMap<BlockPos> map, BlockPos pos, double delta) {
        double now = map.getDouble(pos) + delta;
        if (now > 1.0e-6) map.put(pos, now);
        else map.removeDouble(pos);
    }

    private static void letGoButton(ServerLevel level, Object2IntOpenHashMap<BlockPos> heldButtons, BlockPos pos, @Nullable BlockState s) {
        int left = heldButtons.addTo(pos, -1) - 1;
        if (left <= 0) {
            heldButtons.removeInt(pos);
            // the block's own check a tick later lets it go (its click-off sound), as an arrow pulled out would
            if (s != null && s.getBlock() instanceof ButtonBlock button) level.scheduleTick(pos, button, 1);
        }
    }

    private static void letGoPlate(ServerLevel level, Object2DoubleOpenHashMap<BlockPos> plateMass, double had, BlockPos pos, @Nullable BlockState s) {
        add(plateMass, pos, -had);
        if (s != null && s.getBlock() instanceof BasePressurePlateBlock plate) level.scheduleTick(pos, plate, 1);
    }

    private static void releaseAll(ServerLevel level, Watch w) {
        Object2IntOpenHashMap<BlockPos> heldButtons = HELD_BUTTONS.get(level);
        if (heldButtons != null) {
            for (BlockPos pos : w.heldButtons) letGoButton(level, heldButtons, pos, ShipTerrainImpacts.loadedState(level, pos));
        }
        w.heldButtons.clear();
        Object2DoubleOpenHashMap<BlockPos> plateMass = PLATE_MASS.get(level);
        if (plateMass != null) {
            for (Object2DoubleOpenHashMap.Entry<BlockPos> e : w.heldPlates.object2DoubleEntrySet()) {
                letGoPlate(level, plateMass, e.getDoubleValue(), e.getKey(), ShipTerrainImpacts.loadedState(level, e.getKey()));
            }
        }
        w.heldPlates.clear();
    }

    /**
     * The buttons and plates within a block of the hull's bounds, from loaded chunks whose sections hold any
     * (a palette check per section); what the hull already holds stays listed however far it has moved.
     */
    private static void scan(ServerLevel level, Watch w, AABB bounds, long now) {
        w.scanned = now;
        w.scannedRegion = bounds.inflate(2.0);
        w.buttons.clear();
        w.plates.clear();
        AABB region = bounds.inflate(1.0);
        int x0 = Mth.floor(region.minX), x1 = Mth.floor(region.maxX);
        int y0 = Math.max(Mth.floor(region.minY), level.getMinBuildHeight());
        int y1 = Math.min(Mth.floor(region.maxY), level.getMaxBuildHeight() - 1);
        int z0 = Mth.floor(region.minZ), z1 = Mth.floor(region.maxZ);
        int reads = 0;
        LongOpenHashSet listed = new LongOpenHashSet();
        scan:
        for (int cx = x0 >> 4; cx <= x1 >> 4 && y0 <= y1; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null || Ships.chunkInShipyard(level, cx, cz)) continue;
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    int index = chunk.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= chunk.getSectionsCount()) continue;
                    LevelChunkSection section = chunk.getSection(index);
                    if (section.hasOnlyAir() || !section.maybeHas(ShipRedstoneContacts::redstoneBlock)) continue;
                    int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15);
                    int ay = Math.max(y0, sy << 4), by = Math.min(y1, (sy << 4) + 15);
                    int az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                    for (int x = ax; x <= bx; x++) {
                        for (int y = ay; y <= by; y++) {
                            for (int z = az; z <= bz; z++) {
                                if (++reads > MAX_SCAN_READS) break scan;
                                BlockState s = section.getBlockState(x & 15, y & 15, z & 15);
                                if (s.getBlock() instanceof ButtonBlock) {
                                    BlockPos pos = new BlockPos(x, y, z);
                                    if (listed.add(pos.asLong())) w.buttons.add(pos);
                                } else if (s.getBlock() instanceof BasePressurePlateBlock) {
                                    BlockPos pos = new BlockPos(x, y, z);
                                    if (listed.add(pos.asLong())) w.plates.add(pos);
                                }
                            }
                        }
                    }
                }
            }
        }
        for (BlockPos pos : w.heldButtons) {
            if (listed.add(pos.asLong())) w.buttons.add(pos);
        }
        for (BlockPos pos : w.heldPlates.keySet()) {
            if (listed.add(pos.asLong())) w.plates.add(pos);
        }
    }

    private static boolean redstoneBlock(BlockState s) {
        return s.getBlock() instanceof ButtonBlock || s.getBlock() instanceof BasePressurePlateBlock;
    }

    /** Lets go of everything the ships no longer loaded held. */
    static void prune(ServerLevel level, List<ShipRef> loaded) {
        Long2ObjectOpenHashMap<Watch> watches = WATCHES.get(level);
        if (watches == null || watches.isEmpty()) return;
        LongOpenHashSet ids = new LongOpenHashSet(loaded.size());
        for (ShipRef ship : loaded) ids.add(ship.id());
        for (Iterator<Long2ObjectOpenHashMap.Entry<Watch>> it = watches.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectOpenHashMap.Entry<Watch> entry = it.next();
            if (ids.contains(entry.getLongKey())) continue;
            releaseAll(level, entry.getValue());
            it.remove();
        }
    }
}
