package com.killer560.hub.gui;

/**
 * A screen of the mod's own drawn in the mod menu's active theme (killer560, 2026-10-13: "make the theme for each [custom
 * menu] match the gui's theme"). The marker is what {@link SettingsButtonWidget} reads to draw its raised, themed button
 * there instead of the old fixed amber box; the screen itself paints its frame, header and rows through
 * {@link ScreenChrome}, whose colors all come from {@link MenuStyle}.
 */
public interface MenuThemedScreen {

    /** {x, y, w, h} of the screen's main themed panel (for the pet wheel, its first tile) as last laid out, in GUI units.
     *  Read by the testkit, which samples it under each theme. */
    int[] themedPanelBounds();

    /** The id of this screen's own Theme setting in {@link ScreenThemes}, or null to always follow the menu. */
    default String themeId() {
        return null;
    }
}
