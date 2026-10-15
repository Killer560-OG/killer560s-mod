package com.killer560.hub.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The mod menu's own settings (2026-10-12, Option A menu): the features starred as Favourites, in the order they were
 * starred. A feature is named by its tab class's simple name ({@code HealthAndManaBarsTab}), the same id the testing
 * build's Mark tested uses, so renaming a label never loses a star. Saved in {@code killer560smod-menu.json}; profiles
 * carry it like every other setting file.
 * <p>Saved themes (killer560, 2026-10-13: "Make it so each custom menu you save you can rename. Also they should be
 * saved."): any number of named Custom colour sets. Using one makes it the Custom set the menu draws; while one is in
 * use, changing a Custom colour changes that saved theme too.
 */
public final class MenuConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-menu.json");

    private static MenuConfig instance;

    private final Set<String> favourites = new LinkedHashSet<>();
    /** The colour preset the menu draws in (killer560, 2026-10-12): Amber by default. */
    private MenuTheme theme = MenuTheme.AMBER;
    /** The menu's Animations setting (killer560, 2026-10-15): Smooth by default. */
    private MenuAnim.Mode animations = MenuAnim.Mode.SMOOTH;
    /** Animation Speed (killer560, 2026-10-15): Fast, today's timing, by default. */
    private MenuAnim.Speed animationSpeed = MenuAnim.Speed.FAST;
    /** The preset the Custom colours were started from (what Reset puts back). */
    private MenuTheme customBase = MenuTheme.AMBER;
    /** Custom's five colours, in {@link MenuTheme.Slot} order. */
    private final int[] custom = MenuTheme.AMBER.base();
    /** Bumped on every theme change, so the drawing side knows to re-derive its palette. */
    private static int version;

    /** A named Custom colour set: its five colours in {@link MenuTheme.Slot} order and the preset it started from. */
    public static final class SavedTheme {
        /** Stable across renames and deletions of other themes ("T1", "T2"...): a screen's Theme setting names a saved
         *  theme by it ({@link ScreenThemes}), never by position or name. */
        public String id;
        public String name;
        public MenuTheme base;
        public final int[] colours;

        SavedTheme(String id, String name, MenuTheme base, int[] colours) {
            this.id = id;
            this.name = name;
            this.base = base;
            this.colours = colours;
        }
    }

    public static final int MAX_THEME_NAME = 24;
    private final List<SavedTheme> savedThemes = new ArrayList<>();
    /** The saved theme the Custom set is, or -1 when Custom is not a saved one. */
    private int activeSaved = -1;

    private MenuConfig() {
    }

    public static MenuConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        MenuConfig cfg = new MenuConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (obj.has("theme")) {
                    cfg.theme = MenuTheme.parse(obj.get("theme").getAsString());
                }
                if (obj.has("animations")) {
                    cfg.animations = MenuAnim.Mode.parse(obj.get("animations").getAsString());
                }
                if (obj.has("animationSpeed")) {
                    cfg.animationSpeed = MenuAnim.Speed.parse(obj.get("animationSpeed").getAsString());
                }
                if (obj.has("customBase")) {
                    MenuTheme b = MenuTheme.parse(obj.get("customBase").getAsString());
                    cfg.customBase = b == MenuTheme.CUSTOM ? MenuTheme.AMBER : b;
                }
                System.arraycopy(cfg.customBase.base(), 0, cfg.custom, 0, cfg.custom.length);
                if (obj.has("custom") && obj.get("custom").isJsonObject()) {
                    readColours(obj.getAsJsonObject("custom"), cfg.custom);
                }
                if (obj.has("savedThemes") && obj.get("savedThemes").isJsonArray()) {
                    for (JsonElement e : obj.getAsJsonArray("savedThemes")) {
                        if (!e.isJsonObject()) {
                            continue;
                        }
                        JsonObject t = e.getAsJsonObject();
                        MenuTheme base = t.has("base") ? MenuTheme.parse(t.get("base").getAsString()) : MenuTheme.AMBER;
                        base = base == MenuTheme.CUSTOM ? MenuTheme.AMBER : base;
                        int[] cols = base.base();
                        if (t.has("colours") && t.get("colours").isJsonObject()) {
                            readColours(t.getAsJsonObject("colours"), cols);
                        }
                        String name = t.has("name") ? t.get("name").getAsString() : "";
                        String id = t.has("id") && t.get("id").isJsonPrimitive() ? t.get("id").getAsString().trim() : "";
                        cfg.savedThemes.add(new SavedTheme(id, cleanName(name), base, cols));
                    }
                }
                cfg.assignMissingIds();
                if (obj.has("activeSaved")) {
                    int a = obj.get("activeSaved").getAsInt();
                    cfg.activeSaved = a >= 0 && a < cfg.savedThemes.size() ? a : -1;
                }
                if (obj.has("favourites") && obj.get("favourites").isJsonArray()) {
                    for (JsonElement e : obj.getAsJsonArray("favourites")) {
                        if (e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                            cfg.favourites.add(e.getAsString().trim());
                        }
                    }
                }
            } catch (Exception ignored) {
                // A broken file reads as the defaults, like every other config.
            }
        }
        instance = cfg;
        version++;
    }

    private static void readColours(JsonObject c, int[] into) {
        for (MenuTheme.Slot slot : MenuTheme.Slot.values()) {
            String k = slot.name().toLowerCase(java.util.Locale.ROOT);
            if (c.has(k)) {
                into[slot.ordinal()] = 0xFF000000 | (int) Long.parseLong(c.get(k).getAsString().replace("#", ""), 16);
            }
        }
    }

    private static JsonObject writeColours(int[] cols) {
        JsonObject c = new JsonObject();
        for (MenuTheme.Slot slot : MenuTheme.Slot.values()) {
            c.addProperty(slot.name().toLowerCase(java.util.Locale.ROOT), String.format("#%06X", cols[slot.ordinal()] & 0xFFFFFF));
        }
        return c;
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.strip();
        return n.length() > MAX_THEME_NAME ? n.substring(0, MAX_THEME_NAME) : n;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            JsonArray arr = new JsonArray();
            for (String id : favourites) {
                arr.add(id);
            }
            obj.add("favourites", arr);
            obj.addProperty("theme", theme.name());
            obj.addProperty("animations", animations.name());
            obj.addProperty("animationSpeed", animationSpeed.name());
            obj.addProperty("customBase", customBase.name());
            obj.add("custom", writeColours(custom));
            JsonArray saved = new JsonArray();
            for (SavedTheme t : savedThemes) {
                JsonObject o = new JsonObject();
                o.addProperty("id", t.id);
                o.addProperty("name", t.name);
                o.addProperty("base", t.base.name());
                o.add("colours", writeColours(t.colours));
                saved.add(o);
            }
            obj.add("savedThemes", saved);
            obj.addProperty("activeSaved", activeSaved);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public static Path path() {
        return CONFIG_PATH;
    }

    /** Changes whenever the theme or a custom colour changes (or the file is re-read). */
    public static int version() {
        return version;
    }

    public MenuAnim.Speed getAnimationSpeed() {
        return animationSpeed;
    }

    public void setAnimationSpeed(MenuAnim.Speed speed) {
        animationSpeed = speed == null ? MenuAnim.Speed.FAST : speed;
    }

    public MenuAnim.Mode getAnimations() {
        return animations;
    }

    public void setAnimations(MenuAnim.Mode mode) {
        animations = mode == null ? MenuAnim.Mode.SMOOTH : mode;
    }

    public MenuTheme getTheme() {
        return theme;
    }

    /** Picks a theme. Choosing Custom from a preset starts Custom from that preset's colours (killer560's
     *  "a Custom option starting from the active preset"); Custom keeps its own colours after that. */
    public void setTheme(MenuTheme t) {
        MenuTheme next = t == null ? MenuTheme.AMBER : t;
        if (next == MenuTheme.CUSTOM && theme != MenuTheme.CUSTOM) {
            startCustomFrom(theme);
        }
        theme = next;
        version++;
    }

    public MenuTheme getCustomBase() {
        return customBase;
    }

    /** The five colours the menu draws from right now (the preset's, or Custom's). */
    public int[] activeColours() {
        return theme == MenuTheme.CUSTOM ? custom.clone() : theme.base();
    }

    public int getCustomColour(MenuTheme.Slot slot) {
        return custom[slot.ordinal()];
    }

    /** Sets one Custom colour and switches the menu to Custom (started from the preset in use, if it was one). */
    public void setCustomColour(MenuTheme.Slot slot, int argb) {
        if (theme != MenuTheme.CUSTOM) {
            startCustomFrom(theme);
            theme = MenuTheme.CUSTOM;
        }
        custom[slot.ordinal()] = 0xFF000000 | (argb & 0xFFFFFF);
        if (activeSaved >= 0) {
            savedThemes.get(activeSaved).colours[slot.ordinal()] = custom[slot.ordinal()];
        }
        version++;
    }

    /** Custom's colours back to the preset it was started from. */
    public void resetCustom() {
        System.arraycopy(customBase.base(), 0, custom, 0, custom.length);
        if (activeSaved >= 0) {
            System.arraycopy(custom, 0, savedThemes.get(activeSaved).colours, 0, custom.length);
        }
        version++;
    }

    // ---- saved themes ----

    /** The saved themes, in the order they were saved (read-only; change them through this class). */
    public List<SavedTheme> getSavedThemes() {
        return java.util.Collections.unmodifiableList(savedThemes);
    }

    /** The saved theme the menu draws now, or -1 (a preset, or a Custom set that is not saved). */
    public int getActiveSaved() {
        return theme == MenuTheme.CUSTOM ? activeSaved : -1;
    }

    /** Saves the colours the menu draws now as a new theme called "Custom N" and switches to it. Returns its index. */
    public int saveCurrentAsTheme() {
        int[] cols = activeColours();
        MenuTheme base = theme == MenuTheme.CUSTOM ? customBase : theme;
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (SavedTheme t : savedThemes) {
            taken.add(t.name.toLowerCase(java.util.Locale.ROOT));
        }
        int n = savedThemes.size() + 1;
        while (taken.contains("custom " + n)) {
            n++;
        }
        savedThemes.add(new SavedTheme(freshId(), "Custom " + n, base, cols));
        useSavedTheme(savedThemes.size() - 1);
        return savedThemes.size() - 1;
    }

    /** The saved theme with this {@link SavedTheme#id}, or null (deleted, or never there). */
    public SavedTheme savedThemeById(String id) {
        if (id != null) {
            for (SavedTheme t : savedThemes) {
                if (t.id.equalsIgnoreCase(id.trim())) {
                    return t;
                }
            }
        }
        return null;
    }

    /** "T" plus the lowest number nobody uses (a file from before ids gets T1, T2... in list order, once). */
    private String freshId() {
        int n = 1;
        while (true) {
            String id = "T" + n;
            boolean taken = false;
            for (SavedTheme t : savedThemes) {
                if (id.equalsIgnoreCase(t.id)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return id;
            }
            n++;
        }
    }

    private void assignMissingIds() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SavedTheme t : savedThemes) {
            if (t.id == null || t.id.isBlank() || !seen.add(t.id.toUpperCase(java.util.Locale.ROOT))) {
                t.id = "";
            }
        }
        for (SavedTheme t : savedThemes) {
            if (t.id.isEmpty()) {
                t.id = freshId();
                seen.add(t.id.toUpperCase(java.util.Locale.ROOT));
            }
        }
    }

    /** Makes saved theme {@code i} the Custom set and draws it. */
    public void useSavedTheme(int i) {
        if (i < 0 || i >= savedThemes.size()) {
            return;
        }
        SavedTheme t = savedThemes.get(i);
        customBase = t.base;
        System.arraycopy(t.colours, 0, custom, 0, custom.length);
        theme = MenuTheme.CUSTOM;
        activeSaved = i;
        version++;
    }

    /** Renames saved theme {@code i}: trimmed, at most {@link #MAX_THEME_NAME} characters; a blank name shows as
     *  "Untitled" ({@link #shownName}). */
    public void renameSavedTheme(int i, String name) {
        if (i >= 0 && i < savedThemes.size()) {
            savedThemes.get(i).name = cleanName(name);
        }
    }

    /** Deletes saved theme {@code i}. The menu keeps drawing the same colours (Custom just stops being that theme). */
    public void deleteSavedTheme(int i) {
        if (i < 0 || i >= savedThemes.size()) {
            return;
        }
        savedThemes.remove(i);
        if (activeSaved == i) {
            activeSaved = -1;
        } else if (activeSaved > i) {
            activeSaved--;
        }
        version++;
    }

    /** A saved theme's name as shown: "Untitled" when blank. */
    public static String shownName(SavedTheme t) {
        return t.name == null || t.name.isBlank() ? "Untitled" : t.name;
    }

    private void startCustomFrom(MenuTheme preset) {
        if (preset == MenuTheme.CUSTOM) {
            return;
        }
        activeSaved = -1; // a fresh Custom started from a preset is not any saved theme
        customBase = preset;
        System.arraycopy(preset.base(), 0, custom, 0, custom.length);
    }

    /** The starred feature ids, oldest first. */
    public List<String> getFavourites() {
        return new ArrayList<>(favourites);
    }

    public void setFavourites(List<String> ids) {
        favourites.clear();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    favourites.add(id.trim());
                }
            }
        }
    }

    public boolean isFavourite(String id) {
        return favourites.contains(id);
    }

    public void setFavourite(String id, boolean on) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (on) {
            favourites.add(id);
        } else {
            favourites.remove(id);
        }
    }
}
