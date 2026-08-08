package net.camacraft.fullstop.common.network;

import net.camacraft.fullstop.client.physics.ClientVehicleMotion;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → client: apply a motion change to a vehicle the receiving player is
 * driving.
 *
 * <p>Player-controlled vehicles are client-authoritative: the server-side
 * {@code Boat.tick} zeroes its own deltaMovement every tick when it isn't the
 * controlling instance, and the position flows client → server via move-vehicle
 * packets. A server-side {@code setDeltaMovement + hurtMarked} therefore never
 * reliably reaches a ridden boat — which is why an empty boat bounced off slime
 * perfectly while a ridden one landed dead. This packet delivers the bounce to
 * the authoritative side directly.
 */
public class VehicleMotionPacket {
    private final int entityId;
    private final Vec3 motion; // native blocks/tick

    public VehicleMotionPacket(int entityId, Vec3 motion) {
        this.entityId = entityId;
        this.motion = motion;
    }

    public VehicleMotionPacket(FriendlyByteBuf buffer) {
        this(buffer.readVarInt(), new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entityId);
        buffer.writeDouble(motion.x);
        buffer.writeDouble(motion.y);
        buffer.writeDouble(motion.z);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        // consumerMainThread already puts this on the client main thread; the
        // DistExecutor hop only keeps client classes out of server classloading.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientVehicleMotion.apply(entityId, motion));
        ctx.get().setPacketHandled(true);
    }
}
