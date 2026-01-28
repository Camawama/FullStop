package net.camacraft.fullstop.client;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.client.render.RaycastLineRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = FullStop.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DebugHotkeyHandler {

    private static boolean velocityDebugEnabled = false;

    private DebugHotkeyHandler() {}

    public static boolean isVelocityDebugEnabled() {
        return velocityDebugEnabled;
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }

        if (!mc.options.keyDebug.isDown() || event.getKey() != GLFW.GLFW_KEY_V) {
            return;
        }

        velocityDebugEnabled = !velocityDebugEnabled;
        RaycastLineRenderer.clear();

        Component message = Component.literal("Velocity debug lines: " + (velocityDebugEnabled ? "ON" : "OFF"));
        mc.player.displayClientMessage(message, false);
    }
}
