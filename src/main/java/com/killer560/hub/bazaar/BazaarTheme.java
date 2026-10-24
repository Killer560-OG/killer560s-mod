package com.killer560.hub.bazaar;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.gui.PanelTheme;
import com.killer560.hub.gui.ScreenThemes;
import com.killer560.hub.inventorytheme.InventoryThemeConfig;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

/**
 * The Bazaar screen's colors. By default the mod menu's theme (its Screen Theme, set in the Bazaar tab: Theme Default
 * follows the menu, or any preset / Custom), derived from {@link MenuStyle} with {@code bevel} on, so the panel, wells
 * and buttons take the menu's beveled look (sweep-b, 2026-10-13). The Screen Theme "Inventory Theme" keeps the older
 * look: the Inventory Theme's Amber / Dark / Light (read only, from {@link InventoryThemeConfig#getTheme()}), flat.
 * <p>
 * On Light, every color Hypixel's own item names and lore carry (white, yellow, gray...) would vanish into the panel,
 * so {@link #seq} darkens each styled character's color the same way; on Amber and Dark it is the text unchanged.
 */
public record BazaarTheme(String name, boolean light, int backdrop, int panelBg, int border, int railBg, int listBg,
        int rowAlt, int rowHover, int selectedBg, int accent, int accentBright, int text, int dim, int faint, int gold,
        int green, int red, int control, int controlHover, int skeleton, boolean bevel) {

    static final BazaarTheme AMBER = new BazaarTheme("Amber", false, 0xC8000000, 0xF20D0D0D, 0xFF553311, 0xFF130D07,
            0xFF0A0A0A, 0xFF101010, 0xFF2A1A0A, 0xFF3A2208, 0xFFCC6600, 0xFFFFA040, 0xFFE8E0D8, 0xFF9A8C80,
            0xFF6A6058, 0xFFFFC040, 0xFF55DD55, 0xFFFF5555, 0xFF1A120A, 0xFF2E1E0E, 0xFF1C1612, false);
    static final BazaarTheme DARK = new BazaarTheme("Dark", false, 0xC8000000, 0xF20E0E10, 0xFF3A3A3E, 0xFF141416,
            0xFF0B0B0C, 0xFF121214, 0xFF26262A, 0xFF303036, 0xFFB4B4BA, 0xFFE6E6EC, 0xFFE8E8EC, 0xFF9C9CA4,
            0xFF6A6A72, 0xFFFFC040, 0xFF55DD55, 0xFFFF5555, 0xFF18181B, 0xFF2A2A2F, 0xFF1A1A1D, false);
    static final BazaarTheme LIGHT = new BazaarTheme("Light", true, 0xA0000000, 0xF6E8E8E8, 0xFFAAAAAA, 0xFFDCDCDC,
            0xFFF2F2F2, 0xFFE6E6E6, 0xFFCFCFCF, 0xFFC4C4C4, 0xFF505050, 0xFF202020, 0xFF151515, 0xFF4A4A4A,
            0xFF6E6E6E, 0xFF8A5A00, 0xFF1C7A1C, 0xFFB01E1E, 0xFFD6D6D6, 0xFFC2C2C2, 0xFFDADADA, false);

    /** The Screen Theme id the Bazaar tab sets. */
    public static final String SCREEN_ID = "bazaar";
    /** The Theme choice that keeps the older look (an extra choice on the Bazaar tab's Theme button). */
    public static final String INVENTORY_STYLE = "INVENTORY";

    /** The theme in force right now (derives the menu palette for the Bazaar's Screen Theme as a side effect). */
    public static BazaarTheme current() {
        if (!INVENTORY_STYLE.equals(ScreenThemes.get(SCREEN_ID))) {
            MenuStyle.use(MenuTheme.forScreen(SCREEN_ID));
            return fromMenu();
        }
        PanelTheme t;
        try {
            t = InventoryThemeConfig.getInstance().getTheme();
        } catch (RuntimeException e) {
            t = PanelTheme.AMBER;
        }
        return switch (t == null ? PanelTheme.AMBER : t) {
            case DARK -> DARK;
            case LIGHT -> LIGHT;
            default -> AMBER;
        };
    }

    /** The menu palette in force ({@link MenuStyle} after {@code use}) as the Bazaar's colors. */
    private static BazaarTheme fromMenu() {
        boolean light = MenuStyle.LIGHT;
        return new BazaarTheme("Menu", light, MenuStyle.BACKDROP, MenuStyle.PANEL_TOP, MenuStyle.ACCENT_EDGE,
                MenuStyle.SIDE_TOP, MenuStyle.WELL, MenuStyle.mix(MenuStyle.WELL, MenuStyle.TXT, 0.04f),
                MenuStyle.mix(MenuStyle.WELL, MenuStyle.OR, 0.14f), MenuStyle.SEL_TOP, MenuStyle.OR, MenuStyle.OR_HOVER,
                MenuStyle.TXT, MenuStyle.DIM, MenuStyle.DIM2, light ? 0xFF8A5A00 : 0xFFFFC040,
                light ? 0xFF1C7A1C : 0xFF55DD55, light ? 0xFFB01E1E : 0xFFFF5555, MenuStyle.BTN_TOP,
                MenuStyle.BTN_HOVER_TOP, MenuStyle.CARD_TOP, true);
    }

    /** {@code argb} made readable on this theme's panel (darkened on Light, unchanged otherwise). */
    public int on(int argb) {
        if (!light) {
            return argb;
        }
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r > 0xC8 && g > 0xC8 && b > 0xC8) {
            return text; // white (common items, plain lore) reads as the theme's own text color
        }
        return (argb & 0xFF000000) | ((r * 45 / 100) << 16) | ((g * 45 / 100) << 8) | (b * 45 / 100);
    }

    /** Hypixel's styled text made readable on this theme's panel: every color darkened on Light. */
    public FormattedCharSequence seq(FormattedCharSequence s) {
        if (!light) {
            return s;
        }
        return sink -> s.accept((index, style, cp) -> {
            TextColor c = style.getColor();
            int rgb = c == null ? 0xFFFFFF : c.getValue();
            Style dark = style.withColor(TextColor.fromRgb(on(0xFF000000 | rgb) & 0xFFFFFF));
            return sink.accept(index, dark, cp);
        });
    }
}
