package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.compat.ShipHolds;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.network.ShipHoldPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells a player's client when the position the server sends it back is Valkyrien Skies holding it ({@link
 * ShipHoldPacket}): VS's server refuses every move of a player touching a ship whose chunks aren't loaded there yet
 * and sends the player back where it was, from inside {@code handleMovePlayer}. Its check is asked again here, at the
 * moment that position goes out; a position the server sends for any other reason (a command, vanilla's own "moved
 * wrongly") goes out as before. {@code -Dfullstop.shipHoldKeepsMotion=false} turns it off.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerShipHoldMixin {

    @Unique
    private static final boolean fullstop$ENABLED = !"false".equalsIgnoreCase(System.getProperty("fullstop.shipHoldKeepsMotion"));

    @Shadow
    public ServerPlayer player;

    /** Set while the server handles one of this player's moves (a correction sent then answers that move). */
    @Unique
    private boolean fullstop$inMove;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void fullstop$moveBegins(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        fullstop$inMove = true;
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void fullstop$moveEnds(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        fullstop$inMove = false;
    }

    @Inject(method = "teleport(DDDFF)V", at = @At("HEAD"))
    private void fullstop$heldByShip(double x, double y, double z, float yaw, float pitch, CallbackInfo ci) {
        boolean inMove = fullstop$inMove;
        fullstop$inMove = false;
        if (!fullstop$ENABLED || !inMove || player == null || player.level().isClientSide()) return;
        if (ShipHolds.heldForUnloadedShip(player)) PacketHandler.sendToPlayer(player, new ShipHoldPacket());
    }
}
