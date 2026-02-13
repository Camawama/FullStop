package net.camacraft.fullstop.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.effect.ModEffects;
import net.camacraft.fullstop.common.util.MathUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class GforceEffectsRenderer {

    private static final ResourceLocation POWDER_SNOW_OUTLINE_LOCATION = new ResourceLocation("textures/misc/powder_snow_outline.png");
    private static float currentIntensity = 0.0f;
    private static float currentFovModifier = 1.0f;

    @SubscribeEvent
    public static void onComputeFovModifier(ComputeFovModifierEvent event) {
        if (!FullStopConfig.SERVER.enableGForceEffects.get()) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;

        float targetFovModifier = 1.0f;
        
        FullStopCapability cap = FullStopCapability.grabCapability(minecraft.player);
        if (cap != null) {
            // Use raw G-force (unaffected by potions) for FOV
            double gForce = cap.getRawRunningAverageDelta();
            
            // Exponential scaling for FOV
            // We want 15G to be 100% increase (2.0x FOV)
            // Formula: 1.0 + (gForce / 15.0)^2
            
            targetFovModifier = 1.0f + (float) Math.pow(gForce / 15.0, 2);
        }

        // Smooth interpolation for FOV
        currentFovModifier = currentFovModifier + (targetFovModifier - currentFovModifier) * 0.1f;
        
        // Apply the modifier. This multiplies the existing FOV, so it respects user settings but can exceed limits.
        if (Math.abs(currentFovModifier - 1.0f) > 0.001f) {
            event.setNewFovModifier(event.getNewFovModifier() * currentFovModifier);
        }
    }

    @SubscribeEvent
    public static void onRenderGuiOverlayPost(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.VIGNETTE.type()) {
            return;
        }

        float targetIntensity = 0.0f;
        Minecraft minecraft = Minecraft.getInstance();

        // Check server config instead of client config
        if (minecraft.player != null && minecraft.options.getCameraType().isFirstPerson() && FullStopConfig.SERVER.enableGForceEffects.get()) {
            FullStopCapability cap = FullStopCapability.grabCapability(minecraft.player);
            if (cap != null) {
                double gForce = cap.getRunningAverageDelta();
                double minGforce = FullStopConfig.CLIENT.minGForceThreshold.get();
                double maxGforce = FullStopConfig.CLIENT.maxGForceThreshold.get();

                // Calculate Potion Modifiers
                int clarityLevel = 0;
                int vertigoLevel = 0;

                MobEffectInstance clarity = minecraft.player.getEffect(ModEffects.CLARITY.get());
                if (clarity != null) {
                    clarityLevel = clarity.getAmplifier() + 1;
                }

                MobEffectInstance vertigo = minecraft.player.getEffect(ModEffects.VERTIGO.get());
                if (vertigo != null) {
                    vertigoLevel = vertigo.getAmplifier() + 1;
                }

                int netLevel = vertigoLevel - clarityLevel;

                // Adjust thresholds based on net level
                if (netLevel > 0) {
                    double multiplier = Math.pow(0.8, netLevel); 
                    minGforce *= multiplier;
                    maxGforce *= multiplier;
                } else if (netLevel < 0) {
                    double multiplier = Math.pow(1.25, -netLevel);
                    minGforce *= multiplier;
                    maxGforce *= multiplier;
                }

                if (gForce > minGforce) {
                    // Scale from minGforce to maxGforce
                    targetIntensity = (float) ((gForce - minGforce) / (maxGforce - minGforce));
                    targetIntensity = Math.min(targetIntensity, 1.0f);
                } else {
                    targetIntensity = 0.0f;
                }
            }
        }

        // Smoothly interpolate towards the target intensity.
        // The 0.1f factor controls the speed of the transition. Lower is smoother and slower.
        currentIntensity = currentIntensity + (targetIntensity - currentIntensity) * 0.1f;

        if (currentIntensity > 0.001f) { // Use a small threshold to avoid rendering for tiny values
            GuiGraphics guiGraphics = event.getGuiGraphics();
            int screenWidth = guiGraphics.guiWidth();
            int screenHeight = guiGraphics.guiHeight();

            // Apply easing to make the effect feel more natural
            float easedIntensity = MathUtils.easeOutCubic(currentIntensity);

            // Render our effects on top of the vanilla vignette.
            renderTunnelVision(screenWidth, screenHeight, easedIntensity);
            renderBlackout(screenWidth, screenHeight, easedIntensity);
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

    private static void renderTunnelVision(int screenWidth, int screenHeight, float intensity) {
        // This renders a texture similar to the powder snow effect, creating a tunnel vision effect.
        // We use the powder snow outline texture but color it black.
        
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        
        // Set color to black with alpha based on intensity
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, POWDER_SNOW_OUTLINE_LOCATION);
        
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferbuilder = tesselator.getBuilder();
        bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        
        // Render the texture stretched over the screen
        // We can render it multiple times or scale it to make the tunnel tighter if needed.
        // For now, let's just render it once with varying alpha.
        
        float alpha = intensity; // Full intensity = full opacity
        
        bufferbuilder.vertex(0.0D, screenHeight, -90.0D).uv(0.0F, 1.0F).color(0.0F, 0.0F, 0.0F, alpha).endVertex();
        bufferbuilder.vertex(screenWidth, screenHeight, -90.0D).uv(1.0F, 1.0F).color(0.0F, 0.0F, 0.0F, alpha).endVertex();
        bufferbuilder.vertex(screenWidth, 0.0D, -90.0D).uv(1.0F, 0.0F).color(0.0F, 0.0F, 0.0F, alpha).endVertex();
        bufferbuilder.vertex(0.0D, 0.0D, -90.0D).uv(0.0F, 0.0F).color(0.0F, 0.0F, 0.0F, alpha).endVertex();
        
        tesselator.end();
        
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }
}
