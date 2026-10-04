package net.camacraft.fullstop.client.mixin;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server set the local player's position (a teleport of any kind, a respawn, a correction): the player's own
 * client keeps a FullStop speed history too (its landings on slime are judged here), and the jump is no motion.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientTeleportMixin {

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void fullstop$teleported(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        // the packet is handled on the game thread (the network thread only hands it over)
        if (!mc.isSameThread() || mc.player == null) return;
        FullStopCapability cap = FullStopCapability.grabCapability(mc.player);
        if (cap != null) cap.markJump();
    }
}
