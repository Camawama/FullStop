package net.camacraft.fullstop.common.network;

import net.camacraft.fullstop.server.physics.ship.ShipEntityImpacts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: a Valkyrien Skies hull ran into this player, as the player's own client saw it
 * (see {@code ClientShipContacts}). A player moves on its client, and that is where VS pushes it
 * out of a hull; the server's ship poses run ahead of what the client drew (the round trip plus
 * VS's interpolation - at 64 RPM, a rotor arm two blocks ahead), so only the client can tell when
 * and how a fast hull meets it. The client has already given itself the kick; the server bills the
 * damage, pushes the ship back and corrects the kick if the ship's mass says otherwise.
 *
 * @param shipId     the ship
 * @param block      the hull block met, in ship space
 * @param face       its face met (ship space)
 * @param localPoint the contact point, in ship space
 * @param normal     the face's outward normal in the world, as the client drew the ship
 * @param ownMps     the player's own velocity before the hull met it (m/s, world)
 * @param hullMps    the hull's velocity at the contact point (m/s, world, as the client saw it move)
 * @param closing    how fast the hull closed on the player there (m/s)
 * @param appliedMps the velocity change the client gave itself (m/s, world: normal, friction, lift)
 * @param hit        a new contact (false: the hull caught it up again within a hit's cooldown)
 */
public record ShipContactPacket(long shipId, BlockPos block, Direction face, Vec3 localPoint, Vec3 normal, Vec3 ownMps,
                                Vec3 hullMps, double closing, Vec3 appliedMps, boolean hit) {

    /** Sanity bound on every speed in the packet: 2000 m/s. */
    private static final double MAX_SPEED = 2000.0;

    public ShipContactPacket(FriendlyByteBuf buffer) {
        this(buffer.readLong(), buffer.readBlockPos(), buffer.readEnum(Direction.class), readVec(buffer), readVec(buffer),
                readVec(buffer), readVec(buffer), buffer.readDouble(), readVec(buffer), buffer.readBoolean());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeLong(shipId);
        buffer.writeBlockPos(block);
        buffer.writeEnum(face);
        writeVec(buffer, localPoint);
        writeVec(buffer, normal);
        writeVec(buffer, ownMps);
        writeVec(buffer, hullMps);
        buffer.writeDouble(closing);
        writeVec(buffer, appliedMps);
        buffer.writeBoolean(hit);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null && sane()) {
            ShipEntityImpacts.clientContact(player, this);
        }
        ctx.get().setPacketHandled(true);
    }

    private boolean sane() {
        return finite(localPoint) && finite(normal) && finite(ownMps) && finite(hullMps) && finite(appliedMps)
                && Double.isFinite(closing) && Math.abs(closing) <= MAX_SPEED && ownMps.length() <= MAX_SPEED
                && hullMps.length() <= MAX_SPEED && appliedMps.length() <= MAX_SPEED
                && Math.abs(normal.length() - 1.0) < 0.01;
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static Vec3 readVec(FriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    private static void writeVec(FriendlyByteBuf buffer, Vec3 v) {
        buffer.writeDouble(v.x);
        buffer.writeDouble(v.y);
        buffer.writeDouble(v.z);
    }
}
