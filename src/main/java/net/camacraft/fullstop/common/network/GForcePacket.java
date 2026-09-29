package net.camacraft.fullstop.common.network;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → client: the g-force the server felt for a player seated on a Valkyrien Skies ship
 * (its seat's acceleration from the ship's physics frames; see
 * {@code FullStopCapability#setSeatAcceleration}), each tick while it sits there. The client's
 * vignette, blackout and muffle read it instead of the client's own reading, which only sees VS
 * setting the seated player's position from the ship each tick.
 *
 * @param avg the smoothed felt acceleration (m/s per tick above 1 g), clarity/vertigo applied
 * @param raw the same without clarity/vertigo (the FOV squeeze)
 */
public record GForcePacket(double avg, double raw) {

    /** How long (ticks) one reading stays in force on the client. */
    private static final int HOLD_TICKS = 5;

    public GForcePacket(FriendlyByteBuf buffer) {
        this(buffer.readDouble(), buffer.readDouble());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeDouble(avg);
        buffer.writeDouble(raw);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> Client.apply(avg, raw));
        ctx.get().setPacketHandled(true);
    }

    private static final class Client {
        static void apply(double avg, double raw) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null || !Double.isFinite(avg) || !Double.isFinite(raw)) return;
            FullStopCapability cap = FullStopCapability.grabCapability(minecraft.player);
            if (cap != null) cap.setRemoteGForce(avg, raw, minecraft.level.getGameTime(), HOLD_TICKS);
        }
    }
}
