package net.camacraft.fullstop.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class GforceEffectsRenderer {

    private static final ResourceLocation VIGNETTE_LOCATION = new ResourceLocation("textures/misc/vignette.png");
    private static float currentIntensity = 0.0f;

    @SubscribeEvent
    public static void onRenderGuiOverlayPost(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.VIGNETTE.type()) {
            return;
        }

        float targetIntensity = 0.0f;
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player != null && minecraft.options.getCameraType().isFirstPerson() && FullStopConfig.CLIENT.enableGForceEffects.get()) {
            FullStopCapability cap = FullStopCapability.grabCapability(minecraft.player);
            if (cap != null) {
                double gForce = cap.getRunningAverageDelta();
                double minGforce = FullStopConfig.CLIENT.minGForceThreshold.get();
                double maxGforce = FullStopConfig.CLIENT.maxGForceThreshold.get();

                if (gForce > minGforce) {
                    targetIntensity = (float) ((gForce - minGforce) / (maxGforce - minGforce));
                    targetIntensity = Math.min(targetIntensity, 1.0f);
                }
            }
        }

        // Smoothly interpolate towards the target intensity.
        // The 0.1f factor controls the speed of the transition. Lower is smoother and slower.
        currentIntensity = currentIntensity + (targetIntensity - currentIntensity) * 0.1f;

        if (currentIntensity > 0.01f) { // Use a small threshold to avoid rendering for tiny values
            GuiGraphics guiGraphics = event.getGuiGraphics();
            int screenWidth = guiGraphics.guiWidth();
            int screenHeight = guiGraphics.guiHeight();

            // Render our effects on top of the vanilla vignette.
            renderVignette(screenWidth, screenHeight, currentIntensity);
            renderBlackout(screenWidth, screenHeight, currentIntensity);
        }
    }

    private static void renderBlackout(int screenWidth, int screenHeight, float intensity) {
        // This renders a fullscreen darkening effect.
        float alpha = intensity * 0.95f; // Max blackout opacity
        if (alpha > 0) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc(); // Use standard alpha blending
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder bufferbuilder = tesselator.getBuilder();
            bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            bufferbuilder.vertex(0.0D, screenHeight, -90.0D).color(0f, 0f, 0f, alpha).endVertex();
            bufferbuilder.vertex(screenWidth, screenHeight, -90.0D).color(0f, 0f, 0f, alpha).endVertex();
            bufferbuilder.vertex(screenWidth, 0.0D, -90.0D).color(0f, 0f, 0f, alpha).endVertex();
            bufferbuilder.vertex(0.0D, 0.0D, -90.0D).color(0f, 0f, 0f, alpha).endVertex();
            tesselator.end();

            RenderSystem.disableBlend();
        }
    }

    private static void renderVignette(int screenWidth, int screenHeight, float intensity) {
        // This renders the vignette effect, which darkens the corners.
        RenderSystem.enableBlend();
        // This blend function darkens the destination by the source color: Dst' = Dst * (1 - Src)
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        
        // The color value should be proportional to the desired darkness.
        // A value of 0 means no darkening (Dst' = Dst * 1), a value of 1 means full blackening (Dst' = Dst * 0).
        float darkness = intensity * 0.8f;

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(darkness, darkness, darkness, 1.0F);
        RenderSystem.setShaderTexture(0, VIGNETTE_LOCATION);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferbuilder = tesselator.getBuilder();
        bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferbuilder.vertex(0.0D, screenHeight, -90.0D).uv(0.0F, 1.0F).endVertex();
        bufferbuilder.vertex(screenWidth, screenHeight, -90.0D).uv(1.0F, 1.0F).endVertex();
        bufferbuilder.vertex(screenWidth, 0.0D, -90.0D).uv(1.0F, 0.0F).endVertex();
        bufferbuilder.vertex(0.0D, 0.0D, -90.0D).uv(0.0F, 0.0F).endVertex();
        tesselator.end();

        // Reset shader color and blend func
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
