package com.killer560.hub.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * One answer to "should a gameplay HUD draw while this screen is open?", so every element agrees.
 * <p>
 * killer560 (2026-09-16): "dont make it hide the gui if i open chat. Make sure that happens for all menus
 * that are normally open without chat being open." Before this, most elements tested
 * {@code client.screen != null}, which is also true for the chat screen - so opening chat made the whole
 * HUD vanish. Chat is a transparent overlay you keep playing behind, so it never counts as a menu here;
 * inventories, containers, the mod screen and every other real screen still do.
 */
public final class HudVisibility {

    private HudVisibility() {
    }

    /** True for the vanilla chat screen (and subclasses). */
    public static boolean isChat(Screen screen) {
        return screen instanceof ChatScreen;
    }

    /**
     * For the in-game draw layers (Fabric HUD callbacks, Gui mixins, {@link HudInGameRenderer}): true when a
     * screen other than chat is open. The HUD editor counts as open - it draws every element itself, so the
     * in-game layer must stay quiet or each element shows twice.
     */
    public static boolean menuOpen() {
        Screen screen = Minecraft.getInstance().screen;
        return screen != null && !isChat(screen);
    }

    /**
     * For use inside {@link HudElement#render}: true when a screen that should hide gameplay HUDs is open.
     * Chat never hides (see class doc), and neither does the HUD editor - render() is also the editor's
     * preview, so an element that bailed out here would show up in the editor as an empty box.
     */
    public static boolean hidesHud() {
        Screen screen = Minecraft.getInstance().screen;
        return screen != null && !isChat(screen) && !(screen instanceof HudEditorScreen);
    }

    /** True while the HUD position editor is the open screen. */
    public static boolean editorOpen() {
        return Minecraft.getInstance().screen instanceof HudEditorScreen;
    }
}
