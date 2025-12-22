package net.camacraft.fullstop.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles calling RaycastLineRenderer during world rendering.
 */
@Mod.EventBusSubscriber(modid = "fullstop", value = Dist.CLIENT)
public class RaycastDebugRenderEvent {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            PoseStack poseStack = event.getPoseStack();
            RaycastLineRenderer.render(poseStack, event.getPartialTick());
        }
    }
}