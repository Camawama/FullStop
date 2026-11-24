package net.camacraft.fullstop.common.physics.rules;

import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a block broken by a body drops ({@code impactDropsFollowTools}). A body is no tool: a fall, a ram, a crater, a
 * falling stalactite, a ship's or a contraption's crash breaks a block as a bare hand would mine it, so a block whose
 * drops need the right tool ({@code BlockState.requiresCorrectToolForDrops}: stone, ores, DimDoors' Unravelled Fabric)
 * drops nothing, as a fist leaves it nothing. The rest drop what their loot table gives with no tool, asked as the game
 * asks it ({@code Level.destroyBlock} with drops: {@code Block.dropResources} with the body as the entity and an empty
 * tool, so silk touch, fortune and shears conditions fail as for a fist). Whether a block breaks is unchanged.
 *
 * <p>Before, every broken block dropped what its loot table gives without a tool, the tool requirement unasked (it is
 * not in the loot table: the game asks it of a player mining, {@code Player.hasCorrectToolForDrops}), so a landing on
 * Limbo's Unravelled Fabric, which needs a pickaxe, dropped the block.</p>
 */
public final class ImpactDrops {

    private ImpactDrops() {
    }

    /** Whether {@code state} broken by a body drops anything; {@code drops} is what the caller would otherwise do. */
    public static boolean drops(BlockState state, boolean drops) {
        if (!drops) return false;
        if (!state.requiresCorrectToolForDrops()) return true;
        return FullStopConfig.SERVER_SPEC.isLoaded() && !FullStopConfig.SERVER.impactDropsFollowTools.get();
    }
}
