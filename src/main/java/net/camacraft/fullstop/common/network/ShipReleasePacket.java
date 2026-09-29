package net.camacraft.fullstop.common.network;

import net.camacraft.fullstop.server.physics.ship.ShipDeckMomentum;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: the player's own client let go of this ship (see {@code ClientShipRelease}):
 * it jumped or fell off the deck, or a hull struck it, and until it lands again that ship doesn't
 * carry it. {@code NO_ID} once it has landed. Sent when it changes. The server's view of who
 * rides a deck comes from Valkyrien Skies' own reports and runs a few ticks behind, and a deck's
 * jolt then still threw a player already in the air.
 *
 * @param shipId the ship let go of, or {@code ShipRef.NO_ID}
 */
public record ShipReleasePacket(long shipId) {

    public ShipReleasePacket(FriendlyByteBuf buffer) {
        this(buffer.readLong());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeLong(shipId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) ShipDeckMomentum.clientReleased(player, shipId);
        ctx.get().setPacketHandled(true);
    }
}
