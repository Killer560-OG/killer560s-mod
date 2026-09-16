package com.killer560.hub.accounts.gui;

import com.killer560.hub.mainmenu.MainMenuTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * Shared background for the Swap Accounts flow (AccountSwitcherScreen, DirectSessionLoginScreen,
 * AccountProxyConfigScreen, ProxyConfigScreen) - 2026-09-15, killer560: "make this menu have the same moving
 * background" as the title screen. Uses the full {@link MainMenuTheme#drawBackground} (glows + embers,
 * respecting the particles setting), NOT the toned-down MenuChrome variant other menus get.
 * <p>
 * Each screen overrides {@code Screen.extractBackground} and calls {@link #draw} first. When it returns true the
 * screen skips vanilla's whole background path (extractPanorama -> extractBlurredBackground ->
 * extractMenuBackground), so there's no double draw (MainMenuPanoramaMixin's MenuChrome swap never runs) and no
 * blur pass, and the screen also skips its old 0xCC000000 dim overlay in {@code extractRenderState}. Otherwise
 * (theme off / failed, or a world is loaded) the screen falls back to vanilla's background + the dim overlay,
 * exactly as before.
 */
public final class AccountScreenBackground {

    private AccountScreenBackground() {
    }

    /** Title-screen background applies: theme on (and not failed this session) and no world loaded. */
    public static boolean themed() {
        try {
            return MainMenuTheme.active() && Minecraft.getInstance().level == null;
        } catch (Throwable t) {
            MainMenuTheme.fail("account screen background check", t);
            return false;
        }
    }

    /** Draws the title screen's animated background over the whole screen when {@link #themed()}.
     *  @return true if it was drawn (caller must skip vanilla's background and its own dim overlay). */
    public static boolean draw(GuiGraphicsExtractor graphics, Screen screen) {
        if (!themed()) {
            return false;
        }
        try {
            MainMenuTheme.drawBackground(graphics, screen.width, screen.height);
            // Vanilla Screen.extractBackground ends with this; keep it so subtitles still show.
            Minecraft.getInstance().gui.extractDeferredSubtitles();
            return true;
        } catch (Throwable t) {
            MainMenuTheme.fail("account screen background", t);
            return false;
        }
    }
}
