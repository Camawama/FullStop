package net.camacraft.fullstop.client.hotkey;

import net.camacraft.fullstop.client.render.debug.RaycastLineRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Holds the F3+V velocity-debug toggle state. The keypress itself is consumed in
 * KeyboardHandlerMixin (vanilla's F3-combo path), which is the single owner of
 * the hotkey — no separate raw-input listener.
 */
public final class DebugHotkeyHandler {

    private static boolean velocityDebugEnabled = false;

    private DebugHotkeyHandler() {}

    public static boolean isVelocityDebugEnabled() {
        return velocityDebugEnabled;
    }

    /** Called from KeyboardHandlerMixin when F3+V is pressed. */
    public static void toggle() {
        velocityDebugEnabled = !velocityDebugEnabled;
        RaycastLineRenderer.clear();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Component message = Component.empty()
                .append(Component.literal("[Debug]: ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(Component.literal("Velocity debug lines: " + (velocityDebugEnabled ? "ON" : "OFF"))
                        .withStyle(ChatFormatting.WHITE));

        mc.player.displayClientMessage(message, false);
    }
}
