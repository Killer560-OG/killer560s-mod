package com.killer560.hub.chattidy.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The chat window's full message list (newest first), read by Chat Tidy's test hooks. Same field on 26.1.2 and 26.2
 *  (javap 2026-10-07). */
@Mixin(ChatComponent.class)
public interface ChatTidyAccessor {

    @Accessor("allMessages")
    List<GuiMessage> killer560smod$getAllMessages();
}
