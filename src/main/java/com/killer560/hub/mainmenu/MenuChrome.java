package com.killer560.hub.mainmenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * Themed "chrome" for every non-title menu (2026-09-15, killer560: every other menu should "fit the same type
 * of theme" as the black+orange title screen): the out-of-world background, the translucent menu tint, the
 * header/footer separators, list backgrounds, the selected-row highlight and scrollbars.
 * <p>
 * Drawing only - the mixins in {@code mainmenu.mixin} (MainMenuPanoramaMixin, MenuScreenBackgroundMixin,
 * MenuTextureMixin, MenuListMixin, MenuScrollbarMixin, MenuModMenuListMixin) decide when. Scope:
 * {@link MainMenuTheme#activeOnMenuBackground()} for the opaque background / tint (out of world only, so
 * in-game menus keep the blurred world), {@link MainMenuTheme#activeOnMenus()} for everything else. Every
 * caller wraps these in try/catch -> {@link MainMenuTheme#fail}. Pure chrome: no solver/state colours.
 */
public final class MenuChrome {

    // Palette (shared with MainMenuTheme / ModScreen).
    // Derived from the main menu's theme by MenuWidgets.sync() -> derive() (2026-10-15); call sync() before reading.
    public static int ACCENT = 0xFFCC6600;
    public static int ACCENT_LIGHT = 0xFFFFA040;
    public static int BORDER = 0xFF663D1A;
    public static int BORDER_DARK = 0xFF553311;
    public static int BG_WARM = 0xFF1A1108;
    public static int BG_WARM_LIGHT = 0xFF3D2A14;
    public static int BG_NEUTRAL = 0xFF1A1A1A;
    /** Selected-row fills - opaque, because vanilla's extractSelection paints them over the border fill. */
    public static int SELECTION_FILL = 0xFF1A1108;
    public static int SELECTION_FILL_HOVER = 0xFF261A0D;
    /** Opaque list / panel colours (killer560, 2026-10-15: lists must be readable, no animated background behind text). */
    public static int LIST_BG = 0xFF120C07;
    public static int HEADER_BG = 0xFF0D0906;
    public static int ROW_HOVER = 0x1ECC6600;
    public static int ROW_HOVER_EDGE = 0x70663D1A;
    public static int SCROLL_DISABLED_EDGE = 0xFF3A2C22;

    /** Palette sync for callers that read these fields directly (MenuListMixin). */
    public static void sync() {
        MenuWidgets.sync();
    }

    static void derive(boolean light) {
        ACCENT = MenuWidgets.ACCENT;
        ACCENT_LIGHT = MenuWidgets.ACCENT_LIGHT;
        BORDER = MenuWidgets.BORDER;
        BORDER_DARK = MenuWidgets.BORDER_DARK;
        BG_WARM = com.killer560.hub.gui.MenuStyle.SEL_BOTTOM;
        BG_WARM_LIGHT = com.killer560.hub.gui.MenuStyle.SEL_TOP;
        BG_NEUTRAL = com.killer560.hub.gui.MenuStyle.CARD_BOTTOM;
        SELECTION_FILL = com.killer560.hub.gui.MenuStyle.SEL_BOTTOM;
        SELECTION_FILL_HOVER = com.killer560.hub.gui.MenuStyle.SEL_TOP;
        LIST_BG = com.killer560.hub.gui.MenuStyle.PANEL_BOTTOM | 0xFF000000;
        HEADER_BG = com.killer560.hub.gui.MenuStyle.HEADER_BOTTOM | 0xFF000000;
        ROW_HOVER = com.killer560.hub.gui.MenuStyle.alpha(com.killer560.hub.gui.MenuStyle.OR, 0x1E);
        ROW_HOVER_EDGE = com.killer560.hub.gui.MenuStyle.alpha(com.killer560.hub.gui.MenuStyle.FRAME_RING, 0xA0);
        SCROLL_DISABLED_EDGE = MenuWidgets.BORDER_DISABLED;
    }

    /** Header / tab-bar separator textures ({@code blit} with these ids is replaced by an accent line). */
    public static final int KIND_NONE = 0;
    public static final int KIND_HEADER_SEPARATOR = 1;
    public static final int KIND_FOOTER_SEPARATOR = 2;
    public static final int KIND_TAB_HEADER_BACKGROUND = 3;

    private static final String TAB_HEADER_BACKGROUND_PATH = "textures/gui/tab_header_background.png";

    private MenuChrome() {
    }

    // ---------------------------------------------------------------- texture classification

    /** Cheap classification of a {@code blit} texture id - reference checks against Screen's public
     *  separator constants (every vanilla / ModMenu caller passes those exact objects), plus a path check
     *  for CreateWorldScreen.TAB_HEADER_BACKGROUND (compared by path so this never forces CreateWorldScreen's
     *  static init from an arbitrary early blit). Called for every 10-arg blit, so no allocation. */
    public static int classifyTexture(Identifier id) {
        if (id == null) {
            return KIND_NONE;
        }
        if (id == Screen.HEADER_SEPARATOR || id == Screen.INWORLD_HEADER_SEPARATOR) {
            return KIND_HEADER_SEPARATOR;
        }
        if (id == Screen.FOOTER_SEPARATOR || id == Screen.INWORLD_FOOTER_SEPARATOR) {
            return KIND_FOOTER_SEPARATOR;
        }
        String path = id.getPath();
        if (path.length() == TAB_HEADER_BACKGROUND_PATH.length() && TAB_HEADER_BACKGROUND_PATH.equals(path)
                && "minecraft".equals(id.getNamespace())) {
            return KIND_TAB_HEADER_BACKGROUND;
        }
        return KIND_NONE;
    }

    /** Draws the themed replacement for a classified texture over the blit's destination rect. */
    public static void drawTextureReplacement(GuiGraphicsExtractor g, int kind, int x, int y, int w, int h, boolean inWorld) {
        MenuWidgets.sync();
        if (w <= 0 || h <= 0) {
            return;
        }
        switch (kind) {
            case KIND_HEADER_SEPARATOR -> separator(g, x, y, w, h, true);
            case KIND_FOOTER_SEPARATOR -> separator(g, x, y, w, h, false);
            case KIND_TAB_HEADER_BACKGROUND -> g.fill(x, y, x + w, y + h, inWorld ? com.killer560.hub.gui.MenuStyle.alpha(HEADER_BG, 0x99) : HEADER_BG);
            default -> {
            }
        }
    }

    /** 2px separator: a dark shadow row plus an orange accent row hugging the content below (header) or
     *  above (footer). The accent fades toward the screen edges using SCREEN coordinates, so split
     *  separators (TabNavigationBar draws one left of the tabs and one right of them) join seamlessly. */
    private static void separator(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean header) {
        int accentRow = header ? y + h - 1 : y;
        int shadowTop = header ? y : y + 1;
        int shadowBottom = header ? y + h - 1 : y + h;
        if (shadowBottom > shadowTop) {
            g.fill(x, shadowTop, x + w, shadowBottom, 0x80000000);
        }
        accentSpan(g, x, x + w, accentRow, 0xD8, 0x48);
    }

    /** Horizontal 1px orange line over [x0, x1) whose alpha falls from {@code maxAlpha} at the screen centre
     *  to {@code minAlpha} at the screen edges. ~24 flat quads for a full-width line. */
    public static void accentSpan(GuiGraphicsExtractor g, int x0, int x1, int y, int maxAlpha, int minAlpha) {
        MenuWidgets.sync();
        if (x1 <= x0) {
            return;
        }
        int gw = g.guiWidth();
        if (gw <= 0) {
            gw = Math.max(1, x1);
        }
        int columns = 24;
        int colW = Math.max(1, (gw + columns - 1) / columns);
        double half = gw / 2.0;
        int start = Math.floorDiv(x0, colW) * colW;
        int guard = 0;
        for (int cx = start; cx < x1 && guard < 512; cx += colW, guard++) {
            int a0 = Math.max(x0, cx);
            int a1 = Math.min(x1, cx + colW);
            if (a1 <= a0) {
                continue;
            }
            double mid = cx + colW * 0.5;
            double d = Math.min(1.0, Math.abs(mid - half) / half);
            int a = (int) Math.round(maxAlpha - (maxAlpha - minAlpha) * d);
            g.fill(a0, y, a1, y + 1, MainMenuTheme.argb(ACCENT, a));
        }
    }

    // ---------------------------------------------------------------- screen background

    /** Toned-down sibling of {@link MainMenuTheme#drawBackground} for every non-title out-of-world menu:
     *  same warm gradient, bottom glow, vignette and hairlines, but no logo glow and dimmer/fewer embers
     *  (still honouring the particles toggle) so lists and text on top stay readable. */
    public static void drawMenuBackground(GuiGraphicsExtractor g, int w, int h) {
        MenuWidgets.sync();
        if (w <= 0 || h <= 0) {
            return;
        }
        // The main menu's own animated scene, calmer (half the particles) and under a veil, so every out-of-world menu
        // reads as the same place as the main menu (2026-10-15). Lists and panels on top are opaque (drawListBackground).
        MainMenuScenes.draw(g, w, h, MainMenuLook.current().calm());
        g.fill(0, 0, w, h, com.killer560.hub.gui.MenuStyle.alpha(com.killer560.hub.gui.MenuStyle.PANEL_BOTTOM,
                com.killer560.hub.gui.MenuStyle.LIGHT ? 0x50 : 0x60));
    }

    /** Replaces Screen.MENU_BACKGROUND's tiled dark tint (out of world only) over the given rect. */
    public static void drawMenuTint(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        MenuWidgets.sync();
        if (w <= 0 || h <= 0) {
            return;
        }
        g.fill(x, y, x + w, y + h, com.killer560.hub.gui.MenuStyle.alpha(LIST_BG, 0x40));
    }

    // ---------------------------------------------------------------- lists

    /** Replaces menu_list_background / inworld_menu_list_background: a translucent warm-dark panel. In world
     *  it stays lighter so the blurred world still reads through. */
    public static void drawListBackground(GuiGraphicsExtractor g, int x, int y, int right, int bottom, boolean inWorld) {
        MenuWidgets.sync();
        if (right <= x || bottom <= y) {
            return;
        }
        // Opaque out of the world: nothing of the animated background behind a world or server list's text.
        g.fill(x, y, right, bottom, inWorld ? com.killer560.hub.gui.MenuStyle.alpha(LIST_BG, 0xB0) : LIST_BG);
    }

    /** Selected-row highlight: dark warm fill with an orange outline; light orange (and a slightly lighter
     *  fill) while the list is focused or the row is hovered. Same footprint as vanilla's
     *  outer-fill + inset-black pair. */
    public static void drawSelection(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean focused, boolean hovered) {
        MenuWidgets.sync();
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean bright = focused || hovered;
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hovered ? SELECTION_FILL_HOVER : SELECTION_FILL);
        g.outline(x, y, w, h, bright ? ACCENT_LIGHT : ACCENT);
    }

    /** Faint hover wash for a non-selected row of a selectable list. */
    public static void drawRowHover(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        MenuWidgets.sync();
        if (w <= 0 || h <= 0) {
            return;
        }
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, ROW_HOVER);
        g.outline(x, y, w, h, ROW_HOVER_EDGE);
    }

    // ---------------------------------------------------------------- scrollbars

    public static void drawScrollTrack(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        MenuWidgets.sync();
        if (w <= 0 || h <= 0) {
            return;
        }
        g.fill(x, y, x + w, y + h, BG_WARM);
        g.fill(x, y, x + 1, y + h, BORDER_DARK);
    }

    /** Orange thumb; light orange while hovered/dragged, muted brown when disabled. */
    public static void drawScrollThumb(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hot, boolean disabled) {
        MenuWidgets.sync();
        if (w <= 0 || h <= 0) {
            return;
        }
        int body = disabled ? BORDER_DARK : (hot ? ACCENT_LIGHT : ACCENT);
        g.fill(x, y, x + w, y + h, body);
        if (w > 2 && h > 2) {
            // 1px darker right/bottom edge for a little depth.
            int edge = disabled ? SCROLL_DISABLED_EDGE : BORDER;
            g.fill(x + w - 1, y, x + w, y + h, edge);
            g.fill(x, y + h - 1, x + w - 1, y + h, edge);
        }
    }

}
