package com.killer560.hub.gui;

import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The menu's chrome for the mod's other screens (screen sweep, killer560 2026-10-13: "make the theme for each match the
 * gui's theme. Then redo them to also look in a similar manner"). Every colour comes from {@link MenuStyle}, which
 * {@link #begin} re-derives from the active {@link MenuTheme}, so Amber, Dark, Light, Ocean and Custom all reach these
 * screens: a bevelled panel with the menu frame's ring and shadow, a header bar, recessed wells for lists and grids,
 * cards and selectable rows, and text helpers that swap washed-out legacy colours for darker ones on a light theme.
 * <p>
 * The panel each screen drew last is remembered by the screen's class name ({@link #probe}) so the testkit can sample a
 * pixel of it and prove it followed the theme.
 */
public final class ToolScreenKit {

    private ToolScreenKit() {
    }

    /** Header bar height inside a panel. */
    public static final int HEADER_H = 24;

    private static final Map<String, int[]> PANELS = new ConcurrentHashMap<>();
    private static final Map<String, MenuTheme> THEMES = new ConcurrentHashMap<>();

    /** The theme {@code s} draws in: its own Theme setting, else the menu's ({@link MenuTheme#forScreen}). */
    public static MenuTheme themeOf(Screen s) {
        return MenuTheme.forScreen(s instanceof MenuThemedScreen m ? m.themeId() : null);
    }

    /** Re-derives the palette from the screen's theme and, when {@code dim}, dims the world behind the screen. */
    public static void begin(GuiGraphicsExtractor g, Screen s, boolean dim) {
        MenuStyle.use(themeOf(s));
        if (dim) {
            g.fill(0, 0, s.width, s.height, MenuStyle.BACKDROP);
        }
    }

    /** The menu's main frame: drop shadow, accent ring, bevelled panel gradient. Recorded for {@link #probe}. */
    public static void panel(GuiGraphicsExtractor g, Screen owner, int x, int y, int w, int h) {
        GuiRects r = GuiRects.begin(g);
        for (int i = 4; i >= 1; i--) {
            int a = 0x18 + (4 - i) * 0x14;
            r.fill(x - i, y - i + 3, x + w + i, y + h + i + 3, MenuStyle.shadow(a / 2));
        }
        r.outline(x - 1, y - 1, w + 2, h + 2, MenuStyle.FRAME_RING);
        MenuStyle.bevel(r, x, y, w, h, MenuStyle.PANEL_TOP, MenuStyle.PANEL_BOTTOM);
        r.fill(x + 1, y + 1, x + w - 1, y + 2, MenuStyle.HILITE_SOFT);
        r.submit();
        if (owner != null) {
            PANELS.put(owner.getClass().getSimpleName(), new int[]{x, y, w, h});
            THEMES.put(owner.getClass().getSimpleName(), themeOf(owner));
        }
    }

    /** A smaller raised box (a legend, a hint chip): the card colours, no ring. Recorded for {@link #probe} when owned. */
    public static void box(GuiGraphicsExtractor g, Screen owner, int x, int y, int w, int h) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, x, y, w, h, 2);
        MenuStyle.bevel(r, x, y, w, h, MenuStyle.BOX_TOP, MenuStyle.BOX_BOTTOM);
        r.submit();
        if (owner != null) {
            PANELS.put(owner.getClass().getSimpleName(), new int[]{x, y, w, h, 1});
            THEMES.put(owner.getClass().getSimpleName(), themeOf(owner));
        }
    }

    /**
     * The point the testkit samples for {@code screenName}'s last panel and the colour the theme it was drawn in puts
     * there: {x, y, argb, theme ordinal}, or null when that screen has drawn no panel. Just inside the bottom-left bevel, where every screen
     * leaves the panel bare.
     */
    public static int[] probe(String screenName) {
        int[] p = PANELS.get(screenName);
        if (p == null) {
            return null;
        }
        MenuTheme theme = THEMES.get(screenName);
        MenuStyle.use(theme);
        boolean box = p.length > 4;
        int top = box ? MenuStyle.BOX_TOP : MenuStyle.PANEL_TOP;
        int bottom = box ? MenuStyle.BOX_BOTTOM : MenuStyle.PANEL_BOTTOM;
        int px = p[0] + 3;
        int py = p[1] + p[3] - (box ? 3 : 4);
        float t = (py + 0.5f - (p[1] + 1)) / Math.max(1f, p[3] - 2);
        int argb = MenuStyle.mix(top, bottom, t);
        MenuStyle.use();
        return new int[]{px, py, argb, theme == null ? -1 : theme.ordinal()};
    }

    /** The header bar across the top of a panel ({@link #HEADER_H} tall, inset 3). @return the y below it. */
    public static int header(GuiGraphicsExtractor g, int panelX, int panelY, int panelW) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.bevel(r, panelX + 3, panelY + 3, panelW - 6, HEADER_H, MenuStyle.HEADER_TOP, MenuStyle.HEADER_BOTTOM);
        r.submit();
        return panelY + 3 + HEADER_H;
    }

    /** The screen title in the header: the brand gradient, or cheat red for a cheat-only screen. */
    public static void title(GuiGraphicsExtractor g, String s, int x, int y, boolean cheat) {
        if (cheat) {
            MenuStyle.text(g, net.minecraft.network.chat.Component.literal(s)
                    .withStyle(net.minecraft.ChatFormatting.BOLD), x, y, MenuStyle.RED_TITLE);
            return;
        }
        MenuStyle.gradientText(g, s, x, y, 1f, MenuStyle.BRAND_TOP, MenuStyle.BRAND_BOTTOM);
    }

    /** A title centred on {@code cx}. */
    public static void titleCentred(GuiGraphicsExtractor g, String s, int cx, int y, boolean cheat) {
        int w = MenuStyle.font().width(s) + (cheat ? s.length() : 0); // bold adds a pixel a glyph
        title(g, s, cx - w / 2, y, cheat);
    }

    /** A recessed well (a list, a grid). */
    public static void well(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.well(r, x, y, w, h, MenuStyle.WELL);
        r.submit();
    }

    /** A bevelled card; {@code selected} adds the accent glow and ring. */
    public static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover, boolean selected) {
        GuiRects r = GuiRects.begin(g);
        card(r, x, y, w, h, hover, selected);
        r.submit();
    }

    public static void card(GuiRects r, int x, int y, int w, int h, boolean hover, boolean selected) {
        if (selected) {
            MenuStyle.glow(r, x, y, w, h, MenuStyle.OR, 0x50);
        }
        MenuStyle.dropShadow(r, x, y, w, h, 2);
        MenuStyle.bevel(r, x, y, w, h, hover ? MenuStyle.CARD_HOVER_TOP : MenuStyle.CARD_TOP,
                hover ? MenuStyle.CARD_HOVER_BOTTOM : MenuStyle.CARD_BOTTOM);
        if (selected || hover) {
            r.outline(x - 1, y - 1, w + 2, h + 2, selected ? MenuStyle.OR : MenuStyle.ACCENT_EDGE);
        }
    }

    /** A list row's background inside a well: the selected gradient with an accent edge, or a faint hover band. */
    public static void row(GuiRects r, int x, int y, int w, int h, boolean hover, boolean selected) {
        if (selected) {
            r.gradient(x, y, x + w, y + h, MenuStyle.SEL_TOP, MenuStyle.SEL_BOTTOM);
            r.fill(x, y, x + 2, y + h, MenuStyle.OR);
        } else if (hover) {
            r.fill(x, y, x + w, y + h, MenuStyle.alpha(MenuStyle.TXT, MenuStyle.LIGHT ? 0x18 : 0x10));
        }
    }

    /** A section label in the accent colour with a separator running to {@code x1}. @return the text's height + gap. */
    public static void section(GuiGraphicsExtractor g, String label, int x, int y, int x1, boolean red) {
        net.minecraft.network.chat.Component c = net.minecraft.network.chat.Component.literal(label)
                .withStyle(net.minecraft.ChatFormatting.BOLD);
        MenuStyle.text(g, c, x, y, red ? MenuStyle.RED_TITLE : MenuStyle.OR);
        int tw = MenuStyle.font().width(c);
        if (x + tw + 6 < x1) {
            GuiRects r = GuiRects.begin(g);
            MenuStyle.separator(r, x + tw + 6, x1, y + 4);
            r.submit();
        }
    }

    /** A themed tooltip box with these lines (already wrapped). */
    public static void tooltip(GuiGraphicsExtractor g, java.util.List<net.minecraft.util.FormattedCharSequence> lines,
                               int x, int y, int w, int h) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, x - 4, y - 4, w + 8, h + 8, 3);
        MenuStyle.bevel(r, x - 4, y - 4, w + 8, h + 8, MenuStyle.TOOLTIP_TOP, MenuStyle.TOOLTIP_BOTTOM);
        r.outline(x - 5, y - 5, w + 10, h + 10, MenuStyle.ACCENT_EDGE);
        r.submit();
        int ly = y;
        for (var line : lines) {
            MenuStyle.text(g, line, x, ly, MenuStyle.TXT);
            ly += 10;
        }
    }

    /** A recessed progress bar with the accent fill. */
    public static void progress(GuiGraphicsExtractor g, int x, int y, int w, int h, float t) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.well(r, x, y, w, h, MenuStyle.TRACK);
        int filled = Math.max(0, Math.min(w - 2, Math.round((w - 2) * t)));
        if (filled > 0) {
            MenuStyle.glow(r, x + 1, y + 1, filled, h - 2, MenuStyle.OR, 0x30);
            r.gradient(x + 1, y + 1, x + 1 + filled, y + h - 1, MenuStyle.OR_LIGHT, MenuStyle.OR_DARK);
            r.fill(x + 1, y + 1, x + 1 + filled, y + 2, 0x50FFFFFF);
        }
        r.submit();
    }

    // ---- text --------------------------------------------------------------------------------------------------------

    /** {@code s} with its washed-out legacy colour codes swapped for darker ones on a light theme. */
    public static String readable(String s) {
        return MenuStyle.LIGHT ? MenuStyle.lightReadable(s) : s;
    }

    /** A colour that reads on the panel: a light colour (a class colour, a status green) darkened on a light theme. */
    public static int onPanel(int argb) {
        if (!MenuStyle.LIGHT || MenuStyle.luminance(argb) < 0.55f) {
            return argb;
        }
        return MenuStyle.mix(argb | 0xFF000000, 0xFF000000, 0.45f);
    }

    /** "Good" green and "bad" red that read on the theme's panel. */
    public static int good() {
        return MenuStyle.LIGHT ? 0xFF2E7D32 : 0xFF55FF55;
    }

    public static int bad() {
        return MenuStyle.RED;
    }

    public static void text(GuiGraphicsExtractor g, String s, int x, int y, int colour) {
        MenuStyle.text(g, readable(s), x, y, colour);
    }

    public static void centred(GuiGraphicsExtractor g, String s, int cx, int y, int colour) {
        String r = readable(s);
        MenuStyle.text(g, r, cx - MenuStyle.font().width(r) / 2, y, colour);
    }

    public static void right(GuiGraphicsExtractor g, String s, int x1, int y, int colour) {
        String r = readable(s);
        MenuStyle.text(g, r, x1 - MenuStyle.font().width(r), y, colour);
    }
}
