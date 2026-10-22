package com.killer560.hub.menuthemes;

import com.killer560.hub.fonts.FontChoices;
import com.killer560.hub.gui.MenuConfig;
import com.killer560.hub.gui.MenuTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Theme Font (killer560, 2026-10-21: "Add an option for custom fonts based on the theme"): each menu theme can bring a
 * font from the Font setting's own list (Minecraft's, the four bundled OFL fonts, or one in the fonts folder), or Keep
 * Current, the default, which leaves the Font setting as it is. The theme is the mod menu's ({@link MenuConfig#getTheme()});
 * where the font is drawn (Font Applies To) and its size stay the Font setting's. {@code CustomFonts.refresh} asks
 * {@link #effective} which font to build, so switching theme rebuilds the font on the next tick.
 */
public final class ThemeFonts {

    private ThemeFonts() {
    }

    /** The font id to build: the menu theme's own font, or {@code base} (the Font setting's) when it keeps the current one
     *  or its font is gone (an imported file deleted since). */
    public static String effective(String base) {
        String own = ThemeExtrasConfig.getInstance().getFont(MenuConfig.getInstance().getTheme());
        if (own == null || ThemeExtrasConfig.FONT_KEEP.equals(own) || FontChoices.find(own) == null) {
            return base;
        }
        return own;
    }

    /** Keep Current, then every font the Font setting can pick. */
    public static List<String> choices() {
        List<String> out = new ArrayList<>();
        out.add(ThemeExtrasConfig.FONT_KEEP);
        for (FontChoices.Choice c : FontChoices.all()) {
            out.add(c.id());
        }
        return out;
    }

    /** The next (+1) or previous (-1) choice for {@code theme}, wrapping, saved. Re-reads the fonts folder first, so a font
     *  dropped in is there to pick. */
    public static void step(MenuTheme theme, int dir) {
        FontChoices.rescan();
        ThemeExtrasConfig cfg = ThemeExtrasConfig.getInstance();
        List<String> all = choices();
        int i = Math.max(0, all.indexOf(cfg.getFont(theme)));
        cfg.setFont(theme, all.get(Math.floorMod(i + dir, all.size())));
        cfg.save();
    }

    public static String label(String id) {
        if (id == null || ThemeExtrasConfig.FONT_KEEP.equals(id)) {
            return "Keep Current";
        }
        return FontChoices.find(id) == null ? "Keep Current (font missing)" : FontChoices.label(id);
    }
}
