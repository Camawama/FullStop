package net.camacraft.fullstop.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.rules.GForceThresholds;
import net.camacraft.fullstop.common.util.MathUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Tunnel-vision/blackout overlay driven by sustained g-force and low air supply.
 * Hooks RenderGuiEvent.Post (not the vignette overlay) so it also renders when the
 * vignette is skipped, e.g. on Fast graphics.
 */
@Mod.EventBusSubscriber(modid = FullStop.MODID, value = Dist.CLIENT)
public class GforceEffectsRenderer {

    private static final ResourceLocation POWDER_SNOW_OUTLINE_LOCATION = new ResourceLocation("textures/misc/powder_snow_outline.png");
    private static float currentIntensity = 0.0f;
    private static float currentFovModifier = 1.0f;

    /** True when the effect should run: in a world, config loaded, not creative/spectator. */
    private static LocalPlayer effectTarget() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;
        if (player.isCreative() || player.isSpectator()) return null;
        if (!FullStopConfig.SERVER_SPEC.isLoaded()) return null;
        return player;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        // World FOV pass only. The event fires a second time per frame for the
        // item-in-hand FOV (usedConfiguredFov() == false); running there advanced
        // the smoothing at double speed and warped the held item under high g.
        if (!event.usedConfiguredFov()) return;

        LocalPlayer player = effectTarget();
        if (player == null || !FullStopConfig.SERVER.enableGForceEffects.get()) {
            currentFovModifier = 1.0f;
            return;
        }

        float targetFovModifier = 1.0f;

        FullStopCapability cap = FullStopCapability.grabCapability(player);
        if (cap != null) {
            double gForce = cap.getRawRunningAverageDelta();
            // Capped: sustained g-force (spinning fast in circles) used to grow
            // this unbounded until the FOV crossed 180° and the view flipped
            // upside down.
            targetFovModifier = Math.min(1.0f + (float) Math.pow(gForce / 15.0, 2), 1.8f);
        }

        // Frame-time-scaled so the ramp speed doesn't depend on FPS
        // (0.15/tick ≈ the old 0.05/frame at 60 fps).
        float fovStep = Math.min(1.0f, 0.15f * Minecraft.getInstance().getDeltaFrameTime());
        currentFovModifier = currentFovModifier + (targetFovModifier - currentFovModifier) * fovStep;

        if (Math.abs(currentFovModifier - 1.0f) > 0.001f) {
            // Hard ceiling well below 180°: other FOV modifiers (speed effect,
            // sprinting) stack on top of ours, and past 180° the projection
            // renders the world mirrored/upside down.
            event.setFOV(Math.min(event.getFOV() * currentFovModifier, 160.0));
        }
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        float gForceIntensity = 0.0f;
        float drowningIntensity = 0.0f;

        LocalPlayer player = effectTarget();
        if (player != null) {
            if (FullStopConfig.SERVER.enableGForceEffects.get()) {
                FullStopCapability cap = FullStopCapability.grabCapability(player);
                if (cap != null) {
                    double gForce = cap.getRunningAverageDelta();
                    GForceThresholds.Range thresholds = GForceThresholds.effective(player,
                            FullStopConfig.CLIENT.minGForceThreshold.get(),
                            FullStopConfig.CLIENT.maxGForceThreshold.get());

                    if (gForce > thresholds.min()) {
                        // max(…, 0.001): min == max in the config would be 0/0 = NaN,
                        // which enters the intensity lerp and never leaves.
                        gForceIntensity = (float) ((gForce - thresholds.min())
                                / Math.max(thresholds.max() - thresholds.min(), 0.001));
                        gForceIntensity = Math.min(gForceIntensity, 1.0f);
                    }
                }
            }

            int airSupply = player.getAirSupply();
            int maxAir = player.getMaxAirSupply();
            if (airSupply < maxAir) {
                float intensity = 1.0f - ((float) airSupply / (float) maxAir);
                drowningIntensity = intensity * intensity * intensity;
            }
        }

        float targetIntensity = Math.max(gForceIntensity, drowningIntensity);

        // Frame-time-scaled (0.3/tick ≈ the old 0.1/frame at 60 fps) so the
        // blackout ramps at the same speed at any FPS. The RISE is additionally
        // rate-capped per frame: a lag spike hands the next frame several ticks
        // of delta at once, and an uncapped step snapped the overlay straight to
        // full black instead of fading in. Capped, a catch-up still darkens fast
        // (~5 frames to full) but visibly fades. Fade-OUT keeps the uncapped
        // step so recovery is never slowed down.
        float step = Math.min(1.0f, 0.3f * Minecraft.getInstance().getDeltaFrameTime());
        if (targetIntensity > currentIntensity) {
            step = Math.min(step, 0.2f);
        }
        currentIntensity = currentIntensity + (targetIntensity - currentIntensity) * step;

        if (currentIntensity > 0.001f) {
            GuiGraphics guiGraphics = event.getGuiGraphics();
            int screenWidth = guiGraphics.guiWidth();
            int screenHeight = guiGraphics.guiHeight();

            float easedIntensity = MathUtils.easeOutCubic(currentIntensity);

            renderTunnelVision(screenWidth, screenHeight, easedIntensity);
            renderBlackout(screenWidth, screenHeight, easedIntensity);
        }
    }

    private static void renderBlackout(int screenWidth, int screenHeight, float intensity) {
        float alpha = intensity * 0.95f;
        if (alpha > 0) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
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
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, POWDER_SNOW_OUTLINE_LOCATION);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferbuilder = tesselator.getBuilder();
        bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        float alpha = intensity;

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
