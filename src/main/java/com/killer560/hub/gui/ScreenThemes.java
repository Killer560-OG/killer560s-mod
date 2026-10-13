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
 * presets, Custom being the menu's saved Custom colours. {@link MenuTheme#forScreen} resolves an id to its five base
 * colours and {@link MenuStyle#use} applies them while that screen is open, so nothing else changes per screen.
 * <p>
 * A screen whose own older style choices should stay available passes them to {@link #button} as extra choices; their
 * names are stored as given and {@link #colours} leaves them to the screen (it returns the menu's colours for them).
 * Saved in {@code killer560smod-menu-screens.json} (ModPaths' {@code menu} row puts it beside the menu's own file) and
 * re-read by {@code ProfileManager.reloadAllConfigs}.
 */
public final class ScreenThemes {

    private ScreenThemes() {
    }

    /** "Theme Default": the screen follows the mod menu's theme. */
    public static final String DEFAULT = "DEFAULT";

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
        return map().getOrDefault(screenId, DEFAULT);
    }

    public static synchronized void set(String screenId, String choice) {
        String c = choice == null ? DEFAULT : choice.trim().toUpperCase(Locale.ROOT);
        if (DEFAULT.equals(c)) {
            map().remove(screenId);
        } else {
            map().put(screenId, c);
        }
        version++;
    }

    /** Theme Default, then the menu's presets, then Custom (the menu's saved Custom colours). */
    public static List<String> builtInChoices() {
        List<String> out = new ArrayList<>();
        out.add(DEFAULT);
        for (MenuTheme t : MenuTheme.values()) {
            out.add(t.name());
        }
        return out;
    }

    /** The label a choice shows on its button. */
    public static String label(String choice) {
        if (DEFAULT.equals(choice)) {
            return "Theme Default";
        }
        for (MenuTheme t : MenuTheme.values()) {
            if (t.name().equals(choice)) {
                return t.label;
            }
        }
        String lower = choice.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.isEmpty() ? choice : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** The five base colours the screen draws in: the menu's own for Theme Default (or an unknown/extra choice). */
    public static int[] colours(String screenId) {
        MenuConfig menu = MenuConfig.getInstance();
        String c = get(screenId);
        if (DEFAULT.equals(c)) {
            return menu.activeColours();
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
        List<String> all = builtInChoices();
        for (String e : extraChoices) {
            all.add(e.toUpperCase(Locale.ROOT));
        }
        return SettingsButtonWidget.cycling(text(label, screenId), (btn, dir) -> {
            int i = Math.max(0, all.indexOf(get(screenId)));
            set(screenId, all.get(SettingsButtonWidget.step(i, dir, all.size())));
            save();
            btn.setMessage(text(label, screenId));
        }).bounds(x, y, w, h).build();
    }

    private static Component text(String label, String screenId) {
        return Component.literal(label + ": " + label(get(screenId)));
    }
}
