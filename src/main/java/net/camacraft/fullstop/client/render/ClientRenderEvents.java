package net.camacraft.fullstop.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.camacraft.fullstop.FullStop;

/**
 * Handles client-side world rendering for debug visuals (e.g. raycast lines).
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = FullStop.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientRenderEvents {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Only render after particles, so it’s drawn clearly on top of the world
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            PoseStack poseStack = event.getPoseStack();
            RaycastLineRenderer.render(poseStack, event.getPartialTick());
        }
    }
}