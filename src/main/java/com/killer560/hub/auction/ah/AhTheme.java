package com.killer560.hub.auction.ah;

import com.killer560.hub.gui.PanelTheme;
import com.killer560.hub.inventorytheme.InventoryThemeConfig;

/**
 * The unified Auction House's colours, taken from the mod's theme setting (Inventory Theme: Amber, Dark or Light, plus
 * its accent, custom or the theme's own) so the AH matches the menus he themed. Read once per frame; nothing is
 * written back. Amber with the default accent gives the same orange-on-black as the Bazaar.
 */
public record AhTheme(boolean light, int accent, int accentBright, int backdrop, int panel, int surface, int surfaceAlt,
                      int hover, int selected, int border, int borderStrong, int text, int dim, int faint, int gold,
                      int green, int red, int aqua, int card, int cardHover, int cardText, int cardDim, int cardFaint) {

    private static PanelTheme cachedTheme;
    private static int cachedAccent;
    private static AhTheme cached;

    public static AhTheme current() {
        PanelTheme t;
        int accent;
        try {
            InventoryThemeConfig cfg = InventoryThemeConfig.getInstance();
            t = cfg.getTheme();
            accent = 0xFF000000 | cfg.getAccentColor();
        } catch (Exception e) {
            t = PanelTheme.AMBER;
            accent = 0xFFCC6600;
        }
        if (cached != null && t == cachedTheme && accent == cachedAccent) {
            return cached;
        }
        AhTheme theme = t == PanelTheme.LIGHT ? light(accent) : dark(accent, t == PanelTheme.DARK);
        cachedTheme = t;
        cachedAccent = accent;
        cached = theme;
        return theme;
    }

    private static AhTheme dark(int accent, boolean neutral) {
        int border = neutral ? 0xFF34343A : mix(accent, 0xFF101010, 0.62f);
        return new AhTheme(false, accent, brighten(accent),
                0xFF08080A,                                   // backdrop: opaque, so no HUD shows through
                neutral ? 0xFF111114 : 0xFF0E0C0A,            // panel
                neutral ? 0xFF17171B : 0xFF15120F,            // cards
                neutral ? 0xFF1B1B20 : 0xFF1A1612,            // alt rows / bars
                mix(accent, 0xFF161210, 0.80f),               // hover
                mix(accent, 0xFF120E0A, 0.68f),               // selected
                border, mix(accent, 0xFF101010, 0.35f),
                0xFFECE6DE, 0xFFA59A8F, 0xFF6E655D,
                0xFFFFC040, 0xFF62DD62, 0xFFFF6060, 0xFF55DDEE,
                neutral ? 0xFF17171B : 0xFF15120F, mix(accent, 0xFF161210, 0.80f), 0xFFECE6DE, 0xFFA59A8F, 0xFF6E655D);
    }

    private static AhTheme light(int accent) {
        return new AhTheme(true, accent, mix(accent, 0xFF000000, 0.25f),
                0xFFD9D9DC, 0xFFEDEDEF, 0xFFFFFFFF, 0xFFF4F4F6,
                mix(accent, 0xFFFFFFFF, 0.85f), mix(accent, 0xFFFFFFFF, 0.72f),
                0xFFC4C4C8, mix(accent, 0xFFFFFFFF, 0.35f),
                0xFF1A1A1C, 0xFF55555C, 0xFF85858C,
                0xFFA86A00, 0xFF1E8A1E, 0xFFC02020, 0xFF107A8A,
                // Cards stay dark in the Light theme: item names arrive in Hypixel's own colours (white for COMMON),
                // which a white card would swallow.
                0xFF2A2A2F, mix(accent, 0xFF2A2A2F, 0.70f), 0xFFF0F0F2, 0xFFB4B4BC, 0xFF85858E);
    }

    /** {@code a} weighted {@code 1 - t}, {@code b} weighted {@code t}; opaque. */
    public static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int brighten(int c) {
        return mix(c, 0xFFFFFFFF, 0.28f);
    }
}
