package com.killer560.hub.auction.ah;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.gui.PanelTheme;
import com.killer560.hub.gui.ScreenThemes;
import com.killer560.hub.inventorytheme.InventoryThemeConfig;

/**
 * The unified Auction House's colours. By default the mod menu's theme (the AH's Screen Theme, set in its tab: Theme
 * Default follows the menu, or any preset / Custom), derived from {@link MenuStyle} with {@code bevel} on, so the panel,
 * field and buttons take the menu's bevelled look (sweep-b, 2026-10-13). The Screen Theme "Inventory Theme" keeps the
 * older look, from the Inventory Theme (Amber, Dark or Light plus its accent), flat. Read once per frame; nothing is
 * written back.
 */
public record AhTheme(boolean light, int accent, int accentBright, int backdrop, int panel, int surface, int surfaceAlt,
                      int hover, int selected, int border, int borderStrong, int text, int dim, int faint, int gold,
                      int green, int red, int aqua, int card, int cardHover, int cardText, int cardDim, int cardFaint,
                      boolean bevel) {

    /** The Screen Theme id the Auction House tab sets. */
    public static final String SCREEN_ID = "auction_house";
    /** The Theme choice that keeps the older look (an extra choice on the AH tab's Theme button). */
    public static final String INVENTORY_STYLE = "INVENTORY";

    private static PanelTheme cachedTheme;
    private static int cachedAccent;
    private static AhTheme cached;

    public static AhTheme current() {
        if (!INVENTORY_STYLE.equals(ScreenThemes.get(SCREEN_ID))) {
            MenuStyle.use(MenuTheme.forScreen(SCREEN_ID));
            return fromMenu();
        }
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

    /** The menu palette in force ({@link MenuStyle} after {@code use}) as the AH's colours. Cards stay dark on a light
     *  theme, as they always have: item names arrive in Hypixel's colours. */
    private static AhTheme fromMenu() {
        boolean light = MenuStyle.LIGHT;
        int accent = MenuStyle.OR;
        int surface = MenuStyle.CARD_BOTTOM;
        int hover = mix(surface, accent, 0.20f);
        int panel = MenuStyle.PANEL_BOTTOM;
        int card = light ? 0xFF2A2A2F : surface;
        return new AhTheme(light, accent, light ? MenuStyle.OR_HOVER : MenuStyle.OR_LIGHT,
                0xFF000000 | MenuStyle.mix(panel, 0xFF000000, light ? 0.15f : 0.4f), panel, surface,
                MenuStyle.CARD_TOP, hover, mix(surface, accent, 0.32f), MenuStyle.ACCENT_EDGE, mix(panel, accent, 0.65f),
                MenuStyle.TXT, MenuStyle.DIM, MenuStyle.DIM2,
                light ? 0xFFA86A00 : 0xFFFFC040, light ? 0xFF1E8A1E : 0xFF62DD62, light ? 0xFFC02020 : 0xFFFF6060,
                light ? 0xFF107A8A : 0xFF55DDEE,
                card, light ? mix(0xFF2A2A2F, accent, 0.30f) : hover,
                light ? 0xFFF0F0F2 : MenuStyle.TXT, light ? 0xFFB4B4BC : MenuStyle.DIM, light ? 0xFF85858E : MenuStyle.DIM2,
                true);
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
                neutral ? 0xFF17171B : 0xFF15120F, mix(accent, 0xFF161210, 0.80f), 0xFFECE6DE, 0xFFA59A8F, 0xFF6E655D,
                false);
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
                0xFF2A2A2F, mix(accent, 0xFF2A2A2F, 0.70f), 0xFFF0F0F2, 0xFFB4B4BC, 0xFF85858E, false);
    }

    /** Colours for text ON a card: cards stay dark in the Light theme, so they take the dark theme's bright tones. */
    public int cardGold() {
        return light ? 0xFFFFC040 : gold;
    }

    public int cardAqua() {
        return light ? 0xFF55DDEE : aqua;
    }

    public int cardGreen() {
        return light ? 0xFF62DD62 : green;
    }

    public int cardRed() {
        return light ? 0xFFFF6060 : red;
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
