package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An entity moved by {@code teleportTo} (another mod, a command, an enderman's or a chorus fruit's hop) jumped; it
 * didn't travel: FullStop's speed history forgets the move, as for {@code EntityTeleportEvent} (which only some of
 * these fire). A short jump stays under the 60 m/s discontinuity guard and was judged as an impact at that speed a
 * tick later (a mob put down beside a wall hit it). Players also come through {@code ServerGamePacketListenerImplMixin}.
 */
@Mixin(Entity.class)
public abstract class EntityTeleportMixin {

    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"))
    private void fullstop$teleported(double x, double y, double z, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide()) return;
        FullStopCapability cap = FullStopCapability.grabCapability(self);
        if (cap != null) cap.markJump();
    }
}
