package net.camacraft.fullstop.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → client: a sonic boom reaches this listener ({@code SonicBooms}), from where its shock wave came from. The
 * client plays it loud however far it travelled, deeper and low-passed with distance ({@code client.sound.SonicBoomSounds}).
 *
 * @param distance how far (blocks) the wave travelled to the listener
 * @param range    the boom's hearing range (blocks): {@code sonicBoomRange}
 * @param pitch    the crack's pitch (already a little deeper the farther it travelled)
 */
public record SonicBoomPacket(double x, double y, double z, float distance, float range, float pitch, long seed) {

    public SonicBoomPacket(FriendlyByteBuf buffer) {
        this(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat(), buffer.readLong());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeFloat(distance);
        buffer.writeFloat(range);
        buffer.writeFloat(pitch);
        buffer.writeLong(seed);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.camacraft.fullstop.client.sound.SonicBoomSounds.on(this));
        ctx.get().setPacketHandled(true);
    }
}
