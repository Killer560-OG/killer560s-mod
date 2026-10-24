package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuConfig;
import com.killer560.hub.gui.MenuTheme;

/**
 * What the main menu draws right now, resolved from its Style (killer560, 2026-10-15): Match Menu Theme takes the mod
 * menu's theme and that theme's background; Separate Theme takes the main menu's own theme and ITS background; Custom
 * takes its own theme and every look setting. Cached until either config changes.
 *
 * @param colours  the five base colors (accent, panel, card, text, on-switch), as {@link MenuTheme#base()}
 * @param theme    the theme they came from (CUSTOM for the menu's Custom colors)
 * @param scene    the background, never {@link MainMenuThemeConfig.Scene#AUTO}
 * @param density  particle amount, 0 for none
 * @param speed    animation speed, 1 = normal
 */
public record MainMenuLook(int[] colours, MenuTheme theme, MainMenuThemeConfig.Scene scene, float density, float speed,
                           boolean panel) {

    private static MainMenuLook cached;
    private static int cachedMain = Integer.MIN_VALUE;
    private static int cachedMenu = Integer.MIN_VALUE;
    private static int cachedPreview = Integer.MIN_VALUE;

    private static MainMenuLook calmFor;
    private static MainMenuLook calm;

    /** The same look with half the particles: the background behind every other out-of-world menu. Cached. */
    public synchronized MainMenuLook calm() {
        if (calmFor != this) {
            calm = new MainMenuLook(colours, theme, scene, density * 0.5f, speed, panel);
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
        int preview = TitleScreenLooks.version();
        if (cached == null || main != cachedMain || menu != cachedMenu || preview != cachedPreview) {
            TitleScreenLooks.Look look = TitleScreenLooks.current();
            cached = look != null && !look.vanilla() ? preview(look, MainMenuThemeConfig.getInstance(), MenuConfig.getInstance())
                    : resolve(MainMenuThemeConfig.getInstance(), MenuConfig.getInstance());
            cachedMain = main;
            cachedMenu = menu;
            cachedPreview = preview;
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
            scene = sceneFor(theme, cfg.isSimple());
        }
        boolean custom = style == MainMenuThemeConfig.Style.CUSTOM;
        float density = !cfg.isParticles() ? 0f : custom ? cfg.getDensity().factor : 1f;
        float speed = custom ? cfg.getSpeed() / 100f : 1f;
        boolean panel = !custom || cfg.isPanel();
        return new MainMenuLook(colours, theme, scene, density, speed, panel);
    }

    /** A Title Screen Switcher look: its theme with that theme's own background, everything else as Match gives it. */
    static MainMenuLook preview(TitleScreenLooks.Look look, MainMenuThemeConfig cfg, MenuConfig menu) {
        return new MainMenuLook(coloursOf(look.theme(), menu), look.theme(), sceneFor(look.theme(), look.simple()),
                cfg.isParticles() ? 1f : 0f, 1f, true);
    }

    /** A theme's five colors; CUSTOM is the mod menu's Custom set. */
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

    /** Each theme's own Fancy background (see {@link #sceneFor(MenuTheme, boolean)}). */
    public static MainMenuThemeConfig.Scene sceneFor(MenuTheme theme) {
        return sceneFor(theme, false);
    }

    /**
     * Each theme's own background in the Main Menu Scene mode (killer560, 2026-10-16). Fancy: Amber's aurora picture, Ocean's
     * Cavern Falls, Dark's Milky Way, Light's Soft Light, Viridian's slime boss. Simple: each theme's floating particles
     * ({@link SimpleScenes}) - embers, bubbles, stardust, fireflies; Light keeps Soft Light ("Keep light as only the one
     * option"). Custom shows the embers in its own colors in both.
     */
    public static MainMenuThemeConfig.Scene sceneFor(MenuTheme theme, boolean simple) {
        if (theme == null) {
            return simple ? MainMenuThemeConfig.Scene.SIMPLE_EMBERS : MainMenuThemeConfig.Scene.EMBERS;
        }
        return switch (theme) {
            case AMBER -> simple ? MainMenuThemeConfig.Scene.SIMPLE_EMBERS : MainMenuThemeConfig.Scene.EMBERS;
            case OCEAN -> simple ? MainMenuThemeConfig.Scene.SIMPLE_BUBBLES : MainMenuThemeConfig.Scene.WATERFALL;
            case DARK -> simple ? MainMenuThemeConfig.Scene.SIMPLE_STARDUST : MainMenuThemeConfig.Scene.STARFIELD;
            case LIGHT -> MainMenuThemeConfig.Scene.SOFT_LIGHT;
            case GREEN -> simple ? MainMenuThemeConfig.Scene.SIMPLE_FIREFLIES : MainMenuThemeConfig.Scene.FIREFLIES;
            // killer560 (2026-10-16): "remove aurora from custom, we won't have a custom one", then "for custom on fancy or
            // plain have it do the colors of their custom theme" - Custom has no painted scene; in both modes it shows the
            // Simple embers in its own colors.
            case CUSTOM -> MainMenuThemeConfig.Scene.SIMPLE_EMBERS;
            // A theme added later: a light one gets the soft light, a dark one embers in its own colors.
            default -> com.killer560.hub.gui.MenuStyle.luminance(theme.base()[1]) > 0.5f
                    ? MainMenuThemeConfig.Scene.SOFT_LIGHT
                    : simple ? MainMenuThemeConfig.Scene.SIMPLE_EMBERS : MainMenuThemeConfig.Scene.EMBERS;
        };
    }
}
