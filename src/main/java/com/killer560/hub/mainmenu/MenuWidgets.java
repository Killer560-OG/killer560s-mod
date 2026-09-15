package com.killer560.hub.mainmenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Themed drawing for the shared vanilla interactive widgets (text fields, sliders, checkboxes, tabs, the
 * difficulty lock button, tooltips) on every menu covered by {@link MainMenuTheme#activeOnMenus()}.
 * <p>
 * The mixins in {@code mainmenu.mixin} (MenuEditBoxMixin, MenuSliderMixin, MenuCheckboxMixin, MenuTabButtonMixin,
 * MenuTabNavigationBarMixin, MenuLockIconButtonMixin, MenuTextAreaMixin, MenuTooltipMixin, MenuTextAndIconButtonMixin)
 * only decide WHEN to call these; every draw here is a handful of flat fills (no textures, no allocation), so the
 * per-frame cost is negligible. Buttons themselves reuse {@link MainMenuTheme#drawButtonBox}.
 * <p>
 * Palette matches MainMenuTheme / SettingsButtonWidget / ModScreen.
 */
public final class MenuWidgets {

    public static final int BG = 0xFF1A1A1A;
    public static final int BG_HOVER = 0xFF262626;
    public static final int BG_WARM = 0xFF1A1108;
    public static final int BG_DISABLED = 0xFF121212;
    public static final int BORDER = 0xFF663D1A;
    public static final int BORDER_DARK = 0xFF553311;
    public static final int BORDER_DISABLED = 0xFF3A2C22;
    public static final int ACCENT = 0xFF000000 | MainMenuTheme.ORANGE;
    public static final int ACCENT_LIGHT = 0xFF000000 | MainMenuTheme.LIGHT_ORANGE;
    /** "Already dragged past" strip on slider tracks (same as ThemedSliderButton). */
    public static final int FILLED = 0xFF3D2A14;
    public static final int TOOLTIP_BG = 0xF0100C08;

    private MenuWidgets() {
    }

    /** Widget scope - exactly {@link MainMenuTheme#activeOnMenus()}. */
    public static boolean active() {
        return MainMenuTheme.activeOnMenus();
    }

    /** Tooltip scope: exactly {@link #active()} - chat screens (whose hover tooltips are server item/entity
     *  hover events) are already excluded by {@link MainMenuTheme#activeOnMenus()}, same as the in-world HUD
     *  and container screens. */
    public static boolean tooltipsActive() {
        return MainMenuTheme.activeOnMenus();
    }

    // ---------------------------------------------------------------- text fields

    /** Bordered EditBox / multi-line text area frame: dark field, dim orange border, full orange when focused. */
    public static void drawTextField(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean active, boolean focused) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int bg = active ? BG : BG_DISABLED;
        int border = !active ? BORDER_DISABLED : (focused ? ACCENT : BORDER);
        g.fill(x, y, x + w, y + h, bg);
        g.outline(x, y, w, h, border);
    }

    // ---------------------------------------------------------------- sliders

    /** Slider track: the themed button box plus a warm "filled" strip from the left edge up to the handle. */
    public static void drawSliderTrack(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean active,
                                       boolean highlighted, float alpha, double value, int handleWidth) {
        if (w <= 0 || h <= 0) {
            return;
        }
        MainMenuTheme.drawButtonBox(g, x, y, w, h, active, highlighted, alpha);
        if (active && h > 2) {
            double v = Math.max(0.0, Math.min(1.0, value));
            int handleX = x + (int) (v * (w - handleWidth));
            if (handleX > x + 1) {
                g.fill(x + 1, y + 1, handleX, y + h - 1, MainMenuTheme.fade(FILLED, alpha));
            }
        }
    }

    /** Slider handle: solid orange knob (light orange while hovered/dragged) with a darker 1px rim. */
    public static void drawSliderHandle(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean active,
                                        boolean highlighted, float alpha) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int fillColor = !active ? BORDER_DISABLED : (highlighted ? ACCENT_LIGHT : ACCENT);
        int rim = !active ? BG_DISABLED : BORDER_DARK;
        g.fill(x, y, x + w, y + h, MainMenuTheme.fade(rim, alpha));
        if (w > 2 && h > 2) {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, MainMenuTheme.fade(fillColor, alpha));
        }
    }

    // ---------------------------------------------------------------- checkboxes

    /** Checkbox square with an orange 2px check mark when selected. */
    public static void drawCheckbox(GuiGraphicsExtractor g, int x, int y, int size, boolean active,
                                    boolean highlighted, boolean selected, float alpha) {
        if (size <= 0) {
            return;
        }
        MainMenuTheme.drawButtonBox(g, x, y, size, size, active, highlighted, alpha);
        if (!selected) {
            return;
        }
        int color = MainMenuTheme.fade(!active ? 0xFF7A5A3A : (highlighted ? ACCENT_LIGHT : ACCENT), alpha);
        int t = size >= 12 ? 2 : 1;
        int ax = x + Math.round(size * 0.22f);
        int ay = y + Math.round(size * 0.50f);
        int bx = x + Math.round(size * 0.42f);
        int by = y + Math.round(size * 0.70f);
        int cx = x + Math.round(size * 0.76f);
        int cy = y + Math.round(size * 0.24f);
        thickLine(g, ax, ay, bx, by, t, color);
        thickLine(g, bx, by, cx, cy, t, color);
    }

    /** Bresenham line stamped with t x t squares (a checkmark is ~12 short segments). */
    private static void thickLine(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int t, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int guard = 0;
        while (guard++ < 256) {
            g.fill(x0, y0, x0 + t, y0 + t, color);
            if (x0 == x1 && y0 == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    // ---------------------------------------------------------------- tabs

    /** Tab button: the selected tab is full height, warm dark, orange top/sides and open at the bottom so it
     *  merges with the content; unselected tabs sit 2px lower as closed boxes whose bottom edge lines up with
     *  the tab bar separator line ({@link #drawTabSeparator}). */
    public static void drawTab(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean active,
                               boolean selected, boolean highlighted) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (selected) {
            int border = !active ? BORDER_DISABLED : (highlighted ? ACCENT_LIGHT : ACCENT);
            g.fill(x, y, x + w, y + h, active ? BG_WARM : BG_DISABLED);
            g.fill(x, y, x + w, y + 1, border);
            g.fill(x, y + 1, x + 1, y + h, border);
            g.fill(x + w - 1, y + 1, x + w, y + h, border);
            return;
        }
        int top = y + 2;
        int bh = h - 2;
        if (bh <= 0) {
            return;
        }
        int bg = !active ? BG_DISABLED : (highlighted ? BG_HOVER : BG);
        int border = !active ? BORDER_DISABLED : (highlighted ? ACCENT : BORDER);
        g.fill(x, top, x + w, top + bh, bg);
        g.outline(x, top, w, bh, border);
    }

    /** Replaces the 2px header-separator strips either side of the tab row with a single border-coloured
     *  line on the bottom row (aligned with the unselected tabs' bottom edge). */
    public static void drawTabSeparator(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        g.fill(x, y + h - 1, x + w, y + h, BORDER);
    }

    // ---------------------------------------------------------------- difficulty lock button

    /** Themed box plus a small padlock glyph: closed and orange when locked, open and dim when unlocked. */
    public static void drawLockButton(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean active,
                                      boolean highlighted, boolean locked, float alpha) {
        if (w <= 0 || h <= 0) {
            return;
        }
        MainMenuTheme.drawButtonBox(g, x, y, w, h, active, highlighted, alpha);
        int color;
        if (!active) {
            color = locked ? 0xFF7A5A3A : BORDER_DISABLED;
        } else if (locked) {
            color = highlighted ? ACCENT_LIGHT : ACCENT;
        } else {
            color = highlighted ? ACCENT_LIGHT : (0xFF000000 | MainMenuTheme.DIM);
        }
        color = MainMenuTheme.fade(color, alpha);
        int cx = x + w / 2;
        int cy = y + h / 2;
        // Body 8x6 with a 1x2 keyhole.
        g.fill(cx - 4, cy - 1, cx + 4, cy + 5, color);
        g.fill(cx - 1, cy + 1, cx + 1, cy + 3, MainMenuTheme.fade(BG, alpha));
        // Shackle: closed = both legs down into the body; open = lifted 2px, right leg short of the body.
        int lift = locked ? 0 : 2;
        int topY = cy - 5 - lift;
        g.fill(cx - 3, topY, cx + 3, topY + 1, color);
        g.fill(cx - 3, topY + 1, cx - 2, cy - 1, color);
        g.fill(cx + 2, topY + 1, cx + 3, locked ? cy - 1 : topY + 3, color);
    }

    // ---------------------------------------------------------------- tooltips

    /** Tooltip background for content at (x, y, w, h): same 4px footprint as vanilla's frame, warm near-black
     *  fill and a 1px orange border (matches ModScreen's setting tooltips). */
    public static void drawTooltip(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x - 3, y - 3, x + w + 3, y + h + 3, TOOLTIP_BG);
        g.outline(x - 4, y - 4, w + 8, h + 8, ACCENT);
    }
}
