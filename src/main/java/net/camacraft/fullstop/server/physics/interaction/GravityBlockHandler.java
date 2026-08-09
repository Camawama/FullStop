package net.camacraft.fullstop.server.physics.interaction;

import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.FullStopConfig.SERVER_SPEC;

/**
 * Makes blocks tagged fullstop:gravity_affected (slime and honey by default)
 * fall like sand when nothing holds them up. Blocks also tagged fullstop:sticky
 * count a stationary block on ANY face as support, so slime can still hang off
 * walls and ceilings; non-sticky entries need support from below exactly like
 * sand. Both lists are data-driven (data/fullstop/tags/blocks/).
 *
 * Event-driven rather than a Block override so it works for arbitrary blocks
 * from any mod: placements and neighbor updates queue a support check that runs
 * after the vanilla falling-block delay. A block that falls clears its old
 * position, which notifies its neighbors and re-queues them — columns collapse
 * one block at a time just like sand.
 */
public final class GravityBlockHandler {

    /** Matches vanilla FallingBlock's delay between losing support and falling. */
    private static final int FALL_DELAY_TICKS = 2;

    /** Positions awaiting a support re-check, with remaining delay ticks. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Integer>> PENDING = new HashMap<>();

    private GravityBlockHandler() {
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && enabled()) {
            queueIfUnsupported(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !enabled()) return;

        // The changed block itself (covers placement) plus every neighbor it
        // notified (covers losing support when the block below/beside it went away).
        queueIfUnsupported(level, event.getPos());
        for (Direction direction : event.getNotifiedSides()) {
            queueIfUnsupported(level, event.getPos().relative(direction));
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;

        Map<BlockPos, Integer> pending = PENDING.get(level.dimension());
        if (pending == null || pending.isEmpty()) return;

        // Two-phase: collect due positions first, THEN fall them. fall() calls
        // setBlock, which synchronously fires NeighborNotifyEvent → queueIfUnsupported
        // → a put into this same map; doing that mid-iteration is a
        // ConcurrentModificationException (server crash) the moment a cluster
        // collapse queues a neighbor while its sibling is being processed.
        List<BlockPos> due = new ArrayList<>();
        Iterator<Map.Entry<BlockPos, Integer>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (entry.getValue() > 1) {
                entry.setValue(entry.getValue() - 1);
                continue;
            }
            iterator.remove();
            due.add(entry.getKey());
        }

        for (BlockPos pos : due) {
            if (!level.isLoaded(pos) || pos.getY() < level.getMinBuildHeight()) continue;

            // Re-check: the block may have been broken or re-supported during the delay.
            BlockState state = level.getBlockState(pos);
            if (state.is(FullStopTags.GRAVITY_AFFECTED) && !isSupported(level, pos, state)) {
                FallingBlockEntity.fall(level, pos, state);
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PENDING.remove(level.dimension());
        }
    }

    private static boolean enabled() {
        return SERVER_SPEC.isLoaded() && SERVER.enableGravityBlocks.get();
    }

    /**
     * Mid-fall cling for falling sticky blocks (slime/honey): a sticky block
     * falling PAST a solid block grabs onto it instead of continuing down, the
     * same any-face support rule placed sticky blocks already follow. Called
     * from FallingBlockEntityMixin at the end of every falling-block tick.
     */
    public static void tryStickySideCling(FallingBlockEntity falling) {
        if (!(falling.level() instanceof ServerLevel level) || !enabled()) return;
        // Skip the spawn tick(s): the block just lost ALL support (that's why it
        // fell), so nothing is there to cling to yet — and the check is cheap
        // only once genuinely falling.
        if (falling.time <= 2 || falling.onGround() || !falling.isAlive()) return;
        if (falling.getDeltaMovement().y >= -0.05) return; // only while actually falling

        BlockState state = falling.getBlockState();
        if (!state.is(FullStopTags.STICKY) || !state.is(FullStopTags.GRAVITY_AFFECTED)) return;

        BlockPos pos = falling.blockPosition();
        BlockState occupying = level.getBlockState(pos);
        if (!occupying.isAir() && !occupying.canBeReplaced()) return;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (FallingBlock.isFree(level.getBlockState(pos.relative(direction)))) continue;

            // Something solid beside the fall path: cling there. The placement
            // fires neighbor updates, so the support re-check queue sees it and
            // the block stays exactly as long as its anchor does.
            level.setBlockAndUpdate(pos, state);
            level.playSound(null, pos, state.getSoundType().getPlaceSound(),
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 0.9f);
            falling.discard();
            return;
        }
    }

    private static void queueIfUnsupported(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;

        BlockState state = level.getBlockState(pos);
        if (!state.is(FullStopTags.GRAVITY_AFFECTED)) return;
        if (isSupported(level, pos, state)) return;

        PENDING.computeIfAbsent(level.dimension(), key -> new HashMap<>())
                .putIfAbsent(pos.immutable(), FALL_DELAY_TICKS);
    }

    /** Cluster-search cap; a sticky formation bigger than this is assumed supported. */
    private static final int MAX_CLUSTER_SEARCH = 64;

    private static boolean isSupported(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.is(FullStopTags.STICKY)) {
            // Same floor rule as sand: anything FallingBlock.isFree considers
            // passable (air, fire, fluids, replaceables) is not support.
            return !FallingBlock.isFree(level.getBlockState(pos.below()));
        }

        // Sticky blocks cling to any face — but a cluster of sticky blocks
        // clinging only to EACH OTHER isn't supported (two slime blocks floating
        // side by side used to hold each other up forever). Flood-fill through
        // the sticky cluster looking for an anchor that isn't part of it; other
        // gravity blocks (sand) count as anchors because they fall on their own
        // and re-trigger this check via the neighbor updates their fall emits.
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        visited.add(pos.immutable());
        queue.add(pos.immutable());

        while (!queue.isEmpty()) {
            if (visited.size() > MAX_CLUSTER_SEARCH) return true;
            BlockPos current = queue.poll();
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = current.relative(direction);
                BlockState neighbor = level.getBlockState(neighborPos);
                if (FallingBlock.isFree(neighbor)) continue;
                if (neighbor.is(FullStopTags.STICKY) && neighbor.is(FullStopTags.GRAVITY_AFFECTED)) {
                    BlockPos immutable = neighborPos.immutable();
                    if (visited.add(immutable)) queue.add(immutable);
                } else {
                    return true;
                }
            }
        }
        return false;
    }
}
