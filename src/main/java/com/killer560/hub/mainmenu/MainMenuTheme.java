package com.killer560.hub.mainmenu;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.compat.McCompat;

/**
 * Black+orange restyle of the vanilla title screen (2026-09-15, killer560: "redo the main menu to fit our
 * theme more. Make the Minecraft buttons our theme and the background our theme and whatnot").
 * <p>
 * All drawing lives here; the mixins in {@code mainmenu.mixin} only decide WHEN to call it. Everything is
 * scoped to {@link TitleScreen} (other menus keep vanilla visuals) and guarded: the first exception thrown
 * by any themed draw is logged once and permanently flips {@link #active()} off for the session, so the
 * menu silently falls back to vanilla instead of crashing or spamming the log every frame.
 */
public final class MainMenuTheme {

    private static final Logger LOGGER = ModLog.get("killer560smod-mainmenu");

    // Palette - same values as SettingsButtonWidget / ModScreen / ModChat so the title screen matches the
    // Swap Accounts button and the mod menu exactly.
    public static final int ORANGE = 0xCC6600;
    public static final int LIGHT_ORANGE = 0xFFA040;
    public static final int TEXT = 0xF0E6DC;
    public static final int DIM = 0x9A8C80;


    private static volatile boolean failed;
    private static String modTag;

    private MainMenuTheme() {
    }

    /** Toggle on and nothing has failed this session. */
    public static boolean active() {
        return !failed && MainMenuThemeConfig.getInstance().isEnabled();
    }

    /** {@link #active()} and the screen currently shown is the title screen - used by the mixins on
     *  shared widget classes (AbstractButton, PlainTextButton) so other screens stay vanilla. */
    public static boolean activeOnTitleScreen() {
        return active() && McCompat.screen(Minecraft.getInstance()) instanceof TitleScreen;
    }

    /** Widget theming scope (buttons, text fields, sliders, checkboxes, lists...): the title screen, plus -
     *  while "Themed Menus" is on - every other open screen except container screens (chests, inventories,
     *  terminals keep vanilla/feature visuals). Applies in and out of a world. */
    public static boolean activeOnMenus() {
        if (!active()) {
            return false;
        }
        Screen screen = McCompat.screen(Minecraft.getInstance());
        if (screen == null) {
            return false;
        }
        if (screen instanceof TitleScreen) {
            return true;
        }
        // Chat (and InBedChatScreen's Leave Bed button / mod-added chat widgets) stays vanilla, like the HUD.
        return MainMenuThemeConfig.getInstance().isOtherMenus() && !(screen instanceof AbstractContainerScreen<?>)
                && !(screen instanceof ChatScreen);
    }

    /** Background scope: {@link #activeOnMenus()} and no world loaded, so in-game menus keep the blurred
     *  world behind them and only out-of-world menus swap the panorama for the themed background. */
    public static boolean activeOnMenuBackground() {
        return activeOnMenus() && Minecraft.getInstance().level == null;
    }

    public static void fail(String where, Throwable t) {
        if (!failed) {
            failed = true;
            LOGGER.error("[MainMenu] Themed main menu disabled for this session after an error in {}", where, t);
        }
    }

    // ---------------------------------------------------------------- colour helpers

    /** rgb (0xRRGGBB) with an explicit 0..255 alpha. */
    public static int argb(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    /** Multiplies an ARGB colour's own alpha by {@code factor} (0..1). */
    public static int fade(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        return argb(argb, Math.round(a * Math.max(0f, Math.min(1f, factor))));
    }

    // ---------------------------------------------------------------- buttons

    public static void drawButtonBox(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                     boolean active, boolean highlighted, float alpha) {
        // Every themed menu's buttons (the title screen, the pause menu, options, world and server lists...) in the main
        // menu's theme, read live each frame (killer560, 2026-10-15: the pause menu kept a fixed dark look whatever the
        // theme). Inside one of the mod's own screens a vanilla button follows that screen's palette instead.
        MainMenuChrome.drawButton(g, x, y, w, h, active, highlighted, alpha);
    }

    /** Wraps a label so its un-coloured parts render in the theme colour; explicit colours inside the
     *  component (another mod's coloured label, vanilla's grey inactive message) still win because
     *  children only inherit the parent colour when they don't set their own. */
    public static Component recolorLabel(Component message, boolean active, boolean highlighted) {
        if (message == null) {
            return null;
        }
        int rgb = MainMenuChrome.labelColour(active, highlighted);
        return Component.empty().withColor(rgb).append(message);
    }

    // ---------------------------------------------------------------- footer

    public static String modTag() {
        String tag = modTag;
        if (tag == null) {
            tag = "Killer560's Mod";
            try {
                tag = FabricLoader.getInstance().getModContainer("killer560smod")
                        .map(c -> "Killer560's Mod " + c.getMetadata().getVersion().getFriendlyString())
                        .orElse(tag);
            } catch (Throwable ignored) {
            }
            modTag = tag;
        }
        return tag;
    }

    // ---------------------------------------------------------------- background

    /** The main menu's animated background in its own theme (see {@link MainMenuLook}, {@link MainMenuScenes}): Amber's
     *  embers, Ocean's waterfall, Dark's starfield, Light's soft light, Custom's aurora, or the Custom style's pick.
     *  Also the Swap Accounts screens' background ({@code AccountScreenBackground}). */
    public static void drawBackground(GuiGraphicsExtractor g, int w, int h) {
        MainMenuScenes.draw(g, w, h, MainMenuLook.current());
    }
}
