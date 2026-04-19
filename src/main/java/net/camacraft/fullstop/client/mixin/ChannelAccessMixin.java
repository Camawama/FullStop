package net.camacraft.fullstop.client.mixin;

import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.camacraft.fullstop.client.sound.AudioFilterManager;

@Mixin(ChannelAccess.class)
public class ChannelAccessMixin {

    @Inject(method = "scheduleTick", at = @At("TAIL"))
    private void fullstop$afterTick(CallbackInfo ci) {
        AudioFilterManager.applyFilterToAllChannels();
    }
}