package net.camacraft.fullstop.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → client: a supersonic source's shock cone ({@code SonicBooms}) is on (sent again every so often and when its
 * Mach number changes) or off, for a listener near enough to see it. The client draws it
 * ({@code client.render.MachCones}): behind the entity, or at each supersonic tip of a ship or contraption.
 *
 * @param sourceId an entity's id, or a ship's when {@code ship}
 * @param mach     the source's fastest point's speed over the speed of sound
 * @param tips     its supersonic tips in its own frame (a ship's shipyard, a contraption's local space), at most 8;
 *                 empty: the cone trails the whole body
 */
public record MachConePacket(long sourceId, boolean ship, boolean active, float mach, List<Vec3> tips) {

    public static final int MAX_TIPS = 8;

    public MachConePacket(FriendlyByteBuf buffer) {
        this(buffer.readLong(), buffer.readBoolean(), buffer.readBoolean(), buffer.readFloat(), readTips(buffer));
    }

    private static List<Vec3> readTips(FriendlyByteBuf buffer) {
        int n = Math.min(buffer.readVarInt(), MAX_TIPS);
        List<Vec3> tips = new ArrayList<>(n);
        for (int i = 0; i < n; i++) tips.add(new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
        return tips;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeLong(sourceId);
        buffer.writeBoolean(ship);
        buffer.writeBoolean(active);
        buffer.writeFloat(mach);
        int n = Math.min(tips.size(), MAX_TIPS);
        buffer.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Vec3 tip = tips.get(i);
            buffer.writeDouble(tip.x);
            buffer.writeDouble(tip.y);
            buffer.writeDouble(tip.z);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.camacraft.fullstop.client.render.MachCones.on(this));
        ctx.get().setPacketHandled(true);
    }
}
