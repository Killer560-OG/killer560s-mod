package com.killer560.hub.util;

import com.killer560.hub.compat.McCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;

import java.util.List;

/**
 * "Is he typing right now?" (killer560, 2026-10-12: the Edit HUD key "should work in menus but not if typing ... regular
 * chat, renaming menus, using search bars"). A raw-polled key must not act while a key press is going into text.
 * <p>
 * Typing is any focused text input, found generically: the screen's focus chain ending in an {@link EditBox} or a
 * multi-line box ({@link AbstractTextAreaWidget}), or any focused, visible one among its widgets (that covers chat,
 * the anvil's rename box, command blocks, the creative and recipe-book searches, the mod menu's search and every other
 * vanilla-widget field). Screens that take text without such a widget are named: chat, sign and book editing. The mod's
 * own hand-drawn search boxes report themselves: the Bazaar view, the AH browser, the inventory search bar (Ctrl+F).
 * A "Press any key..." keybind capture in the mod menu counts too: that key is being bound, not pressed.
 */
public final class TypingDetector {

    private TypingDetector() {
    }

    public static boolean isTyping(Minecraft client) {
        Screen screen = client == null ? null : McCompat.screen(client);
        return screen != null && isTyping(screen);
    }

    public static boolean isTyping(Screen screen) {
        if (screen instanceof ChatScreen || screen instanceof AbstractSignEditScreen || screen instanceof BookEditScreen) {
            return true;
        }
        if (screen instanceof com.killer560.hub.gui.ModScreen menu && menu.isCapturingKey()) {
            return true;
        }
        if (screen instanceof com.killer560.hub.auction.screen.AuctionHouseScreen ah && ah.isSearchFocused()) {
            return true;
        }
        if (com.killer560.hub.bazaar.BazaarView.isSearchFocused()
                && (com.killer560.hub.bazaar.BazaarReskin.isHiding(screen)
                || screen.getClass().getName().startsWith("com.killer560.hub.bazaar."))) {
            return true;
        }
        if (com.killer560.hub.inventorysearch.InventorySearchFeature.isListening()
                && screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
            return true;
        }
        if (screen instanceof CreativeModeInventoryScreen) {
            EditBox search = ((com.killer560.hub.hud.mixin.CreativeSearchAccessor) screen).killer560smod$getSearchBox();
            if (takesText(search)) {
                return true;
            }
        }
        // The focus chain first: what key presses actually go to.
        GuiEventListener focused = screen.getFocused();
        for (int depth = 0; focused != null && depth < 8; depth++) {
            if (takesText(focused)) {
                return true;
            }
            focused = focused instanceof ContainerEventHandler c ? c.getFocused() : null;
        }
        // Then any focused field among the widgets (a field can be focused without being the screen's focused child).
        return anyFocusedField(screen.children(), 0);
    }

    private static boolean anyFocusedField(List<? extends GuiEventListener> children, int depth) {
        if (depth > 6) {
            return false;
        }
        for (GuiEventListener child : children) {
            if (takesText(child)) {
                return true;
            }
            if (child instanceof RecipeBookComponent<?> book) {
                EditBox search = ((com.killer560.hub.hud.mixin.RecipeBookSearchAccessor) book).killer560smod$getSearchBox();
                if (takesText(search)) {
                    return true;
                }
            }
            if (child instanceof ContainerEventHandler c && anyFocusedField(c.children(), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean takesText(GuiEventListener l) {
        if (l instanceof EditBox box) {
            return box.canConsumeInput(); // visible, focused and editable (javap, both versions)
        }
        if (l instanceof AbstractTextAreaWidget area) {
            return area.isFocused() && area.visible;
        }
        return false;
    }
}
