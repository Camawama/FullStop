package net.camacraft.fullstop.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The lightmap's pixels as this frame worked them out (time of day, weather, gamma, night vision and whatever other mods
 * change in it), for what FullStop draws without the lightmap shader: the Mach cones ({@code MachCones}).
 */
@Mixin(LightTexture.class)
public interface LightTextureAccessor {

    @Accessor("lightPixels")
    NativeImage fullstop$lightPixels();
}
