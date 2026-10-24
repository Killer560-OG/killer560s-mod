package com.killer560.hub.gui;

/**
 * The colors the Storage Overlay and the Inventory Theme draw in, as plain values: one of the {@link PanelTheme} looks
 * (Amber, Dark, Light) or the menu's palette ({@link #fromMenu}, when the overlay's Menu Theme is not Default - 2026-10-15).
 * The drawing code reads these and neither knows nor cares which it was given. Field meanings are {@link PanelTheme}'s.
 *
 * @param amber true only for PanelTheme.AMBER, whose active page label keeps its gold "§6" code exactly
 */
public record PanelColours(int panelBg, int border, int hoverBorder, int activeBorder, int text, int cellBg, int cellLine,
                           int viewportBg, int buttonHoverBg, int highlightRgb, int invPanelBg, int invSlotBg, int invAccent,
                           boolean amber) {

    /** The overlay's colors in a menu theme: panels take the menu's panel, cells its card, lines and highlights its accent. */
    public static PanelColours fromMenu(OverlayPalette p) {
        int text = p.text() | 0xFF000000;
        int cellLine = MenuStyle.mix(p.card(), p.text(), p.light() ? 0.25f : 0.18f) | 0xFF000000;
        return new PanelColours(OverlayPalette.alpha(p.panel(), 0xCC), p.border() | 0xFF000000, p.accent() | 0xFF000000,
                p.accent() | 0xFF000000, text, p.card() | 0xFF000000, cellLine, OverlayPalette.alpha(p.well(), 0xD0),
                OverlayPalette.alpha(p.cardHover(), 0xE0), p.accent() & 0xFFFFFF, p.panel() | 0xFF000000,
                p.card() | 0xFF000000, p.accent() | 0xFF000000, false);
    }
}
