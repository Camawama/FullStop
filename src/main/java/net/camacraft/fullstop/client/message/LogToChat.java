package net.camacraft.fullstop.client.message;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public class LogToChat {

    public static void logToChat(Object... messages) {
        Minecraft minecraft = Minecraft.getInstance();
        StringBuilder message = new StringBuilder();
        for (Object object : messages) {
            message.append(object);
            message.append(", ");
        }
        Component chatMessage = Component.literal(message.toString());

        if (minecraft.isLocalServer()) {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(chatMessage);
            }
        } else {
            for (Player player : minecraft.level.players()) {
                player.sendSystemMessage(chatMessage);
            }
        }
    }

}
