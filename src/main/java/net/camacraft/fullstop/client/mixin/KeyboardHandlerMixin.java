package net.camacraft.fullstop.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Adds an extra line to the F3+Q debug help list.
 *
 * Inserts:
 *   F3 + V = Show velocity debug lines
 * directly after the vanilla "debug.reload_resourcepacks.help" entry (F3 + T).
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    private static final String RESOURCEPACKS_HELP_KEY = "debug.reload_resourcepacks.help";

    @WrapOperation(
            method = "handleDebugKeys", // if this doesn't apply in your mappings, rename to the method that contains the F3+Q block
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessage(Lnet/minecraft/network/chat/Component;)V"
            )
    )
    private void fullstop$appendVelocityHelpLine(
            ChatComponent chat,
            Component message,
            Operation<Void> original
    ) {
        // Let vanilla print the original help line
        original.call(chat, message);

        // If the line was the "reload resource packs" help entry, print our extra line right after it.
        if (message != null && message.getContents() instanceof TranslatableContents tc) {
            if (RESOURCEPACKS_HELP_KEY.equals(tc.getKey())) {
                // Use literal for a quick addition; swap to translatable if you want localization.
                original.call(chat, Component.literal("F3 + V = Show velocity debug lines"));
            }
        }
    }
}