package com.killer560.hub.mainmenu;

/**
 * The frame rate out-of-world menus run at while the themed main menu draws its animated scene (killer560, 2026-10-16:
 * "120fps"). Vanilla holds every out-of-world menu to 60 fps ({@code FramerateLimitTracker.OUT_OF_LEVEL_MENU_LIMIT}); a scene
 * whose lights drift a few pixels a second visibly steps at 60, so while the theme's scene is on screen the cap becomes the
 * larger of 120 and the player's own Max Framerate (260, unlimited, stays unlimited). Applied by
 * {@code mixin/MainMenuFramerateMixin}; vsync is untouched.
 */
public final class MainMenuFramerate {

    private MainMenuFramerate() {
    }

    /** The floor the themed menu raises the out-of-world cap to. */
    public static final int THEMED_MENU_FPS = 120;

    /** The limit to use instead of vanilla's 60 for an out-of-world menu, or 0 to leave vanilla's: the themed scene is drawn
     *  (the title screen, or another out-of-world menu with Themed Menus on). */
    public static int menuLimit(int userLimit) {
        try {
            if (!MainMenuTheme.activeOnMenuBackground()) {
                return 0;
            }
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
        return Math.max(THEMED_MENU_FPS, userLimit);
    }
}
