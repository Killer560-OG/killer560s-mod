package com.killer560.hub.bazaar;

import com.killer560.hub.gui.PanelTheme;
import com.killer560.hub.inventorytheme.InventoryThemeConfig;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

/**
 * The Bazaar screen's colours, following the Inventory Theme's Amber / Dark / Light setting (read only, from
 * {@link InventoryThemeConfig#getTheme()}), so the Bazaar matches his menus. Amber is the orange look the Bazaar had;
 * Dark keeps the dark fills with grey lines and a pale accent; Light is light grey with dark text.
 * <p>
 * On Light, every colour Hypixel's own item names and lore carry (white, yellow, grey...) would vanish into the panel,
 * so {@link #seq} darkens each styled character's colour the same way; on Amber and Dark it is the text unchanged.
 */
public record BazaarTheme(String name, boolean light, int backdrop, int panelBg, int border, int railBg, int listBg,
        int rowAlt, int rowHover, int selectedBg, int accent, int accentBright, int text, int dim, int faint, int gold,
        int green, int red, int control, int controlHover, int skeleton) {

    static final BazaarTheme AMBER = new BazaarTheme("Amber", false, 0xC8000000, 0xF20D0D0D, 0xFF553311, 0xFF130D07,
            0xFF0A0A0A, 0xFF101010, 0xFF2A1A0A, 0xFF3A2208, 0xFFCC6600, 0xFFFFA040, 0xFFE8E0D8, 0xFF9A8C80,
            0xFF6A6058, 0xFFFFC040, 0xFF55DD55, 0xFFFF5555, 0xFF1A120A, 0xFF2E1E0E, 0xFF1C1612);
    static final BazaarTheme DARK = new BazaarTheme("Dark", false, 0xC8000000, 0xF20E0E10, 0xFF3A3A3E, 0xFF141416,
            0xFF0B0B0C, 0xFF121214, 0xFF26262A, 0xFF303036, 0xFFB4B4BA, 0xFFE6E6EC, 0xFFE8E8EC, 0xFF9C9CA4,
            0xFF6A6A72, 0xFFFFC040, 0xFF55DD55, 0xFFFF5555, 0xFF18181B, 0xFF2A2A2F, 0xFF1A1A1D);
    static final BazaarTheme LIGHT = new BazaarTheme("Light", true, 0xA0000000, 0xF6E8E8E8, 0xFFAAAAAA, 0xFFDCDCDC,
            0xFFF2F2F2, 0xFFE6E6E6, 0xFFCFCFCF, 0xFFC4C4C4, 0xFF505050, 0xFF202020, 0xFF151515, 0xFF4A4A4A,
            0xFF6E6E6E, 0xFF8A5A00, 0xFF1C7A1C, 0xFFB01E1E, 0xFFD6D6D6, 0xFFC2C2C2, 0xFFDADADA);

    /** The theme in force right now. */
    public static BazaarTheme current() {
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

    /** {@code argb} made readable on this theme's panel (darkened on Light, unchanged otherwise). */
    public int on(int argb) {
        if (!light) {
            return argb;
        }
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r > 0xC8 && g > 0xC8 && b > 0xC8) {
            return text; // white (common items, plain lore) reads as the theme's own text colour
        }
        return (argb & 0xFF000000) | ((r * 45 / 100) << 16) | ((g * 45 / 100) << 8) | (b * 45 / 100);
    }

    /** Hypixel's styled text made readable on this theme's panel: every colour darkened on Light. */
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
