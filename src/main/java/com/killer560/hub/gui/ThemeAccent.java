package com.killer560.hub.gui;

/**
 * The mod menu's theme for code that used to draw in fixed amber (killer560, 2026-11-04: "all parts of my mod should match
 * the theme of the mod. so if someone has the green theme and has my crystal hollows map the border shouldnt be orange it
 * should be green"). Each call takes the amber literal the code drew before: on Amber it comes back unchanged, so the
 * default look does not move by a pixel; on any other theme it is that theme's color with the literal's alpha byte kept
 * (an RGB-only literal stays RGB-only). The palette is {@link OverlayThemes#menu()}, re-derived only when
 * {@link MenuConfig#version()} moves, so a per-frame call allocates nothing.
 * <p>
 * Not for colors that mean something (rarity, zone, room type, warning red) or one the player picked.
 */
public final class ThemeAccent {

    public static final double MIN_RATIO = 4.5;
    public static final int DARK_BG = 0xFF202020;
    private static int lastIn;
    private static int lastOut;
    private static int lastVersion = -1;

    private ThemeAccent() {
    }

    /** The theme's accent (amber 0xCC6600 / 0xFF8A1F: borders, outlines, highlights). */
    public static int accent(int amber) {
        OverlayPalette p = themed();
        return p == null ? amber : keepAlpha(amber, p.accent());
    }

    /** The theme's bright accent (amber 0xFFA040 / 0xFFAA00: titles, headers, hovered outlines). */
    public static int light(int amber) {
        OverlayPalette p = themed();
        return p == null ? amber : keepAlpha(amber, p.accentHover());
    }

    /** The theme's dim outline (amber 0x553311: idle borders, cell lines). */
    public static int dim(int amber) {
        OverlayPalette p = themed();
        return p == null ? amber : keepAlpha(amber, p.border());
    }

    /** The theme's selected fill (amber dark browns 0x4A2A08 / 0x3D2A14: a pressed button, a slider's filled part). */
    public static int fill(int amber) {
        OverlayPalette p = themed();
        return p == null ? amber : keepAlpha(amber, p.selected());
    }

    /** Text drawn over the game world or chat (always dark). A light menu theme's accent is a dark color made for a light panel
     *  (Light's blue is 3.9:1 on black, its hover 2.6:1), so it is lifted toward white until it reaches {@link #MIN_RATIO}
     *  against {@link #DARK_BG}; on Amber and every dark theme the color comes back unchanged. */
    public static int onDark(int argb) {
        OverlayPalette p = themed();
        if (p == null || !p.light()) {
            return argb;
        }
        if (argb == lastIn && MenuConfig.version() == lastVersion) {
            return lastOut;
        }
        int c = argb;
        for (int i = 0; i < 20 && contrast(c, DARK_BG) < MIN_RATIO; i++) {
            c = MenuStyle.mix(c, 0xFFFFFFFF, 0.1f);
        }
        lastIn = argb;
        lastOut = c;
        lastVersion = MenuConfig.version();
        return c;
    }

    /** WCAG contrast ratio of two opaque colors (1..21). */
    public static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int c) {
        return 0.2126 * lin((c >> 16) & 0xFF) + 0.7152 * lin((c >> 8) & 0xFF) + 0.0722 * lin(c & 0xFF);
    }

    private static double lin(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** Null on Amber (draw the literal), else the menu's palette. */
    public static OverlayPalette themed() {
        return MenuConfig.getInstance().getTheme() == MenuTheme.AMBER ? null : OverlayThemes.menu();
    }

    private static int keepAlpha(int amber, int colour) {
        return (amber & 0xFF000000) | (colour & 0xFFFFFF);
    }
}
