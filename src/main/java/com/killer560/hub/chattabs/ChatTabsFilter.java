package com.killer560.hub.chattabs;

import net.minecraft.client.multiplayer.chat.GuiMessage;

import java.util.function.Predicate;

/**
 * The chat's visible-message filter with the selected chat tab added: a message shows when the filter it wraps (vanilla's
 * chat restrictions) lets it AND {@link ChatTabsFeature#visible} does. Recognized by class, so wrapping never nests.
 */
public final class ChatTabsFilter implements Predicate<GuiMessage> {

    private final Predicate<GuiMessage> inner;

    private ChatTabsFilter(Predicate<GuiMessage> inner) {
        this.inner = inner;
    }

    /** {@code filter} with the chat tab added (its inner one when it is already a ChatTabsFilter). */
    public static Predicate<GuiMessage> wrap(Predicate<GuiMessage> filter) {
        Predicate<GuiMessage> base = unwrap(filter);
        return new ChatTabsFilter(base == null ? m -> true : base);
    }

    /** The filter under a ChatTabsFilter, or {@code filter} itself. */
    public static Predicate<GuiMessage> unwrap(Predicate<GuiMessage> filter) {
        return filter instanceof ChatTabsFilter f ? f.inner : filter;
    }

    @Override
    public boolean test(GuiMessage message) {
        return inner.test(message) && ChatTabsFeature.visible(message);
    }
}
