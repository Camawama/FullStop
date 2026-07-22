package net.camacraft.fullstop.client.mixin;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("channelAccess")
    ChannelAccess fullstop$getChannelAccess();

    @Accessor("executor")
    SoundEngineExecutor fullstop$getExecutor();
}