package com.killer560.hub.mainmenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

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
    public static final int ACCENT = 0xFFCC6600;
    public static final int ACCENT_LIGHT = 0xFFFFA040;
    public static final int BORDER = 0xFF663D1A;
    public static final int BORDER_DARK = 0xFF553311;
    public static final int BG_WARM = 0xFF1A1108;
    public static final int BG_WARM_LIGHT = 0xFF3D2A14;
    public static final int BG_NEUTRAL = 0xFF1A1A1A;
    /** Selected-row fills - opaque, because vanilla's extractSelection paints them over the border fill. */
    public static final int SELECTION_FILL = 0xFF1A1108;
    public static final int SELECTION_FILL_HOVER = 0xFF261A0D;

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
        if (w <= 0 || h <= 0) {
            return;
        }
        switch (kind) {
            case KIND_HEADER_SEPARATOR -> separator(g, x, y, w, h, true);
            case KIND_FOOTER_SEPARATOR -> separator(g, x, y, w, h, false);
            case KIND_TAB_HEADER_BACKGROUND -> g.fill(x, y, x + w, y + h, MainMenuTheme.argb(0x0D0906, inWorld ? 0x99 : 0xCC));
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
            g.fill(a0, y, a1, y + 1, MainMenuTheme.argb(MainMenuTheme.ORANGE, a));
        }
    }

    // ---------------------------------------------------------------- screen background

    /** Toned-down sibling of {@link MainMenuTheme#drawBackground} for every non-title out-of-world menu:
     *  same warm gradient, bottom glow, vignette and hairlines, but no logo glow and dimmer/fewer embers
     *  (still honouring the particles toggle) so lists and text on top stay readable. */
    public static void drawMenuBackground(GuiGraphicsExtractor g, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        double t = (Util.getMillis() % 3_600_000L) / 1000.0;

        g.fillGradient(0, 0, w, h, 0xFF140D08, 0xFF050404);

        int lowCx = w / 2 - (int) Math.round(Math.sin(t * 0.13) * w * 0.12);
        glow(g, lowCx, h + 10, w * 0.75, h * 0.38, MainMenuTheme.ORANGE, 0x1A, 4);

        if (MainMenuThemeConfig.getInstance().isParticles()) {
            embers(g, w, h, t, 0.6, 0.55);
        }

        g.fillGradient(0, 0, w, Math.max(1, h / 5), 0x80000000, 0x00000000);
        g.fillGradient(0, h - Math.max(1, h / 4), w, h, 0x00000000, 0x99000000);
        int strips = 8;
        int stripW = Math.max(1, w / 60);
        for (int i = 0; i < strips; i++) {
            int a = (int) (0x60 * (1.0 - (double) i / strips));
            int c = MainMenuTheme.argb(0x000000, a);
            g.fill(i * stripW, 0, (i + 1) * stripW, h, c);
            g.fill(w - (i + 1) * stripW, 0, w - i * stripW, h, c);
        }

        accentSpan(g, 0, w, 0, 0x70, 0x00);
        accentSpan(g, 0, w, h - 1, 0x58, 0x00);
    }

    /** Replaces Screen.MENU_BACKGROUND's tiled dark tint (out of world only) over the given rect. */
    public static void drawMenuTint(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        g.fill(x, y, x + w, y + h, 0x40080503);
    }

    // ---------------------------------------------------------------- lists

    /** Replaces menu_list_background / inworld_menu_list_background: a translucent warm-dark panel. In world
     *  it stays lighter so the blurred world still reads through. */
    public static void drawListBackground(GuiGraphicsExtractor g, int x, int y, int right, int bottom, boolean inWorld) {
        if (right <= x || bottom <= y) {
            return;
        }
        g.fill(x, y, right, bottom, MainMenuTheme.argb(0x120C07, inWorld ? 0x80 : 0xA8));
    }

    /** Selected-row highlight: dark warm fill with an orange outline; light orange (and a slightly lighter
     *  fill) while the list is focused or the row is hovered. Same footprint as vanilla's
     *  outer-fill + inset-black pair. */
    public static void drawSelection(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean focused, boolean hovered) {
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean bright = focused || hovered;
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hovered ? SELECTION_FILL_HOVER : SELECTION_FILL);
        g.outline(x, y, w, h, bright ? ACCENT_LIGHT : ACCENT);
    }

    /** Faint hover wash for a non-selected row of a selectable list. */
    public static void drawRowHover(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x1ECC6600);
        g.outline(x, y, w, h, 0x70663D1A);
    }

    // ---------------------------------------------------------------- scrollbars

    public static void drawScrollTrack(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        g.fill(x, y, x + w, y + h, BG_WARM);
        g.fill(x, y, x + 1, y + h, BORDER_DARK);
    }

    /** Orange thumb; light orange while hovered/dragged, muted brown when disabled. */
    public static void drawScrollThumb(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hot, boolean disabled) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int body = disabled ? BORDER_DARK : (hot ? ACCENT_LIGHT : ACCENT);
        g.fill(x, y, x + w, y + h, body);
        if (w > 2 && h > 2) {
            // 1px darker right/bottom edge for a little depth.
            int edge = disabled ? 0xFF3A2C22 : BORDER;
            g.fill(x + w - 1, y, x + w, y + h, edge);
            g.fill(x, y + h - 1, x + w - 1, y + h, edge);
        }
    }

    // ---------------------------------------------------------------- private copies of MainMenuTheme's helpers

    private static void glow(GuiGraphicsExtractor g, int cx, int cy, double rx, double ry, int rgb, int maxAlpha, int layers) {
        if (rx < 1 || ry < 1 || maxAlpha <= 0) {
            return;
        }
        int perLayer = Math.max(1, maxAlpha / layers);
        int color = MainMenuTheme.argb(rgb, perLayer);
        for (int k = layers; k >= 1; k--) {
            double s = (double) k / layers;
            double lrx = rx * s;
            double lry = ry * s;
            int step = Math.max(2, (int) Math.ceil(lry / 14.0));
            int top = (int) Math.floor(cy - lry);
            int bottom = (int) Math.ceil(cy + lry);
            for (int y = top; y < bottom; y += step) {
                double dy = ((y + step * 0.5) - cy) / lry;
                double q = 1.0 - dy * dy;
                if (q <= 0) {
                    continue;
                }
                int half = (int) Math.round(lrx * Math.sqrt(q));
                if (half <= 0) {
                    continue;
                }
                g.fill(cx - half, y, cx + half, y + step, color);
            }
        }
    }

    private static void embers(GuiGraphicsExtractor g, int w, int h, double t, double density, double alphaScale) {
        int count = (int) Math.round(Math.max(24, Math.min(70, (w * h) / 3500)) * density);
        double travel = h + 24.0;
        for (int i = 0; i < count; i++) {
            double r1 = hash(i, 1);
            double r2 = hash(i, 2);
            double r3 = hash(i, 3);
            double speed = 5.0 + r2 * 13.0;
            double rise = (t * speed + r1 * travel * 11.0) % travel;
            double y = h + 12.0 - rise;
            double x = r3 * w + Math.sin(t * (0.25 + r1 * 0.45) + i * 1.3) * (5.0 + r2 * 12.0);
            double progress = rise / travel;
            double life = Math.min(1.0, progress * 8.0) * (1.0 - progress);
            double flicker = 0.7 + 0.3 * Math.sin(t * (2.0 + r3 * 2.5) + i * 1.7);
            int a = (int) (215 * life * flicker * alphaScale);
            if (a <= 4) {
                continue;
            }
            int px = (int) Math.round(x);
            int py = (int) Math.round(y);
            int size = r2 > 0.82 ? 2 : 1;
            g.fill(px - 1, py - 1, px + size + 1, py + size + 1, MainMenuTheme.argb(MainMenuTheme.ORANGE, a / 4));
            g.fill(px, py, px + size, py + size, MainMenuTheme.argb(r1 > 0.5 ? MainMenuTheme.LIGHT_ORANGE : MainMenuTheme.ORANGE, a));
        }
    }

    private static double hash(int i, int salt) {
        double v = Math.sin(i * 12.9898 + salt * 78.233) * 43758.5453;
        return v - Math.floor(v);
    }
}
