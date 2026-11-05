package com.killer560.hub.profileviewer.screen;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.profileviewer.ProfileViewerConfig.Style;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The shapes of the /pv Styles other than Default (killer560, 2026-11-04), drawn with fills only - no texture, nothing
 * allocated per frame. NEU Classic recreates NotEnoughUpdates' 1.8.9 profile viewer (its 431 x 202 panel: a black edge
 * with cut corners, a 2 px light top-left and dark bottom-right bevel over a smoky fill, 28 px icon tabs along the
 * top); the SkyBlockPV styles recreate that mod's Default, Dark and NEU themes (2 px framed plate, thin rounded boxes,
 * 20 px icon tabs whose selected one is green). Sizes and colors were read off their sprites; no code or texture of
 * either mod is used.
 */
final class PvStyleChrome {

    private PvStyleChrome() {
    }

    private static final int NEU_LIGHT = 0xFF555555;
    private static final int NEU_DARK = 0xFF2C2C2C;
    private static final int NEU_FILL = 0xE8222222;
    private static final int SPV_OUTLINE = 0xFF1E1E1F;

    /** How far the icon tabs stand above the panel. */
    static int tabH(Style s) { return s == Style.NEU ? 28 : 24; }

    static int tabW(Style s) { return s == Style.NEU ? 28 : 20; }

    static int tabStep(Style s) { return s == Style.NEU ? 28 : 22; }

    /** The first tab's x inside the panel. */
    static int tabX0(Style s) { return s == Style.NEU ? 0 : 20; }

    static void frame(GuiRects r, Style s, int x, int y, int w, int h) {
        switch (s) {
            case NEU -> neuBevel(r, x, y, w, h, NEU_FILL, 2);
            case SBPV_LIGHT -> plate(r, x, y, w, h, 0xFFC0C0C0, 0xFFA0A0A0, 2);
            case SBPV_DARK -> plate(r, x, y, w, h, 0xFF303030, 0xFF212121, 2);
            default -> plate(r, x, y, w, h, 0xC0262626, 0xFF1C1C1C, 2);
        }
    }

    /** A content box: NEU's thin bevel, or SkyBlockPV's thin rounded box. */
    static void box(GuiRects r, Style s, int x, int y, int w, int h) {
        switch (s) {
            case NEU -> neuBevel(r, x, y, w, h, 0x50101010, 1);
            case SBPV_LIGHT -> plate(r, x, y, w, h, 0xFFC8C8C8, 0xFFA0A0A0, 1);
            case SBPV_DARK -> plate(r, x, y, w, h, 0xFF2D2D2D, 0xFF212121, 1);
            default -> plate(r, x, y, w, h, 0x4D262626, 0xFF1C1C1C, 1);
        }
    }

    static void button(GuiGraphicsExtractor g, Style s, int x, int y, int w, int h, String label, boolean hover, boolean selected) {
        GuiRects r = GuiRects.begin(g);
        boolean lit = hover || selected;
        switch (s) {
            case SBPV_LIGHT -> raised(r, x, y, w, h, lit ? 0xFFF4F4F5 : 0xFFE3E3E5, 0xFFECEDEE, 0xFF58585A);
            case SBPV_DARK -> raised(r, x, y, w, h, lit ? 0xFF7A7A7A : 0xFF656565, 0xFF707070, 0xFF272727);
            default -> neuBevel(r, x, y, w, h, lit ? 0xFF4A4A4A : 0xFF383838, 1);
        }
        r.submit();
        String t = MenuStyle.ellipsize(label, w - 6);
        ScreenChrome.text(g, t, x + (w - MenuStyle.font().width(t)) / 2, y + (h - 8) / 2 - (s == Style.SBPV_LIGHT || s == Style.SBPV_DARK ? 1 : 0),
                lit ? MenuStyle.OR_HOVER : MenuStyle.OR);
    }

