package com.killer560.hub.gui;

/**
 * The mod menu's colours as an overlay needs them (killer560, 2026-10-15: "other features like storage overlay ... need an
 * option for the new menu themes"). A snapshot of {@link MenuStyle}'s palette derived from one theme's five base colours,
 * so an overlay drawing over the game (the Storage Overlay, the Inventory Theme, the Chest Profit panel...) can take the
 * same colours as the menu without being a screen of its own. Get one through {@link OverlayThemes#of}; null there means
 * the overlay's own look ("Default").
 *
 * @param light      the panel is lighter than mid grey: dark text, grey outlines (the menu's Light treatment)
 * @param base       the five base colours: accent, panel, card, text, switch-on
 * @param backdrop   the dim behind a whole screen
 * @param panel      a panel's fill
 * @param panelDeep  the darker end of a panel's gradient
 * @param card       a card / cell fill
 * @param cardHover  a hovered card
 * @param well       a recessed area (list, grid backdrop)
 * @param border     a panel's outline (a dim accent)
 * @param outline    the hard outline (black on dark themes)
 * @param accent     the accent colour
 * @param accentHover a hovered / brighter accent
 * @param accentDeep a darker accent
 * @param control    a button's fill
 * @param controlHover a hovered button
 * @param selected   a selected row / slot fill
 * @param text       normal text
 * @param textHover  bright text
 * @param dim        secondary text
 * @param faint      tertiary text
 * @param switchOn   the on-switch colour
 */
public record OverlayPalette(boolean light, int[] base, int backdrop, int panel, int panelDeep, int card, int cardHover,
                             int well, int border, int outline, int accent, int accentHover, int accentDeep, int control,
                             int controlHover, int selected, int text, int textHover, int dim, int faint, int switchOn) {

    /** {@code argb} with its alpha replaced. */
    public static int alpha(int argb, int a) {
        return MenuStyle.alpha(argb, a);
    }

    /** The colours that identify this palette, for a test that wants to know one theme drew differently from another. */
    public int[] key() {
        return new int[]{panel, card, accent, text, border, control};
    }
}
