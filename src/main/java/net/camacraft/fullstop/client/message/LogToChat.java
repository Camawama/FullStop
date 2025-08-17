package net.camacraft.fullstop.client.message;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
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

        minecraft.execute(() -> {
            if (minecraft.isLocalServer()) {
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(chatMessage);
                }
            } else if (minecraft.level != null) {
                for (Player player : minecraft.level.players()) {
                    player.sendSystemMessage(chatMessage);
                }
            }
        });
    }

    public static void sendTo(Entity entity, Object... messages) {
        StringBuilder message = new StringBuilder();
        for (Object object : messages) {
            message.append(object);
            message.append(", ");
        }
        Component chatMessage = Component.literal(
                (entity.level().isClientSide ? "client " : "server ") + message
        );

        if (entity.level().isClientSide) {
            Minecraft.getInstance().execute(() -> entity.sendSystemMessage(chatMessage));
        } else {
            entity.sendSystemMessage(chatMessage);
        }
    }
}
