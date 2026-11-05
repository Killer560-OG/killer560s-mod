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

    /** Null on Amber (draw the literal), else the menu's palette. */
    public static OverlayPalette themed() {
        return MenuConfig.getInstance().getTheme() == MenuTheme.AMBER ? null : OverlayThemes.menu();
    }

    private static int keepAlpha(int amber, int colour) {
        return (amber & 0xFF000000) | (colour & 0xFFFFFF);
    }
}
