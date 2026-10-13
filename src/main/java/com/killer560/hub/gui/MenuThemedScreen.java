package com.killer560.hub.gui;

/**
 * A mod screen drawn in the menu theme (screen sweep, killer560 2026-10-13: "make the theme for each match the gui's
 * theme"). While one of these is the open screen, {@link SettingsButtonWidget}, {@link ThemedSliderButton} and
 * {@link RangeSliderWidget} take the mod menu's look (switch rows, value pills, raised buttons, slider tracks) instead of
 * their old plain boxes, so the screen's own controls follow the active {@link MenuTheme} the same way the menu does.
 * The screen draws its own chrome through {@link ToolScreenKit}.
 */
public interface MenuThemedScreen {

    /**
     * The id of this screen's own Theme setting ({@link ScreenThemeConfig}, read by {@link MenuTheme#forScreen}): one
     * per feature, so the sim's five screens share "sim".
     */
    String themeId();
}
