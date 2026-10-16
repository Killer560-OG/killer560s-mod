package com.killer560.hub.mainmenu;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.GreenTheme;
import com.killer560.hub.gui.MenuConfig;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Title Screen Switcher (killer560, 2026-10-16: "make a new instance called title screen gui that has our mod with a
 * button on the title screen to swap through different gui's you have built", then "add a button to replace the
 * multiplayer button that opens a menu to swap gui's"). It exists only in the Prism instance "Title Screen GUI", which
 * "will never be loaded into a server - only for testing the main menus": the instance carries the marker file
 * {@link #MARKER_FILE} in its game folder (or a harness sets {@code -Dkiller560smod.titleScreenSwitcher=true}); with
 * neither, nothing here does anything and the title screen is the normal one.
 *
 * <p>In that instance the title screen's Multiplayer button becomes {@code Main Menu Looks}, which opens
 * {@link TitleScreenLooksScreen} (every look, grouped by theme), and a small cycling button in the top right corner
 * steps through the same list (left click next, right click previous) with the current look's name on it.
 *
 * <p>A look is a session-only override: it never writes a setting, so the instance's saved choice is what comes back on
 * the next launch. {@link MainMenuLook#current()} and {@link MainMenuTheme#active()} read it, so it applies live.
 */
public final class TitleScreenLooks {

    /** The marker file in the game folder that turns the switcher on (like Auto Update's no-auto-update marker). */
    public static final String MARKER_FILE = "killer560smod-title-screen-gui";
    /** The system property that turns it on without the file (docs/TESTING-HOOKS.md). */
    public static final String PROPERTY = "killer560smod.titleScreenSwitcher";

    /**
     * One main menu look: a theme and the variant of its background. {@code theme == null} is the vanilla title screen
     * (the themed main menu off).
     */
    public record Look(String group, String name, MenuTheme theme, MainMenuScenes.AmberScene amber,
                       MainMenuScenes.GreenScene green, GreenTheme.Gradient gradient, Enum<?> variant, String note) {
        public boolean vanilla() {
            return theme == null;
        }

        /** "Amber · Embers", "Vanilla". */
        public String label() {
            return name.isEmpty() ? group : group + " · " + name;
        }
    }

    private static final List<Look> LOOKS;

    static {
        List<Look> l = new ArrayList<>();
        l.add(new Look("Vanilla", "", null, null, null, null, null, "The vanilla title screen: the themed main menu off."));
        l.add(amber("Embers", MainMenuScenes.AmberScene.EMBERS, "EMBERS: the original drifting embers (the default elsewhere)."));
        l.add(amber("New", MainMenuScenes.AmberScene.NEW, "NEW: the dusk colonnade (AmberForgeScene)."));
        l.add(amber("Simple", MainMenuScenes.AmberScene.SIMPLE, "SIMPLE: the embers refined (AmberCalmScene)."));
        l.add(amber("Aurora", MainMenuScenes.AmberScene.AURORA,
                "AURORA: your orange aurora picture, animated (AmberAuroraScene); Amber's default in this instance."));
        l.add(variant(MenuTheme.OCEAN, "Waterfall", MainMenuScenes.OceanScene.WATERFALL, "Ocean's waterfall."));
        l.add(variant(MenuTheme.OCEAN, "Cavern Falls", MainMenuScenes.OceanScene.CAVERN_FALLS,
                "CAVERN_FALLS: your waterfall picture, animated (glowing falls, mist, the lake)."));
        // killer560 (2026-10-16): "let's use the one with the player" - Dark lists only that look. MILKY_WAY (without her)
        // and STARS stay in code (MainMenuScenes.DarkScene) but not here.
        l.add(variant(MenuTheme.DARK, "Milky Way", MainMenuScenes.DarkScene.MILKY_WAY_HERO,
                "MILKY_WAY_HERO: your Milky Way picture in black and white, the caped character on the hill."));
        l.add(plain(MenuTheme.LIGHT, "Soft Light", "Light's soft light."));
        // killer560 (2026-10-16): the designer's picture with droplets is Green's final look - "Remove the other ones from
        // the title screen gui instance." The other Green scenes stay in code (MainMenuScenes.GreenScene) but not here.
        l.add(green("Slime Boss", MainMenuScenes.GreenScene.TARGET, GreenTheme.DEFAULT_GRADIENT,
                "TARGET: the designer's picture, animated, with droplets (Green's final look), gradient C."));
        l.add(plain(MenuTheme.CUSTOM, "Aurora", "Custom's aurora, in the menu's Custom colors."));
        LOOKS = Collections.unmodifiableList(l);
    }

    private static Look amber(String name, MainMenuScenes.AmberScene scene, String note) {
        return new Look(MenuTheme.AMBER.label, name, MenuTheme.AMBER, scene, null, null, null, note);
    }

    private static Look green(String name, MainMenuScenes.GreenScene scene, GreenTheme.Gradient gradient, String note) {
        return new Look(MenuTheme.GREEN.label, name, MenuTheme.GREEN, null, scene, gradient, null, note);
    }

    private static Look plain(MenuTheme theme, String name, String note) {
        return new Look(theme.label, name, theme, null, null, null, null, note);
    }

    private static Look variant(MenuTheme theme, String name, Enum<?> scene, String note) {
        return new Look(theme.label, name, theme, null, null, null, scene, note);
    }

    private static Boolean enabled;
    /** The look shown, or null for the saved one (nothing picked yet this session). */
    private static volatile Look current;
    private static volatile int version;
    /** Set by a pick while the title screen is open; that screen re-inits on its next tick (never inside the click). */
    private static boolean relayout;

    private TitleScreenLooks() {
    }

    /** Whether this game is the Title Screen GUI instance (marker file or property). Read once. */
    public static boolean enabled() {
        Boolean e = enabled;
        if (e == null) {
            boolean on = Boolean.getBoolean(PROPERTY);
            if (!on) {
                try {
                    on = Files.exists(FabricLoader.getInstance().getGameDir().resolve(MARKER_FILE));
                } catch (Throwable ignored) {
                }
            }
            e = on;
            enabled = e;
        }
        return e;
    }

    /** Test hook (testkit 1574): turns the switcher on or off for this session; off also drops the picked look. */
    public static void setEnabledForTest(boolean on) {
        enabled = on;
        if (!on) {
            clear();
        }
    }

    public static List<Look> looks() {
        return LOOKS;
    }

    /** The picked look, or null when none is (the saved look shows). */
    public static Look current() {
        return enabled() ? current : null;
    }

    /** The index of the look on screen in {@link #looks()}: the picked one, else the one the saved settings give. */
    public static int currentIndex() {
        Look c = current();
        if (c != null) {
            return LOOKS.indexOf(c);
        }
        if (!MainMenuThemeConfig.getInstance().isEnabled()) {
            return 0;
        }
        MenuTheme theme = MainMenuLook.current().theme();
        for (int i = 0; i < LOOKS.size(); i++) {
            Look l = LOOKS.get(i);
            if (l.theme() == theme && (l.amber() == null || l.amber() == MainMenuScenes.amberScene())
                    && (l.green() == null || (l.green() == MainMenuScenes.greenScene() && l.gradient() == GreenTheme.gradient()))
                    && (l.variant() == null || l.variant() == MainMenuScenes.darkScene()
                        || l.variant() == MainMenuScenes.oceanScene())) {
                return i;
            }
        }
        return -1;
    }

    /** Bumped on every pick, so {@link MainMenuLook#current()} re-resolves. */
    public static int version() {
        return version;
    }

    /** Shows {@code look} live. Writes no setting. */
    public static void apply(Look look) {
        if (!enabled() || look == null) {
            return;
        }
        boolean wasVanilla = !MainMenuTheme.active();
        current = look;
        MainMenuScenes.setAmberScene(look.amber());
        MainMenuScenes.setGreenScene(look.green());
        GreenTheme.setGradient(look.gradient() != null ? look.gradient() : GreenTheme.DEFAULT_GRADIENT);
        MainMenuScenes.setDarkScene(look.variant() instanceof MainMenuScenes.DarkScene d ? d : null);
        MainMenuScenes.setOceanScene(look.variant() instanceof MainMenuScenes.OceanScene o ? o : null);
        version++;
        // The themed layout (column, panel, no Realms) and the vanilla one are built at init, so crossing between them
        // re-inits the title screen; any other change is drawn live from the next frame.
        if (wasVanilla != look.vanilla()) {
            relayout = true;
        }
    }

    /** Steps {@code dir} looks from the one on screen, wrapping. */
    public static void step(int dir) {
        int i = currentIndex();
        apply(LOOKS.get(i < 0 ? (dir > 0 ? 0 : LOOKS.size() - 1) : SettingsButtonWidget.step(i, dir, LOOKS.size())));
    }

    /** Back to the saved look (nothing picked). */
    public static void clear() {
        if (current == null) {
            return;
        }
        current = null;
        MainMenuScenes.setAmberScene(null);
        MainMenuScenes.setDarkScene(null);
        MainMenuScenes.setOceanScene(null);
        MainMenuScenes.setGreenScene(null);
        GreenTheme.setGradient(GreenTheme.DEFAULT_GRADIENT);
        version++;
        relayout = true;
    }

    /** The picked look's five colours for the mod's own screens (so Mod Settings shows Green's gradients), or null. */
    public static int[] menuColours() {
        Look c = current();
        if (c == null || c.vanilla()) {
            return null;
        }
        return MainMenuLook.coloursOf(c.theme(), MenuConfig.getInstance());
    }

    // ---------------------------------------------------------------- the title screen's buttons

    /** The Multiplayer slot's replacement: a plain vanilla Button, so it is themed like the rest of the column. */
    public static final class LooksButton extends Button.Plain {
        private LooksButton(int x, int y, int width, int height, Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }
    }

    /**
     * The corner switcher: a plain vanilla Button (so it is drawn in the main menu's theme like the column, and as a
     * vanilla button on the vanilla look) that also takes a right click. Left click or Enter steps forward, right click
     * back, wrapping - the mod's cycling rule (SettingsButtonWidget.cycling).
     */
    public static final class CornerButton extends Button.Plain {
        private CornerButton(int x, int y, int width, int height, Component message) {
            super(x, y, width, height, message, b -> step(1), DEFAULT_NARRATION);
        }

        @Override
        protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo info) {
            return info.button() == 0 || info.button() == 1;
        }

        @Override
        public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 1) {
                step(-1);
            } else {
                super.onClick(event, doubleClick);
            }
        }

        /** Steps through values (the testkit's 806 rule: every cycling control says so). */
        public boolean isCycling() {
            return true;
        }
    }

    private static java.lang.ref.WeakReference<CornerButton> corner = new java.lang.ref.WeakReference<>(null);

    /** The corner switcher on the open title screen, or null (testkit 1574). */
    public static CornerButton cornerButton() {
        return corner.get();
    }

    /** "Look: Amber · Embers" for the corner button. */
    public static String cornerLabel() {
        int i = currentIndex();
        return "Look: " + (i < 0 ? "Saved" : LOOKS.get(i).label());
    }

    /**
     * Called from the title screen's AFTER_INIT before the themed layout runs: swaps Multiplayer for Main Menu Looks in
     * the same slot (the layout then treats it as Multiplayer) and puts the cycling button in the top right corner.
     */
    static void install(Screen screen) {
        if (!enabled() || !(screen instanceof TitleScreen)) {
            return;
        }
        try {
            List<AbstractWidget> widgets = Screens.getWidgets(screen);
            for (int i = 0; i < widgets.size(); i++) {
                AbstractWidget w = widgets.get(i);
                if (w.getMessage() != null && w.getMessage().getContents() instanceof TranslatableContents tc
                        && "menu.multiplayer".equals(tc.getKey())) {
                    LooksButton looks = new LooksButton(w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                            Component.literal("Main Menu Looks"),
                            b -> McCompat.setScreen(Minecraft.getInstance(), new TitleScreenLooksScreen(screen)));
                    looks.setTooltip(Tooltip.create(Component.literal("Pick any main menu look the mod has.")));
                    widgets.remove(i);
                    widgets.add(i, looks);
                    break;
                }
            }
            var font = Minecraft.getInstance().font;
            int widest = 0;
            for (Look l : LOOKS) {
                widest = Math.max(widest, font.width("Look: " + l.label()));
            }
            int w = Math.min(screen.width - 8, widest + 20);
            CornerButton button = new CornerButton(screen.width - w - 4, 4, w, 20, Component.literal(cornerLabel()));
            button.setTooltip(Tooltip.create(Component.literal("Title Screen Switcher: left click for the next main menu "
                    + "look, right click for the previous one. Only in this instance; it changes no setting.")));
            widgets.add(button);
            corner = new java.lang.ref.WeakReference<>(button);
            ScreenEvents.afterTick(screen).register(s -> {
                if (relayout) {
                    relayout = false;
                    s.resize(s.width, s.height);
                    return;
                }
                CornerButton c = corner.get();
                if (c != null) {
                    String label = cornerLabel();
                    if (!label.equals(c.getMessage().getString())) {
                        c.setMessage(Component.literal(label));
                    }
                }
            });
        } catch (Throwable t) {
            MainMenuTheme.fail("title screen switcher", t);
        }
    }
}
