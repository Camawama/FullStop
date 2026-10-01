package net.camacraft.fullstop.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → client: the continuous shock train of a supersonic source (a Valkyrien Skies rotor
 * whose tips are past the speed of sound: {@code SonicBooms}, {@code ShipSonicBooms}) starts,
 * goes on (sent again every so often, and whenever its rate or the tips' speed changes) or stops
 * for this listener. The client plays one crack per shock, at that cadence, following the ship
 * ({@code client.sound.ShockTrainSounds}).
 *
 * @param sourceId        the source: a ship's id when {@code ship}, else an entity's
 * @param active          on (start or update) or off
 * @param pulsesPerSecond how many shocks a second sweep the listener (turns a second times supersonic tips)
 * @param loudness        the cracks' gain, 0..1 (their range is the sound's own, 192 blocks: {@code SonicBooms.TRAIN_RANGE})
 * @param mach            the fastest tip's speed over the speed of sound (1 at the barrier): the cracks' pitch and bite
 */
public record ShockTrainPacket(long sourceId, boolean ship, boolean active, float pulsesPerSecond, double x, double y, double z,
                               float loudness, float mach) {

    public ShockTrainPacket(FriendlyByteBuf buffer) {
        this(buffer.readLong(), buffer.readBoolean(), buffer.readBoolean(), buffer.readFloat(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), buffer.readFloat(), buffer.readFloat());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeLong(sourceId);
        buffer.writeBoolean(ship);
        buffer.writeBoolean(active);
        buffer.writeFloat(pulsesPerSecond);
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeFloat(loudness);
        buffer.writeFloat(mach);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.camacraft.fullstop.client.sound.ShockTrainSounds.on(this));
        ctx.get().setPacketHandled(true);
    }
}
