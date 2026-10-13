package com.killer560.hub.gui;

import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;

/**
 * The mod menu's look for the mod's other screens (Profile Viewer, Friends List, Swap Accounts, ...): the same bevelled
 * frame, header bar, recessed wells, cards, rows and raised buttons {@link ModScreen} draws, every colour taken from
 * {@link MenuStyle} so all five themes (Amber, Dark, Light, Ocean, Custom) reach them. Call {@link #backdrop} (which
 * derives the palette) first each frame. Text goes through {@link #text}, which keeps it readable on the Light theme.
 */
public final class ScreenChrome {

    private ScreenChrome() {
    }

    /** Height of {@link #header}'s bar; it sits 4 units inside the frame, so content can start at {@code y + 30}. */
    public static final int HEADER_H = 22;

    /** Derives the palette from the active theme and dims the whole screen behind the panel. */
    public static void backdrop(GuiGraphicsExtractor g, int w, int h) {
        MenuStyle.use();
        g.fill(0, 0, w, h, MenuStyle.BACKDROP);
    }

    /** The menu's outer panel: soft drop shadow, accent ring, bevelled panel gradient. */
    public static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        MenuStyle.use();
        GuiRects r = GuiRects.begin(g);
        for (int i = 6; i >= 1; i--) {
            int a = 0x18 + (6 - i) * 0x10;
            r.fill(x - i, y - i + 4, x + w + i, y + h + i + 4, MenuStyle.shadow(a / 2));
        }
        r.outline(x - 1, y - 1, w + 2, h + 2, MenuStyle.FRAME_RING);
        MenuStyle.bevel(r, x, y, w, h, MenuStyle.PANEL_TOP, MenuStyle.PANEL_BOTTOM);
        r.fill(x + 1, y + 1, x + w - 1, y + 2, MenuStyle.HILITE_SOFT);
        r.submit();
    }

    /** The header bar across the top of a {@link #frame}: a bevelled strip with the title in the brand gradient. */
    public static void header(GuiGraphicsExtractor g, int panelX, int panelY, int panelW, String title) {
        int x = panelX + 4;
        int y = panelY + 4;
        int w = panelW - 8;
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, x, y, w, HEADER_H, 2);
        MenuStyle.bevel(r, x, y, w, HEADER_H, MenuStyle.HEADER_TOP, MenuStyle.HEADER_BOTTOM);
        r.submit();
        MenuStyle.gradientText(g, title, x + 6, y + (HEADER_H - 8) / 2, 1f, MenuStyle.BRAND_TOP, MenuStyle.BRAND_BOTTOM);
    }

    /** Header text right-aligned inside the bar (a count, a hint), in {@code colour}. */
    public static void headerRight(GuiGraphicsExtractor g, int panelX, int panelY, int panelW, String s, int colour) {
        int w = MenuStyle.font().width(s);
        text(g, s, panelX + panelW - 10 - w, panelY + 4 + (HEADER_H - 8) / 2, colour);
    }

    /** A recessed well (a list's or a page's background). */
    public static void well(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.well(r, x, y, w, h, MenuStyle.WELL);
        r.submit();
    }

    /** A raised card (a content box), lighter when hovered. */
    public static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover) {
        GuiRects r = GuiRects.begin(g);
        card(r, x, y, w, h, hover);
        r.submit();
    }

    public static void card(GuiRects r, int x, int y, int w, int h, boolean hover) {
        MenuStyle.bevel(r, x, y, w, h, hover ? MenuStyle.CARD_HOVER_TOP : MenuStyle.CARD_TOP,
                hover ? MenuStyle.CARD_HOVER_BOTTOM : MenuStyle.CARD_BOTTOM);
    }

    /**
     * One list row inside a well: the selected row gets the sidebar's selected look (accent gradient, accent strip on its
     * left), a hovered one a faint band, every other row a fainter stripe; a separator closes each row.
     */
    public static void row(GuiRects r, int x, int y, int w, int h, boolean hover, boolean selected, boolean stripe) {
        if (selected) {
            MenuStyle.hGradient(r, x, y, x + w, y + h - 1, MenuStyle.SEL_TOP, MenuStyle.SEL_BOTTOM, 6);
            r.fill(x, y, x + 2, y + h - 1, MenuStyle.OR);
        } else if (hover) {
            r.fill(x, y, x + w, y + h - 1, MenuStyle.alpha(MenuStyle.TXT, 0x16));
            r.fill(x, y, x + 2, y + h - 1, MenuStyle.OR2);
        } else if (stripe) {
            r.fill(x, y, x + w, y + h - 1, MenuStyle.alpha(MenuStyle.TXT, 0x07));
        }
        r.fill(x + 2, y + h - 1, x + w - 2, y + h, MenuStyle.alpha(MenuStyle.BLACK, MenuStyle.LIGHT ? 0x40 : 0x80));
    }

    /** A hand-drawn raised button (one that scrolls under a scissor, where a widget would not clip), label centred. */
    public static void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean hover,
                              boolean selected) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.raisedButton(r, x + 1, y + 1, w - 2, h - 2, hover, selected);
        r.submit();
        String s = MenuStyle.ellipsize(label, w - 6);
        int c = selected || hover ? MenuStyle.OR_HOVER : MenuStyle.OR;
        text(g, s, x + (w - MenuStyle.font().width(s)) / 2, y + (h - 8) / 2, c);
    }

    /** A vertical scrollbar: recessed track and the accent thumb, the thumb's box given directly. */
    public static void scrollbar(GuiGraphicsExtractor g, int x, int y, int w, int h, int thumbY, int thumbH, boolean hot) {
        GuiRects r = GuiRects.begin(g);
        r.fill(x, y, x + w, y + h, MenuStyle.shadow(0x60));
        r.outline(x, y, w, h, MenuStyle.alpha(MenuStyle.BLACK, 0xA0));
        MenuStyle.glow(r, x + 1, thumbY, w - 2, thumbH, MenuStyle.OR, hot ? 0x40 : 0x20);
        MenuStyle.hGradient(r, x + 1, thumbY, x + w - 1, thumbY + thumbH, hot ? MenuStyle.OR_HOVER : MenuStyle.OR_LIGHT,
                MenuStyle.OR_DARK, Math.max(1, w - 2));
        r.fill(x + 1, thumbY, x + 2, thumbY + thumbH, 0x50FFFFFF);
        r.submit();
    }

    /** A progress bar: recessed track, the filled part in {@code fillColour} with a light top line. */
    public static void progress(GuiGraphicsExtractor g, int x, int y, int w, int h, double t, int fillColour) {
        GuiRects r = GuiRects.begin(g);
        r.fill(x, y, x + w, y + h, MenuStyle.TRACK);
        r.fill(x, y, x + w, y + 1, MenuStyle.shadow(0x70));
        int fw = (int) Math.round(w * Math.max(0, Math.min(1, t)));
        if (fw > 0) {
            r.gradient(x, y, x + fw, y + h, MenuStyle.mix(fillColour, 0xFFFFFFFF, MenuStyle.LIGHT ? 0.1f : 0.2f), fillColour);
        }
        r.submit();
    }

    /**
     * A colour that stays readable on the panel: on a light theme a bright colour (white, a rarity's yellow or aqua, the
     * old fixed palette's green) is darkened to about a third of white's luminance, keeping its hue. Dark themes keep it.
     */
    public static int readable(int argb) {
        if (!MenuStyle.LIGHT) {
            return argb;
        }
        float lum = MenuStyle.luminance(argb);
        if (lum <= 0.55f) {
            return argb;
        }
        return MenuStyle.shade(argb, 0.36f / lum);
    }

    /** Text in the theme: § colours swapped for readable partners and the base colour darkened on Light; shadow only on
     *  dark themes. */
    public static void text(GuiGraphicsExtractor g, String s, int x, int y, int colour) {
        g.text(MenuStyle.font(), MenuStyle.LIGHT ? MenuStyle.lightReadable(s) : s, x, y, readable(colour),
                MenuStyle.TEXT_SHADOW);
    }

    public static void text(GuiGraphicsExtractor g, FormattedCharSequence s, int x, int y, int colour) {
        g.text(MenuStyle.font(), s, x, y, readable(colour), MenuStyle.TEXT_SHADOW);
    }

    /** {@link #text} centred on {@code cx}. */
    public static void centered(GuiGraphicsExtractor g, String s, int cx, int y, int colour) {
        text(g, s, cx - MenuStyle.font().width(s) / 2, y, colour);
    }

    // ---- screen sweep C (2026-10-13): the pieces the sim / AP3 / editor screens add ------------------------------------

    /** A raised card; {@code selected} adds the accent glow and ring, a hover the dim accent ring. */
    public static void card(GuiRects r, int x, int y, int w, int h, boolean hover, boolean selected) {
        if (selected) {
            MenuStyle.glow(r, x, y, w, h, MenuStyle.OR, 0x50);
        }
        MenuStyle.dropShadow(r, x, y, w, h, 2);
        card(r, x, y, w, h, hover);
        if (selected || hover) {
            r.outline(x - 1, y - 1, w + 2, h + 2, selected ? MenuStyle.OR : MenuStyle.ACCENT_EDGE);
        }
    }

    /** {@link #header}'s bar alone, for a screen that writes its own title (centred, two lines, or cheat red). */
    public static void headerBar(GuiGraphicsExtractor g, int panelX, int panelY, int panelW) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, panelX + 4, panelY + 4, panelW - 8, HEADER_H, 2);
        MenuStyle.bevel(r, panelX + 4, panelY + 4, panelW - 8, HEADER_H, MenuStyle.HEADER_TOP, MenuStyle.HEADER_BOTTOM);
        r.submit();
    }

    /** A screen title: the brand gradient, or bold cheat red for a cheat-only screen. */
    public static void title(GuiGraphicsExtractor g, String s, int x, int y, boolean cheat) {
        if (cheat) {
            g.text(MenuStyle.font(), net.minecraft.network.chat.Component.literal(s)
                    .withStyle(net.minecraft.ChatFormatting.BOLD), x, y, MenuStyle.RED_TITLE, MenuStyle.TEXT_SHADOW);
            return;
        }
        MenuStyle.gradientText(g, s, x, y, 1f, MenuStyle.BRAND_TOP, MenuStyle.BRAND_BOTTOM);
    }

    /** {@link #title} centred on {@code cx}. */
    public static void titleCentred(GuiGraphicsExtractor g, String s, int cx, int y, boolean cheat) {
        int w = cheat ? MenuStyle.font().width(net.minecraft.network.chat.Component.literal(s)
                .withStyle(net.minecraft.ChatFormatting.BOLD)) : MenuStyle.font().width(s);
        title(g, s, cx - w / 2, y, cheat);
    }

    /** A bold section label in the accent (or cheat red) with a separator running on to {@code x1}. */
    public static void section(GuiGraphicsExtractor g, String label, int x, int y, int x1, boolean red) {
        net.minecraft.network.chat.Component c = net.minecraft.network.chat.Component.literal(label)
                .withStyle(net.minecraft.ChatFormatting.BOLD);
        g.text(MenuStyle.font(), c, x, y, red ? MenuStyle.RED_TITLE : MenuStyle.OR, MenuStyle.TEXT_SHADOW);
        int tw = MenuStyle.font().width(c);
        if (x + tw + 6 < x1) {
            GuiRects r = GuiRects.begin(g);
            MenuStyle.separator(r, x + tw + 6, x1, y + 4);
            r.submit();
        }
    }

    /** A hover description box in the menu's tooltip look; {@code lines} already wrapped, {@code w}/{@code h} their size. */
    public static void tooltip(GuiGraphicsExtractor g, java.util.List<FormattedCharSequence> lines, int x, int y, int w,
                               int h) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, x - 4, y - 4, w + 8, h + 8, 3);
        MenuStyle.bevel(r, x - 4, y - 4, w + 8, h + 8, MenuStyle.TOOLTIP_TOP, MenuStyle.TOOLTIP_BOTTOM);
        r.outline(x - 5, y - 5, w + 10, h + 10, MenuStyle.ACCENT_EDGE);
        r.submit();
        int ly = y;
        for (FormattedCharSequence line : lines) {
            text(g, line, x, ly, MenuStyle.TXT);
            ly += 10;
        }
    }
}
