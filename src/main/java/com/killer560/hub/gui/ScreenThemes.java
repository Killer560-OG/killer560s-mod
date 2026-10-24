package com.killer560.hub.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModPaths;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Each themed screen's own Theme setting (killer560, 2026-10-13: "have each theme option start on theme default which
 * copies it, then you can toggle through different selected themes for each menu"). A screen id maps to
 * {@link #DEFAULT} ("Theme Default": follows the mod menu's theme, what every screen starts on) or to one of the menu's
 * presets, Custom being the menu's saved Custom colors. {@link MenuTheme#forScreen} resolves an id to its five base
 * colors and {@link MenuStyle#use} applies them while that screen is open, so nothing else changes per screen.
 * <p>
 * <p>The choices (killer560, 2026-10-15: "if i make a new custom theme it would show up there"): Theme Default, the four
 * presets, Custom (the menu's live Custom colors), then every theme he has SAVED, read live from {@link MenuConfig} each
 * time the list is used - a theme saved a second ago is on the next press. A saved theme is stored by its stable id
 * ({@code SAVED:T2}), so renaming or deleting another one never moves it; one that has been deleted reads as Default.
 * Overlays that draw over the game (the Storage Overlay and its kin, {@link OverlayThemes}) use the same list with
 * "Default" meaning THEIR OWN look rather than following the menu.
 * <p>
 * A screen whose own older style choices should stay available passes them to {@link #button} as extra choices; their
 * names are stored as given and {@link #colours} leaves them to the screen (it returns the menu's colors for them).
 * Saved in {@code killer560smod-menu-screens.json} (ModPaths' {@code menu} row puts it beside the menu's own file) and
 * re-read by {@code ProfileManager.reloadAllConfigs}.
 */
public final class ScreenThemes {

    private ScreenThemes() {
    }

    /** "Theme Default": the screen follows the mod menu's theme. */
    public static final String DEFAULT = "DEFAULT";
    /** A saved theme's choice is this plus its {@link MenuConfig.SavedTheme#id}. */
    public static final String SAVED_PREFIX = "SAVED:";

    /**
     * One Theme per mod section (killer560, 2026-10-18: "one theme specifically per mod section ... croeseus would have its
     * own specific theme but not every single part of it would have its own. It should be this way for every mod"). A
     * section's screens and overlays all read and write the one choice stored under the section's id; the left side here
     * are the ids that used to have a Theme setting of their own (Croesus' chest panel and start button, Experiments'
     * start buttons, the Leap Menu overlay). Code keeps asking for its old id; this maps it.
     */
    private static final Map<String, String> SECTION = Map.of(
            "chest_profit", "croesus",
            "start_buttons", "croesus",
            "experiments_start", "profit",
            "leap_menu", "leaporder");

    /** The id a Theme choice is stored under: the section's, for a part of a section; otherwise {@code id} itself. */
    public static String section(String id) {
        return SECTION.getOrDefault(id, id);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = ModPaths.config("killer560smod-menu-screens.json");

    private static Map<String, String> choices;
    private static int version;

    /** Re-reads the file. */
    public static synchronized void load() {
        Map<String, String> out = new LinkedHashMap<>();
        if (Files.exists(PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        out.put(e.getKey(), e.getValue().getAsString().trim().toUpperCase(Locale.ROOT));
                    }
                }
            } catch (Exception ignored) {
                // A broken file reads as every screen on Theme Default.
            }
        }
        // A choice saved under a part's old id moves to its section's id (unless the section already has one).
        for (Map.Entry<String, String> e : SECTION.entrySet()) {
            String old = out.remove(e.getKey());
            if (old != null) {
                out.putIfAbsent(e.getValue(), old);
            }
        }
        choices = out;
        version++;
    }

    private static synchronized Map<String, String> map() {
        if (choices == null) {
            load();
        }
        return choices;
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(PATH.getParent());
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, String> e : map().entrySet()) {
                obj.addProperty(e.getKey(), e.getValue());
            }
            Files.writeString(PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public static Path path() {
        return PATH;
    }

    /** Changes whenever a screen's choice changes (or the file is re-read). */
    public static int version() {
        return version;
    }

    /** The screen's choice: {@link #DEFAULT}, a {@link MenuTheme} name, or one of the screen's own extra names. */
    public static String get(String screenId) {
        return map().getOrDefault(section(screenId), DEFAULT);
    }

    public static synchronized void set(String screenId, String choice) {
        screenId = section(screenId);
        String c = choice == null ? DEFAULT : choice.trim().toUpperCase(Locale.ROOT);
        if (DEFAULT.equals(c)) {
            map().remove(screenId);
        } else {
            map().put(screenId, c);
        }
        version++;
    }

    /** Theme Default, then the menu's presets, then Custom (the menu's live Custom colors), then every saved theme in the
     *  order he saved them. Read live: call it when the choice is made, never cache it. */
    public static List<String> builtInChoices() {
        List<String> out = new ArrayList<>();
        out.add(DEFAULT);
        for (MenuTheme t : MenuTheme.values()) {
            out.add(t.name());
        }
        for (MenuConfig.SavedTheme t : MenuConfig.getInstance().getSavedThemes()) {
            out.add(SAVED_PREFIX + t.id.toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /** {@link #builtInChoices} followed by a screen's own extra choices. */
    public static List<String> choices(String... extraChoices) {
        List<String> all = builtInChoices();
        for (String e : extraChoices) {
            all.add(e.toUpperCase(Locale.ROOT));
        }
        return all;
    }

    /** Whether the choice is a saved theme's. */
    public static boolean isSaved(String choice) {
        return choice != null && choice.startsWith(SAVED_PREFIX);
    }

    /** The saved theme a choice names, or null (not a saved choice, or that theme has been deleted). */
    public static MenuConfig.SavedTheme savedTheme(String choice) {
        return isSaved(choice) ? MenuConfig.getInstance().savedThemeById(choice.substring(SAVED_PREFIX.length())) : null;
    }

    /** The choice as the stored string for the saved theme at {@code index} in {@link MenuConfig#getSavedThemes}. */
    public static String savedChoice(int index) {
        return SAVED_PREFIX + MenuConfig.getInstance().getSavedThemes().get(index).id.toUpperCase(Locale.ROOT);
    }

    /** The label a choice shows on its button. */
    public static String label(String choice) {
        return label(choice, false);
    }

    /** {@link #label(String)}; for an overlay ({@code overlay} true) the first choice reads "Default" - its own look. */
    public static String label(String choice, boolean overlay) {
        if (DEFAULT.equals(choice)) {
            return overlay ? "Default" : "Theme Default";
        }
        if (isSaved(choice)) {
            MenuConfig.SavedTheme t = savedTheme(choice);
            return t == null ? (overlay ? "Default" : "Theme Default") : MenuConfig.shownName(t);
        }
        for (MenuTheme t : MenuTheme.values()) {
            if (t.name().equals(choice)) {
                return t.label;
            }
        }
        String lower = choice.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.isEmpty() ? choice : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** The five base colors the screen draws in: the menu's own for Theme Default (or an unknown/extra choice). */
    public static int[] colours(String screenId) {
        MenuConfig menu = MenuConfig.getInstance();
        String c = get(screenId);
        if (DEFAULT.equals(c)) {
            return menu.activeColours();
        }
        if (isSaved(c)) {
            MenuConfig.SavedTheme t = savedTheme(c);
            return t == null ? menu.activeColours() : t.colours.clone();
        }
        if (MenuTheme.CUSTOM.name().equals(c)) {
            int[] out = new int[MenuTheme.Slot.values().length];
            for (MenuTheme.Slot slot : MenuTheme.Slot.values()) {
                out[slot.ordinal()] = menu.getCustomColour(slot);
            }
            return out;
        }
        for (MenuTheme t : MenuTheme.presets()) {
            if (t.name().equals(c)) {
                return t.base();
            }
        }
        return menu.activeColours();
    }

    /** The "Theme: ..." cycling button for a feature's settings tab (left forward, right back, wrapping). */
    public static SettingsButtonWidget button(String screenId, int x, int y, int w, int h, String... extraChoices) {
        return button(screenId, "Theme", x, y, w, h, extraChoices);
    }

    /** {@link #button} with its own label ("Swap Accounts Theme" in a tab that is not that screen's own). */
    public static SettingsButtonWidget button(String screenId, String label, int x, int y, int w, int h,
                                              String... extraChoices) {
        return button(screenId, label, false, x, y, w, h, extraChoices);
    }

    /**
     * The Menu Theme button of an overlay that draws over the game (killer560, 2026-10-15): "Menu Theme: Default" is the
     * overlay's own look, then the presets, Custom and every saved theme. Left forward, right back, wrapping.
     */
    public static SettingsButtonWidget overlayButton(String overlayId, int x, int y, int w, int h) {
        return button(overlayId, "Menu Theme", true, x, y, w, h);
    }

    /** {@link #overlayButton} with its own label ("Chest Panel Menu Theme" in a tab with more than one). */
    public static SettingsButtonWidget overlayButton(String overlayId, String label, int x, int y, int w, int h) {
        return button(overlayId, label, true, x, y, w, h);
    }

    private static SettingsButtonWidget button(String screenId, String label, boolean overlay, int x, int y, int w, int h,
                                               String... extraChoices) {
        return SettingsButtonWidget.cycling(text(label, screenId, overlay), (btn, dir) -> {
            // The list is read now, so a theme saved since the tab was built is in it.
            List<String> all = choices(extraChoices);
            int i = Math.max(0, all.indexOf(get(screenId)));
            set(screenId, all.get(SettingsButtonWidget.step(i, dir, all.size())));
            save();
            btn.setMessage(text(label, screenId, overlay));
        }).bounds(x, y, w, h).build();
    }

    private static Component text(String label, String screenId, boolean overlay) {
        return Component.literal(label + ": " + label(get(screenId), overlay));
    }

    /**
     * The five colors an OVERLAY draws in, or null when it is on Default (its own look). The one question every overlay
     * asks; {@link OverlayThemes#of} wraps it into a full palette.
     */
    public static int[] overlayColours(String overlayId) {
        String c = get(overlayId);
        if (DEFAULT.equals(c) || (isSaved(c) && savedTheme(c) == null)) {
            return null;
        }
        return colours(overlayId);
    }

    /**
     * Adds a screen's Theme button one row under everything already in {@code widgets}, so no existing row moves (screen
     * sweep C's tabs). {@code width} is capped at 220.
     */
    public static void append(List<net.minecraft.client.gui.components.AbstractWidget> widgets, String screenId, int x,
                              int width, int fallbackY) {
        int y = fallbackY;
        for (net.minecraft.client.gui.components.AbstractWidget w : widgets) {
            y = Math.max(y, w.getY() + w.getHeight() + 6);
        }
        widgets.add(button(screenId, x, y, Math.min(220, width), 20));
    }
}