    /** One icon tab standing on the panel's top edge at {@code y + tabH}; the selected one is taller and open at the
     *  bottom so it runs into the panel. */
    static void tab(GuiRects r, Style s, int x, int y, boolean selected, boolean hover) {
        int w = tabW(s);
        int h = tabH(s);
        int top = selected ? y : y + 3;
        int bottom = y + h + (selected ? 2 : 0);
        if (s == Style.NEU) {
            neuBevel(r, x, top, w, bottom - top + (selected ? 0 : 1), selected ? NEU_FILL : (hover ? 0xE8303030 : 0xE81A1A1A), 2);
        } else {
            int outline = s == Style.SBPV_CLEAR ? 0xFF000000 : SPV_OUTLINE;
            int fill;
            int hi;
            if (selected) {
                fill = s == Style.SBPV_CLEAR ? 0xFF555555 : 0xFF3C8527;
                hi = s == Style.SBPV_CLEAR ? 0xFF555555 : 0xFF639D52;
            } else if (s == Style.SBPV_LIGHT) {
                fill = hover ? 0xFFB1B2B5 : 0xFFD0D1D4;
                hi = 0xFFECEDEE;
            } else if (s == Style.SBPV_DARK) {
                fill = hover ? 0xFF808080 : 0xFF707070;
                hi = 0xFF4C4C4C;
            } else {
                fill = hover ? 0xFF424242 : 0xFF555555;
                hi = fill;
            }
            r.fill(x + 1, top, x + w - 1, top + 1, outline);
            r.fill(x, top + 1, x + 1, bottom, outline);
            r.fill(x + w - 1, top + 1, x + w, bottom, outline);
            if (!selected) {
                r.fill(x, bottom, x + w, bottom + 1, outline);
            }
            r.fill(x + 1, top + 1, x + w - 1, top + 2, hi);
            r.fill(x + 1, top + 2, x + 2, bottom, hi);
            r.fill(x + 2, top + 2, x + w - 1, bottom, fill);
        }
        if (selected) {
            // Covers the panel's top edge under the tab, so the tab and the panel read as one piece.
            r.fill(x + 2, y + h - 1, x + w - 2, y + h + 2, s == Style.NEU ? NEU_FILL : s == Style.SBPV_CLEAR ? 0xFF555555 : 0xFF3C8527);
        }
    }

    /** NEU's bevel: a black edge with its corners cut, {@code d} px light top-left and dark bottom-right, then the fill. */
    private static void neuBevel(GuiRects r, int x, int y, int w, int h, int fill, int d) {
        r.fill(x + 1, y, x + w - 1, y + 1, 0xFF000000);
        r.fill(x + 1, y + h - 1, x + w - 1, y + h, 0xFF000000);
        r.fill(x, y + 1, x + 1, y + h - 1, 0xFF000000);
        r.fill(x + w - 1, y + 1, x + w, y + h - 1, 0xFF000000);
        r.fill(x + 1, y + 1, x + w - 1, y + 1 + d, NEU_LIGHT);
        r.fill(x + 1, y + 1 + d, x + 1 + d, y + h - 1, NEU_LIGHT);
        r.fill(x + 1 + d, y + h - 1 - d, x + w - 1, y + h - 1, NEU_DARK);
        r.fill(x + w - 1 - d, y + 1 + d, x + w - 1, y + h - 1 - d, NEU_DARK);
        r.fill(x + 1 + d, y + 1 + d, x + w - 1 - d, y + h - 1 - d, fill);
    }

    /** SkyBlockPV's framed plate: a {@code b} px edge (a thin one has its corners cut) around the fill. */
    private static void plate(GuiRects r, int x, int y, int w, int h, int fill, int edge, int b) {
        int c = b == 1 ? 1 : 0;
        r.fill(x + c, y, x + w - c, y + b, edge);
        r.fill(x + c, y + h - b, x + w - c, y + h, edge);
        r.fill(x, y + b, x + b, y + h - b, edge);
        r.fill(x + w - b, y + b, x + w, y + h - b, edge);
        r.fill(x + b, y + b, x + w - b, y + h - b, fill);
    }

    /** SkyBlockPV's raised button: a dark outline, a light top line, the face and a 2 px shade along the bottom. */
    private static void raised(GuiRects r, int x, int y, int w, int h, int face, int hi, int shade) {
        r.outline(x, y, w, h, SPV_OUTLINE);
        r.fill(x + 1, y + 1, x + w - 1, y + h - 3, face);
        r.fill(x + 1, y + 1, x + w - 1, y + 2, hi);
        r.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, shade);
    }
}
