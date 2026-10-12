package net.camacraft.fullstop.server.physics.interaction;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * FullStop's mass breaking, made cheap for the clients and the server without changing what breaks or what drops.
 *
 * <p><b>Sounds and dust ({@code breakSoundsPerTick}, {@code breakDustPerTick}).</b> A ship ploughing a meadow or a fast
 * contraption carving a hillside breaks up to {@code sweepMaxBlocksPerTick} plus {@code terrainMaxBlocksPerTick} blocks
 * a tick (88 by default), and vanilla's {@code Level.destroyBlock} sends every one its break effect (level event 2001:
 * the block's break sound and 30 to 64 dust particles on every client in range). Twenty plants a tick is 400 break
 * sounds and 20,000 particles a second. The particles cost frames; the sounds froze the game: every sound start waits
 * on the sound thread ({@code SoundEngine.play} joins it), and with a sound mod tracing rays for each one the render
 * thread sat there for half a second to a second at a time while a netherite arm carved stone (pack harness
 * {@code perf_crush}: 919 ms frames). Now per level and tick the first {@code breakSoundsPerTick} breaks at least
 * {@link #SPREAD} blocks apart play the vanilla effect, the next {@code breakDustPerTick} throw a lighter puff of the
 * block's dust without a sound, and the rest break quietly ({@code LevelDestroyBlockEffectMixin}).</p>
 *
 * <p><b>Drops ({@code mergeBreakDrops}).</b> Every plant a ship mows drops its own item a block from the next, too far
 * apart for vanilla to merge them, so a meadow left thousands of item entities, each ticking for five minutes (the
 * harness: about 900 after a 15-block-wide ship crossed 5,000 plants; items were a third of the server thread after).
 * Mass breaks' drops are gathered over the tick and land merged: one stack per kind of item per {@link #DROP_CELL}-block
 * cell, topped up into the stack an earlier tick's breaks left in that cell while it still lies there. The same items,
 * in far fewer entities.</p>
 *
 * <p>A player breaking a block, and FullStop's single impacts, are untouched: only breaks made through
 * {@link #destroy} count.</p>
 */
public final class BreakEffects {

    /** Two breaks with their effect this close (blocks) in one tick: the second has none (it would be the same dust). */
    static final double SPREAD = 1.5;
    /** Drops of mass breaks merge within cells this many blocks wide. */
    static final int DROP_CELL = 3;
    /** A stack mass breaks left takes later drops of its cell for this long (ticks), while it lies in the cell. */
    private static final int CARRIER_TICKS = 200;
    /** Dust particles of a break that shows only its dust. */
    private static final int DUST_PARTICLES = 12;
    private static final int MAX_REMEMBERED = 128;

    private enum Effect { FULL, DUST, NONE }

    private static final Map<ServerLevel, Tick> TICKS = new WeakHashMap<>();
    /** The items dropped by this tick's mass breaks, per level, not yet in the world. */
    private static final Map<ServerLevel, List<ItemEntity>> DROPS = new WeakHashMap<>();
    /** Per level, per drop cell, the stacks mass breaks left there lately. */
    private static final Map<ServerLevel, Long2ObjectOpenHashMap<List<Carrier>>> CARRIERS = new WeakHashMap<>();
    /** Set while a break must skip its vanilla effect (server thread only; read by {@code LevelDestroyBlockEffectMixin}). */
    private static boolean quiet;
    /** Set while a mass break runs: the items it drops are gathered (server thread only). */
    private static boolean gathering;

    private record Carrier(ItemEntity item, long since) {
    }

    private static final class Tick {
        long time = Long.MIN_VALUE;
        int full;
        int dust;
        int remembered;
        final long[] at = new long[MAX_REMEMBERED];
    }

    private BreakEffects() {
    }

    /** Whether the block being destroyed right now leaves out its vanilla break effect. */
    public static boolean quiet() {
        return quiet;
    }

    /**
     * {@code level.destroyBlock(pos, drops, cause)} with its effect rationed (see the class) and its drops merged with
     * the tick's others. Returns what destroyBlock returns.
     */
    public static boolean destroy(ServerLevel level, BlockPos pos, boolean drops, @Nullable Entity cause) {
        BlockState state = level.getBlockState(pos);
        Effect effect = state.isAir() ? Effect.NONE : effect(level, pos);
        boolean wasQuiet = quiet, wasGathering = gathering;
        quiet = effect != Effect.FULL;
        gathering = drops && SERVER.mergeBreakDrops.get();
        boolean broken;
        try {
            broken = level.destroyBlock(pos, drops, cause);
        } finally {
            quiet = wasQuiet;
            gathering = wasGathering;
        }
        if (broken && effect == Effect.DUST) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state).setPos(pos), pos.getX() + 0.5,
                    pos.getY() + 0.5, pos.getZ() + 0.5, DUST_PARTICLES, 0.25, 0.25, 0.25, 0.05);
        }
        return broken;
    }

    /** Which effect a break at {@code pos} gets, counting it. */
    private static Effect effect(ServerLevel level, BlockPos pos) {
        int sounds = SERVER.breakSoundsPerTick.get();
        int dust = SERVER.breakDustPerTick.get();
        Tick tick = TICKS.computeIfAbsent(level, l -> new Tick());
        long now = level.getGameTime();
        if (tick.time != now) {
            tick.time = now;
            tick.full = 0;
            tick.dust = 0;
            tick.remembered = 0;
        }
        boolean fullLeft = tick.full < sounds, dustLeft = tick.dust < dust;
        if (!fullLeft && !dustLeft) return Effect.NONE;
        double spread2 = SPREAD * SPREAD;
        for (int i = 0; i < tick.remembered; i++) {
            long other = tick.at[i];
            double dx = BlockPos.getX(other) - pos.getX(), dy = BlockPos.getY(other) - pos.getY(), dz = BlockPos.getZ(other) - pos.getZ();
            if (dx * dx + dy * dy + dz * dz < spread2) return Effect.NONE;
        }
        if (tick.remembered < MAX_REMEMBERED) tick.at[tick.remembered++] = pos.asLong();
        if (fullLeft) {
            tick.full++;
            return Effect.FULL;
        }
        tick.dust++;
        return Effect.DUST;
    }

    /** An item a mass break drops is held back for this tick's merge (and seen by other mods when it joins merged). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!gathering || event.loadedFromDisk() || !(event.getEntity() instanceof ItemEntity item)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        DROPS.computeIfAbsent(level, l -> new ArrayList<>()).add(item);
        event.setCanceled(true);
    }

    /** The end of the level tick, after every ship and contraption has broken what it breaks: the merged drops land. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        Long2ObjectOpenHashMap<List<Carrier>> cells = CARRIERS.get(level);
        if (cells != null && now % 100L == 0L) forgetOld(cells, now);
        List<ItemEntity> items = DROPS.remove(level);
        if (items == null || items.isEmpty()) return;
        if (cells == null) CARRIERS.put(level, cells = new Long2ObjectOpenHashMap<>());
        for (ItemEntity item : items) {
            ItemStack stack = item.getItem();
            if (stack.isEmpty()) continue;
            long cell = cellOf(item);
            List<Carrier> carriers = cells.computeIfAbsent(cell, c -> new ArrayList<>());
            for (Carrier carrier : carriers) {
                ItemEntity into = carrier.item();
                // an earlier tick's stack only while it still lies in this cell and nobody took it
                if (now - carrier.since() > CARRIER_TICKS || !into.isAlive() || (into.isAddedToWorld() && cellOf(into) != cell)) continue;
                ItemStack held = into.getItem();
                if (!ItemEntity.areMergable(held, stack)) continue;
                ItemStack grown = held.copy();
                grown.grow(stack.getCount());
                into.setItem(grown);
                stack = ItemStack.EMPTY;
                break;
            }
            if (!stack.isEmpty()) {
                carriers.add(new Carrier(item, now));
                level.addFreshEntity(item);
            }
        }
    }

    private static long cellOf(ItemEntity item) {
        return BlockPos.asLong(Math.floorDiv((int) Math.floor(item.getX()), DROP_CELL),
                Math.floorDiv((int) Math.floor(item.getY()), DROP_CELL), Math.floorDiv((int) Math.floor(item.getZ()), DROP_CELL));
    }

    private static void forgetOld(Long2ObjectOpenHashMap<List<Carrier>> cells, long now) {
        Iterator<Long2ObjectOpenHashMap.Entry<List<Carrier>>> it = cells.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            List<Carrier> carriers = it.next().getValue();
            carriers.removeIf(c -> now - c.since() > CARRIER_TICKS || !c.item().isAlive());
            if (carriers.isEmpty()) it.remove();
        }
    }
}
