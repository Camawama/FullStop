package net.camacraft.fullstop.common.compat.create.contraption;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllPackets;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ContraptionBlockChangedPacket;
import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.apache.commons.lang3.tuple.MutablePair;
import org.slf4j.Logger;

import java.util.List;

/**
 * Knocking a block off a moving contraption ({@code contraptionBlocksBreak}): the way Create itself changes a
 * block of a contraption that is moving (a door on it opening, a redstone contact switching), so every client
 * redraws it. The block leaves the contraption's own block list (it isn't saved, and isn't placed when the
 * contraption is disassembled), the clients are sent Create's {@code ContraptionBlockChangedPacket} with air
 * there (Create's client handler swaps the block, rebuilds the drawn contraption and its colliders), and the
 * contraption's collision shape for bodies is rebuilt on the server too. Its drops fly off with the speed it had.
 *
 * <p>Only plain blocks come off: never the block the contraption hangs from (its anchor), a block with a block
 * entity (chests and other storage, drills, saws, deployers, bogeys: their contents and behaviours live with
 * the contraption), a seat, a control or interactable block, or an actor - and never the last block.
 */
final class ContraptionBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ContraptionBlocks() {
    }

    /** Whether the block at {@code local} may be knocked off this contraption. */
    static boolean canKnockOff(Contraption contraption, BlockPos local, StructureBlockInfo info) {
        if (local.equals(BlockPos.ZERO)) return false;
        if (info.nbt() != null || info.state().hasBlockEntity()) return false;
        if (contraption.getBlocks().size() <= 1) return false;
        if (contraption.getSeats().contains(local)) return false;
        if (contraption.getInteractors().containsKey(local)) return false;
        for (MutablePair<StructureBlockInfo, ?> actor : contraption.getActors()) {
            if (actor.getLeft() != null && local.equals(actor.getLeft().pos())) return false;
        }
        return true;
    }

    /**
     * Takes the block at {@code local} off the contraption and drops it at {@code worldCenter} with the
     * contraption's velocity there ({@code velocityMps}, m/s). Returns whether it came off.
     */
    static boolean knockOff(ServerLevel level, AbstractContraptionEntity c, Contraption contraption, BlockPos local,
                            StructureBlockInfo info, Vec3 worldCenter, Vec3 velocityMps) {
        if (!takeOff(c, contraption, local)) return false;
        drop(level, info.state(), worldCenter, velocityMps);
        return true;
    }

    /** Takes the block at {@code local} off the contraption, on every client too, and leaves nothing of it (TNT gone off). */
    static boolean takeOff(AbstractContraptionEntity c, Contraption contraption, BlockPos local) {
        if (contraption.getBlocks().remove(local) == null) return false;
        try {
            AllPackets.getChannel().send(PacketDistributor.TRACKING_ENTITY.with(() -> c),
                    new ContraptionBlockChangedPacket(c.getId(), local, Blocks.AIR.defaultBlockState()));
        } catch (RuntimeException e) {
            LOGGER.warn("[FullStop] couldn't tell the clients contraption {} lost a block: {}", c.getId(), e.toString());
        }
        contraption.invalidateColliders();
        return true;
    }

    /** The knocked-off block's drops, flying on with the contraption's speed there (a little less), and its break sound and dust. */
    private static void drop(ServerLevel level, BlockState state, Vec3 at, Vec3 velocityMps) {
        BlockPos cell = BlockPos.containing(at);
        // the break sound and the block's dust where it came off (a level event, as a mined block plays it)
        level.levelEvent(2001, cell, Block.getId(state));
        if (!FullStopConfig.SERVER.terrainBlockDrops.get() || !level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) return;
        List<ItemStack> drops = Block.getDrops(state, level, cell, null);
        Vec3 fly = velocityMps.scale(0.05 * 0.6);
        for (ItemStack stack : drops) {
            if (stack.isEmpty()) continue;
            ItemEntity item = new ItemEntity(level, at.x, at.y, at.z, stack);
            item.setDeltaMovement(fly.add((level.random.nextDouble() - 0.5) * 0.1, level.random.nextDouble() * 0.1,
                    (level.random.nextDouble() - 0.5) * 0.1));
            item.setDefaultPickUpDelay();
            level.addFreshEntity(item);
        }
    }
}
