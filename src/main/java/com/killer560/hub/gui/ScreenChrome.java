package com.killer560.hub.gui;

import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

/**
 * The pieces a standalone {@link ThemedScreen} is drawn from, all in the menu's active theme ({@link MenuStyle} after
 * {@link #begin()}): the dimmed backdrop, the bevelled frame with its ring and drop shadow, the header bar with the
 * gradient title, recessed wells for lists and fields, raised cards, list rows, item slots, the scrollbar, and the
 * colours a screen's own text takes (good / bad / gold, and anything Hypixel or a § code coloured, made readable on
 * Light). Every colour comes from the theme, so Amber, Dark, Light, Ocean and Custom all reach these screens.
 */
public final class ScreenChrome {

    private ScreenChrome() {
    }

    /** Height of {@link #header}. */
    public static final int HEADER_H = 24;

    /** Derives the palette for screen {@code screenId} (its Screen Theme, or the menu's). Call first thing in a draw. */
    public static void begin(String screenId) {
        MenuStyle.use(MenuTheme.forScreen(screenId));
    }

    public static void backdrop(GuiGraphicsExtractor g, int w, int h) {
        g.fill(0, 0, w, h, MenuStyle.BACKDROP);
    }

    /** The outer panel: soft drop shadow, the accent-tinted ring, then the bevelled panel. The shadow stays within 3
     *  units of the panel: the Bazaar and AH reskins keep only a 4-8 unit margin, which must stay pure backdrop over the
     *  hotbar (testkit 496). */
    public static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        GuiRects r = GuiRects.begin(g);
        for (int i = 2; i >= 1; i--) {
            r.fill(x - i, y - i + 1, x + w + i, y + h + i + 1, MenuStyle.shadow((0x28 + (2 - i) * 0x18) / 2));
        }
        r.outline(x - 1, y - 1, w + 2, h + 2, MenuStyle.FRAME_RING);
        MenuStyle.bevel(r, x, y, w, h, MenuStyle.PANEL_TOP, MenuStyle.PANEL_BOTTOM);
        r.fill(x + 1, y + 1, x + w - 1, y + 2, MenuStyle.HILITE_SOFT);
        r.submit();
    }

    /** A bevelled header bar with {@code title} in the brand gradient at its left. */
    public static void header(GuiGraphicsExtractor g, int x, int y, int w, int h, String title) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, x, y, w, h, 2);
        MenuStyle.bevel(r, x, y, w, h, MenuStyle.HEADER_TOP, MenuStyle.HEADER_BOTTOM);
        r.submit();
        if (title != null && !title.isEmpty()) {
            MenuStyle.gradientText(g, title, x + 8, y + (h - 8) / 2, 1f, MenuStyle.BRAND_TOP, MenuStyle.BRAND_BOTTOM);
        }
    }

    /** A recessed well (lists, previews, graphs). */
    public static void well(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.well(r, x, y, w, h, MenuStyle.WELL);
        r.submit();
    }

    /** A raised card; {@code hover} lifts it and rings it in the accent. */
    public static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, x, y, w, h, 2);
        if (hover) {
            MenuStyle.glow(r, x, y, w, h, MenuStyle.OR, 0x30);
        }
        MenuStyle.bevel(r, x, y, w, h, hover ? MenuStyle.CARD_HOVER_TOP : MenuStyle.CARD_TOP,
                hover ? MenuStyle.CARD_HOVER_BOTTOM : MenuStyle.CARD_BOTTOM);
        if (hover) {
            r.outline(x, y, w, h, MenuStyle.ACCENT_EDGE);
        }
        r.submit();
    }

    /** A card's accent stripe down its left edge (inside the outline). */
    public static void stripe(GuiGraphicsExtractor g, int x, int y, int h, int color) {
        g.fill(x + 1, y + 1, x + 3, y + h - 1, color);
    }

    /**
     * One list row's background: selected rows get the accent band with a bar on the left, a hovered row a faint band,
     * every other row a barely-there stripe so long lists stay readable.
     */
    public static void row(GuiGraphicsExtractor g, int x, int y, int w, int h, int index, boolean hover, boolean selected) {
        GuiRects r = GuiRects.begin(g);
        if (selected) {
            r.gradient(x, y, x + w, y + h, MenuStyle.SEL_TOP, MenuStyle.SEL_BOTTOM);
            r.fill(x, y, x + 2, y + h, MenuStyle.OR);
            if (hover) {
                r.fill(x + 2, y, x + w, y + h, MenuStyle.alpha(MenuStyle.TXT, 0x10));
            }
        } else if (hover) {
            r.fill(x, y, x + w, y + h, MenuStyle.alpha(MenuStyle.TXT, MenuStyle.LIGHT ? 0x14 : 0x12));
            r.outline(x, y, w, h, MenuStyle.ACCENT_EDGE);
        } else if (index % 2 == 1) {
            r.fill(x, y, x + w, y + h, MenuStyle.alpha(MenuStyle.TXT, MenuStyle.LIGHT ? 0x0A : 0x07));
        }
        r.submit();
    }

    /** An item slot: a small recessed square; {@code selected} rings it in the accent, {@code hover} lightens it. */
    public static void slot(GuiGraphicsExtractor g, int x, int y, int size, boolean selected, boolean hover) {
        GuiRects r = GuiRects.begin(g);
        r.fill(x + 1, y + 1, x + size - 1, y + size - 1, selected ? MenuStyle.SEL_TOP : MenuStyle.WELL);
        r.outline(x, y, size, size, selected ? MenuStyle.OR : MenuStyle.BLACK);
        r.fill(x + 1, y + 1, x + size - 1, y + 2, MenuStyle.shadow(0x60));
        r.fill(x + 1, y + 2, x + 2, y + size - 1, MenuStyle.shadow(0x40));
        if (hover) {
            r.fill(x + 1, y + 1, x + size - 1, y + size - 1, MenuStyle.alpha(MenuStyle.TXT, 0x30));
        }
        r.submit();
    }

    /** The accent scrollbar in its track, at {@code x} (5 wide). */
    public static void scrollbar(GuiGraphicsExtractor g, int x, int top, int height, int offset, int max, int view) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.scrollbar(r, x, top, height, offset, max, view);
        r.submit();
    }

    /** A thin separator line across a panel. */
    public static void separator(GuiGraphicsExtractor g, int x0, int x1, int y) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.separator(r, x0, x1, y);
        r.submit();
    }

    // ---- text field -----------------------------------------------------------------------------------------------

    /** Makes a vanilla box draw like the menu's search: no border of its own, theme text, no text shadow on Light.
     *  Place it inside its well with {@link #fieldInset}. */
    public static void styleField(EditBox box) {
        box.setBordered(false);
        box.setTextColor(MenuStyle.TXT);
        box.setTextShadow(MenuStyle.TEXT_SHADOW);
    }

    /** Where a borderless box goes inside a well of {x, y, w, h}: {x, y, w, h}. */
    public static int[] fieldInset(int x, int y, int w, int h) {
        return new int[]{x + 5, y + (h - 8) / 2, Math.max(8, w - 10), 10};
    }

    /** The well under a borderless box (outlined in the accent while focused) and its hint while empty, painted here:
     *  the box's own hint carries a text shadow, which smears on Light. */
    public static void fieldWell(GuiGraphicsExtractor g, int x, int y, int w, int h, EditBox box, String hint,
            boolean hover) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.well(r, x, y, w, h, MenuStyle.WELL_DEEP);
        boolean focus = box != null && box.isFocused();
        if (focus || hover) {
            r.outline(x - 1, y - 1, w + 2, h + 2, focus ? MenuStyle.OR2 : MenuStyle.KEY_CHIP_EDGE);
        }
        r.submit();
        if (box != null) {
            box.setTextColor(box.active ? MenuStyle.TXT : MenuStyle.DIM2);
            box.setTextShadow(MenuStyle.TEXT_SHADOW);
        }
        if (hint != null && box != null && box.getValue().isEmpty() && !box.isFocused()) {
            MenuStyle.flat(g, MenuStyle.ellipsize(hint, w - 10), x + 5, y + (h - 8) / 2, MenuStyle.DIM2);
        }
    }

    // ---- colours --------------------------------------------------------------------------------------------------

    /** A profit / "it worked" green that reads on the panel. */
    public static int good() {
        return MenuStyle.LIGHT ? 0xFF1E7A2E : 0xFF4ADE80;
    }

    /** A loss / error red that reads on the panel. */
    public static int bad() {
        return MenuStyle.LIGHT ? 0xFFB02A2A : 0xFFEF5555;
    }

    /** Coins gold that reads on the panel. */
    public static int gold() {
        return MenuStyle.LIGHT ? 0xFF8A5A00 : 0xFFFFC040;
    }

    /** {@code argb} made readable on the panel: a pale colour (white, yellow, aqua...) darkened on Light; unchanged on a
     *  dark theme. White text becomes the theme's text colour. */
    public static int readable(int argb) {
        if (!MenuStyle.LIGHT) {
            return argb;
        }
        int r = (argb >> 16) & 0xFF;
        int gr = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r > 0xC8 && gr > 0xC8 && b > 0xC8) {
            return (argb & 0xFF000000) | (MenuStyle.TXT & 0xFFFFFF);
        }
        if (MenuStyle.luminance(argb | 0xFF000000) < 0.45f) {
            return argb;
        }
        return (argb & 0xFF000000) | ((r * 45 / 100) << 16) | ((gr * 45 / 100) << 8) | (b * 45 / 100);
    }

    /** A § coded line made readable on the panel (Light swaps the pale codes for darker partners). */
    public static String readable(String raw) {
        return MenuStyle.LIGHT ? MenuStyle.lightReadable(raw) : raw;
    }

    /** Styled text (an item name in Hypixel's colours) made readable on the panel. */
    public static FormattedCharSequence readable(Component c) {
        FormattedCharSequence s = c.getVisualOrderText();
        if (!MenuStyle.LIGHT) {
            return s;
        }
        return sink -> s.accept((index, style, cp) -> {
            TextColor col = style.getColor();
            int rgb = col == null ? (MenuStyle.TXT & 0xFFFFFF) : col.getValue();
            Style dark = style.withColor(TextColor.fromRgb(readable(0xFF000000 | rgb) & 0xFFFFFF));
            return sink.accept(index, dark, cp);
        });
    }

    /** Theme text with the theme's shadow setting. */
    public static void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        MenuStyle.text(g, s, x, y, color);
    }

    public static void text(GuiGraphicsExtractor g, FormattedCharSequence s, int x, int y, int color) {
        MenuStyle.text(g, s, x, y, color);
    }
}
