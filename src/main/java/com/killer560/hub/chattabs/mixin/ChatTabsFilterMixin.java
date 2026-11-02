package com.killer560.hub.chattabs.mixin;

import com.killer560.hub.chattabs.ChatTabsFilter;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Predicate;

/**
 * Chat Tabs' message filter. {@code ChatComponent.setVisibleMessageFilter(Predicate<GuiMessage>)} is vanilla's own hook
 * for which messages the chat shows (26.3 and 26.2, javap 2026-11-01: it stores the predicate and calls
 * {@code refreshTrimmedMessages}, which re-lays every message through it; vanilla sets it from the chat restrictions in
 * {@code LocalPlayer.refreshChatAbilities}). Whatever predicate arrives is wrapped in a {@link ChatTabsFilter} that also
 * asks the selected chat tab, so vanilla's restriction still applies and a new one from vanilla is wrapped again.
 *
 * <p>A {@code @ModifyVariable} on the argument, which chains with any other mod's, never a {@code @Redirect} or
 * {@code @Overwrite} (CLAUDE.md: Lunar Client's chat mod and the chat history mods hook ChatComponent too).
 */
@Mixin(ChatComponent.class)
public abstract class ChatTabsFilterMixin {

    @ModifyVariable(method = "setVisibleMessageFilter", at = @At("HEAD"), argsOnly = true, require = 0)
    private Predicate<GuiMessage> killer560smod$chatTabsFilter(Predicate<GuiMessage> filter) {
        return ChatTabsFilter.wrap(filter);
    }
}
