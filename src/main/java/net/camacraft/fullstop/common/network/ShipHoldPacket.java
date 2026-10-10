package net.camacraft.fullstop.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → client, right before the position the server sends back: Valkyrien Skies' server refused this player's
 * move because it touches a ship not yet loaded there (a ship made a moment ago, "moved while colliding with unloaded
 * ships!"), and the position that follows only puts it back where the server has it. It isn't a stop: the client
 * keeps the motion it had and holds still until Valkyrien Skies lets go ({@code ClientShipHold}). Without it the
 * correction took a bounce that had just begun on a ship's slime (vanilla zeroes a player's motion on a position from
 * the server) and left the player standing where it touched.
 */
public record ShipHoldPacket() {

    public ShipHoldPacket(FriendlyByteBuf buffer) {
        this();
    }

    public void encode(FriendlyByteBuf buffer) {
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.camacraft.fullstop.client.physics.ClientShipHold.heldByServer());
        ctx.get().setPacketHandled(true);
    }
}
