package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuConfig;
import com.killer560.hub.gui.MenuTheme;

/**
 * What the main menu draws right now, resolved from its Style (killer560, 2026-10-15): Match Menu Theme takes the mod
 * menu's theme and that theme's background; Separate Theme takes the main menu's own theme and ITS background; Custom
 * takes its own theme and every look setting. Cached until either config changes.
 *
 * @param colours  the five base colours (accent, panel, card, text, on-switch), as {@link MenuTheme#base()}
 * @param theme    the theme they came from (CUSTOM for the menu's Custom colours)
 * @param scene    the background, never {@link MainMenuThemeConfig.Scene#AUTO}
 * @param density  particle amount, 0 for none
 * @param speed    animation speed, 1 = normal
 */
public record MainMenuLook(int[] colours, MenuTheme theme, MainMenuThemeConfig.Scene scene, float density, float speed,
                           boolean splash, boolean panel) {

    private static MainMenuLook cached;
    private static int cachedMain = Integer.MIN_VALUE;
    private static int cachedMenu = Integer.MIN_VALUE;

    private static MainMenuLook calmFor;
    private static MainMenuLook calm;

    /** The same look with half the particles: the background behind every other out-of-world menu. Cached. */
    public synchronized MainMenuLook calm() {
        if (calmFor != this) {
            calm = new MainMenuLook(colours, theme, scene, density * 0.5f, speed, splash, panel);
            calmFor = this;
        }
        return calm;
    }

    public int accent() {
        return colours[0];
    }

    public int panelColour() {
        return colours[1];
    }

    public int card() {
        return colours[2];
    }

    public int text() {
        return colours[3];
    }

    public int switchOn() {
        return colours[4];
    }

    public static synchronized MainMenuLook current() {
        int main = MainMenuThemeConfig.version();
        int menu = MenuConfig.version();
        if (cached == null || main != cachedMain || menu != cachedMenu) {
            cached = resolve(MainMenuThemeConfig.getInstance(), MenuConfig.getInstance());
            cachedMain = main;
            cachedMenu = menu;
        }
        return cached;
    }

    static MainMenuLook resolve(MainMenuThemeConfig cfg, MenuConfig menu) {
        MainMenuThemeConfig.Style style = cfg.getStyle();
        MenuTheme theme;
        int[] colours;
        if (style == MainMenuThemeConfig.Style.MATCH) {
            theme = menu.getTheme();
            colours = menu.activeColours();
        } else {
            theme = cfg.getTheme();
            colours = coloursOf(theme, menu);
        }
        MainMenuThemeConfig.Scene scene = style == MainMenuThemeConfig.Style.CUSTOM ? cfg.getScene()
                : MainMenuThemeConfig.Scene.AUTO;
        if (scene == MainMenuThemeConfig.Scene.AUTO) {
            scene = sceneFor(theme);
        }
        boolean custom = style == MainMenuThemeConfig.Style.CUSTOM;
        float density = !cfg.isParticles() ? 0f : custom ? cfg.getDensity().factor : 1f;
        float speed = custom ? cfg.getSpeed() / 100f : 1f;
        boolean splash = !custom || cfg.isSplash();
        boolean panel = !custom || cfg.isPanel();
        return new MainMenuLook(colours, theme, scene, density, speed, splash, panel);
    }

    /** A theme's five colours; CUSTOM is the mod menu's Custom set. */
    public static int[] coloursOf(MenuTheme theme, MenuConfig menu) {
        if (theme == MenuTheme.CUSTOM) {
            int[] out = new int[MenuTheme.Slot.values().length];
            for (MenuTheme.Slot slot : MenuTheme.Slot.values()) {
                out[slot.ordinal()] = menu.getCustomColour(slot);
            }
            return out;
        }
        return theme.base();
    }

    /** Each theme's own background: Amber's embers, Ocean's waterfall, Dark's starfield, Light's soft light, Custom's aurora. */
    public static MainMenuThemeConfig.Scene sceneFor(MenuTheme theme) {
        if (theme == null) {
            return MainMenuThemeConfig.Scene.EMBERS;
        }
        return switch (theme) {
            case AMBER -> MainMenuThemeConfig.Scene.EMBERS;
            case OCEAN -> MainMenuThemeConfig.Scene.WATERFALL;
            case DARK -> MainMenuThemeConfig.Scene.STARFIELD;
            case LIGHT -> MainMenuThemeConfig.Scene.SOFT_LIGHT;
            case GREEN -> MainMenuThemeConfig.Scene.FIREFLIES;
            case CUSTOM -> MainMenuThemeConfig.Scene.AURORA;
            // A theme added later: a light one gets the soft light, a dark one embers in its own colours.
            default -> com.killer560.hub.gui.MenuStyle.luminance(theme.base()[1]) > 0.5f
                    ? MainMenuThemeConfig.Scene.SOFT_LIGHT : MainMenuThemeConfig.Scene.EMBERS;
        };
    }
}
