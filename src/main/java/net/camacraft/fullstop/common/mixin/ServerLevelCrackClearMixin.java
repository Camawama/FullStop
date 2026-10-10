package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.server.physics.interaction.KineticBlockInteractions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A block that changes takes FullStop's cracks with it: the server level hears every block change here (a player's
 * break, an explosion, a piston, a command, another mod's {@code setBlock}; ship blocks live in the same level), and a
 * position with a crack record or overlay is cleared ({@link KineticBlockInteractions#onBlockChanged}). The owner: "the
 * lingering block cracks ... sometimes remain on the block even after it's broken and then placed back down".
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelCrackClearMixin {

    @Inject(method = "onBlockStateChange", at = @At("HEAD"))
    private void fullstop$cracksGoWithTheBlock(BlockPos pos, BlockState before, BlockState after, CallbackInfo ci) {
        KineticBlockInteractions.onBlockChanged((ServerLevel) (Object) this, pos, before, after);
    }
}
