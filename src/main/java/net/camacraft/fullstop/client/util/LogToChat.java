package net.camacraft.fullstop.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-only debug logging into the local chat. Lives under client.* because it
 * touches {@link Minecraft} — referencing it from server code would crash a
 * dedicated server.
 */
public final class LogToChat {
    private LogToChat() {
    }

    public static void logToChat(Object... messages) {
        StringBuilder message = new StringBuilder();
        for (Object object : messages) {
            message.append(object).append(", ");
        }
        Component chatMessage = Component.literal(message.toString());

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(chatMessage);
            }
        });
    }
}
