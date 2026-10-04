package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records every applied move packet as one client tick of motion on the
 * moved entity's FullStop capability (see the sampling notes there). The
 * server's per-tick position delta for a client-driven mover is only as
 * regular as the packet arrivals, which drift against the server clock:
 * doubled deltas and dead stops that were never real - phantom jump
 * damage, elytra blackouts on a straight glide. Sampling per packet makes
 * the measurement per client tick, which is what the physics actually did.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void fullstop$sampleMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        ServerPlayer mover = this.player;
        if (mover == null || mover.isPassenger()) return;
        FullStopCapability cap = FullStopCapability.grabCapability(mover);
        if (cap != null) {
            cap.recordMoveSample(mover.position(), mover.onGround(), mover.getYRot());
        }
    }

    /**
     * Every position the server sets on a player (a command, an ender pearl, chorus fruit, another mod's
     * {@code teleportTo}, a correction) goes through here: FullStop's speed history must not read the jump as motion.
     * Only some of them fire {@code EntityTeleportEvent}; a short one stays under the 60 m/s discontinuity guard and was
     * judged as a hit at that speed a tick later (a slime ceiling threw a teleported player down at 43 m/s).
     */
    @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"))
    private void fullstop$teleported(double x, double y, double z, float yaw, float pitch,
                                     java.util.Set<net.minecraft.world.entity.RelativeMovement> relative, CallbackInfo ci) {
        ServerPlayer moved = this.player;
        if (moved == null) return;
        // only a real move (a look-only set, relative and zero, is none)
        double dx = relative.contains(net.minecraft.world.entity.RelativeMovement.X) ? x : x - moved.getX();
        double dy = relative.contains(net.minecraft.world.entity.RelativeMovement.Y) ? y : y - moved.getY();
        double dz = relative.contains(net.minecraft.world.entity.RelativeMovement.Z) ? z : z - moved.getZ();
        if (dx * dx + dy * dy + dz * dz < 0.05 * 0.05) return;
        FullStopCapability cap = FullStopCapability.grabCapability(moved);
        if (cap != null) cap.markJump();
    }

    @Inject(method = "handleMoveVehicle", at = @At("RETURN"))
    private void fullstop$sampleVehicleMove(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        ServerPlayer driver = this.player;
        if (driver == null) return;
        Entity vehicle = driver.getRootVehicle();
        if (vehicle == driver || vehicle.getControllingPassenger() != driver) return;
        FullStopCapability cap = FullStopCapability.grabCapability(vehicle);
        if (cap != null) {
            cap.recordMoveSample(vehicle.position(), vehicle.onGround(), vehicle.getYRot());
        }
    }
}
