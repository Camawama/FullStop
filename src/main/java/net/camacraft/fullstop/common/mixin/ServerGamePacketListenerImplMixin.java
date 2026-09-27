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
