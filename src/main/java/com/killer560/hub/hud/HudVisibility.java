package com.killer560.hub.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import com.killer560.hub.compat.McCompat;

/**
 * One answer to "should a gameplay HUD draw while this screen is open?", so every element agrees.
 * <p>
 * killer560 (2026-09-16): "dont make it hide the gui if i open chat. Make sure that happens for all menus
 * that are normally open without chat being open." Before this, most elements tested
 * {@code McCompat.screen(client) != null}, which is also true for the chat screen - so opening chat made the whole
 * HUD vanish. Chat is a transparent overlay you keep playing behind, so it never counts as a menu here;
 * inventories, containers, the mod screen and every other real screen still do.
 */
public final class HudVisibility {

    private HudVisibility() {
    }

    /** Set by {@link HudInGameRenderer#drawOverScreen} while it draws the tick elements on top of a leap menu, so their
     *  own {@link #hidesHud} gate lets them through. Render thread only. */
    static boolean overScreen;

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
        Screen screen = McCompat.screen(Minecraft.getInstance());
        return screen != null && !isChat(screen) && !isTerminalMenu(screen);
    }

    /**
     * For use inside {@link HudElement#render}: true when a screen that should hide gameplay HUDs is open.
     * Chat never hides (see class doc), and neither does the HUD editor - render() is also the editor's
     * preview, so an element that bailed out here would show up in the editor as an empty box.
     */
    public static boolean hidesHud() {
        if (overScreen) {
            return false;
        }
        Screen screen = McCompat.screen(Minecraft.getInstance());
        return screen != null && !isChat(screen) && !(screen instanceof HudEditorScreen) && !isTerminalMenu(screen);
    }

    private static net.minecraft.network.chat.Component terminalTitleFor;
    private static boolean terminalTitleResult;

    /**
     * True for a Hypixel terminal container (Click in order, Correct all the panes, ... Melody), with or without the
     * Terminal Solver or its Custom GUI. killer560 (2026-11-03): "dont hide stuff inside of the terminal menu (hud wise)" -
     * the tick timers, splits and the rest keep drawing there, because a terminal is a menu you play inside, not one you
     * stop to read. Every other container still hides the HUD. The title is matched once per title object, not per frame.
     */
    public static boolean isTerminalMenu(Screen screen) {
        if (!(screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen container)) {
            return false;
        }
        net.minecraft.network.chat.Component title = container.getTitle();
        if (title != terminalTitleFor) {
            terminalTitleResult = com.killer560.hub.terminals.TerminalType.match(title.getString()) != null;
            terminalTitleFor = title;
        }
        return terminalTitleResult;
    }

    /** True while the HUD position editor is the open screen. */
    public static boolean editorOpen() {
        return McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen;
    }
}
