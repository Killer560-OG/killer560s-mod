package com.killer560.hub.chattabs.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Predicate;

/** The chat's current visible-message filter (vanilla's, or one already wrapped by Chat Tabs). Same field on 26.3 and
 *  26.2 (javap 2026-11-01). */
@Mixin(ChatComponent.class)
public interface ChatTabsAccessor {

    @Accessor("visibleMessageFilter")
    Predicate<GuiMessage> killer560smod$getVisibleMessageFilter();

    /** The laid-out chat lines (newest first), read by the testkit through {@code ChatTabsFeature.testShownMessages}. */
    @Accessor("trimmedMessages")
    java.util.List<GuiMessage.Line> killer560smod$getTrimmedMessages();
}
