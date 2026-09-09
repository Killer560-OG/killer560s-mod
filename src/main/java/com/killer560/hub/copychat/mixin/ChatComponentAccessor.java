package com.killer560.hub.copychat.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Exposes the private per-visual-line state {@link com.killer560.hub.copychat.CopyChatFeature}'s own
 *  "shift-click copies just this line" mode needs (killer560's request, 2026-09-09) - {@code trimmedMessages}
 *  is the actual wrapped/visible line list (one entry per on-screen row, not per full message), and
 *  {@code getScale()}/{@code getLineHeight()} are the exact real values the chat GUI itself already uses
 *  to lay those rows out, needed to correctly reverse a raw click Y back into a specific line index -
 *  same math quoi's own {@code ChatUtils.toChatLineMY}/{@code getMessageLineIdx} use (decompiled
 *  2026-09-09 as reference), reproduced here via accessors instead of duplicating the formula by hand so
 *  it can never drift from what the screen actually rendered. */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> killer560smod$getTrimmedMessages();

    @Accessor("chatScrollbarPos")
    int killer560smod$getChatScrollbarPos();

    @Invoker("getScale")
    double killer560smod$invokeGetScale();

    @Invoker("getLineHeight")
    int killer560smod$invokeGetLineHeight();
}
