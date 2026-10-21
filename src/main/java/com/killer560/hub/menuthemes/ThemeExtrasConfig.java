package com.killer560.hub.menuthemes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * What each menu theme brings besides its colours (killer560, 2026-10-21: "Can you add an option for menu themes to have
 * their own background music as well for all themes"). Saved in {@code killer560smod-menu-themeextras.json} (the
 * {@code menu} row of ModPaths, so {@code config/killer560/interface/menu/}); re-read by
 * {@code ProfileManager.reloadAllConfigs}. Everything here is off or "none" until he picks it, so a theme with nothing set
 * looks and sounds exactly as it did before.
 * <p>Per theme ({@link MenuTheme}, Custom included): the music track ({@link ThemeMusic}). Shared: the Theme Music switch
 * (default OFF) and its volume.
 */
public final class ThemeExtrasConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-menu-themeextras.json");

    /** The theme's own bundled loop (Custom has none, so it plays nothing). */
    public static final String MUSIC_THEME = "theme";
    /** No music for this theme: vanilla's menu music plays as it always did. */
    public static final String MUSIC_NONE = "none";

    public static final int MIN_VOLUME = 0;
    public static final int MAX_VOLUME = 100;
    public static final int VOLUME_STEP = 5;

    private static volatile ThemeExtrasConfig instance;
    /** Bumped on every change and every load, so the players know to re-resolve. */
    private static volatile int version;

    /** One theme's extras. */
    public static final class Entry {
        /** {@link #MUSIC_THEME}, {@link #MUSIC_NONE}, {@code bundled:<id>} or {@code import:<file name>}. */
        String music = MUSIC_THEME;
    }

    private boolean musicOn = false;
    /** Percent, 0..100. */
    private int musicVolume = 50;
    private final Map<MenuTheme, Entry> themes = new EnumMap<>(MenuTheme.class);

    private ThemeExtrasConfig() {
        for (MenuTheme t : MenuTheme.values()) {
            themes.put(t, new Entry());
        }
    }

    public static ThemeExtrasConfig getInstance() {
        ThemeExtrasConfig c = instance;
        if (c == null) {
            load();
            c = instance;
        }
        return c;
    }

    public static synchronized void load() {
        ThemeExtrasConfig cfg = new ThemeExtrasConfig();
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.musicOn = ConfigJson.getBool(obj, "musicOn", cfg.musicOn);
                cfg.musicVolume = clampVolume(ConfigJson.getInt(obj, "musicVolume", cfg.musicVolume));
                if (obj.has("themes") && obj.get("themes").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("themes").entrySet()) {
                        MenuTheme t = parseTheme(e.getKey());
                        if (t == null || !e.getValue().isJsonObject()) {
                            continue;
                        }
                        JsonObject o = e.getValue().getAsJsonObject();
                        Entry entry = cfg.themes.get(t);
                        entry.music = clean(ConfigJson.getString(o, "music", entry.music), MUSIC_THEME);
                    }
                }
            }
        } catch (Exception ignored) {
            // A broken file reads as the defaults, like every other config.
        }
        instance = cfg;
        version++;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("musicOn", musicOn);
            obj.addProperty("musicVolume", musicVolume);
            JsonObject all = new JsonObject();
            for (Map.Entry<MenuTheme, Entry> e : themes.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("music", e.getValue().music);
                all.add(e.getKey().name(), o);
            }
            obj.add("themes", all);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public static Path path() {
        return CONFIG_PATH;
    }

    public static int version() {
        return version;
    }

    /** An exact theme name, or null (unlike {@link MenuTheme#parse}, an unknown key is not Amber's). */
    private static MenuTheme parseTheme(String s) {
        for (MenuTheme t : MenuTheme.values()) {
            if (t.name().equalsIgnoreCase(s)) {
                return t;
            }
        }
        return null;
    }

    private static String clean(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    private static int clampVolume(int v) {
        int c = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, v));
        return Math.round(c / (float) VOLUME_STEP) * VOLUME_STEP;
    }

    private Entry entry(MenuTheme theme) {
        return themes.get(theme == null ? MenuTheme.AMBER : theme);
    }

    // ---------------------------------------------------------------- music

    public boolean isMusicOn() {
        return musicOn;
    }

    public void setMusicOn(boolean on) {
        if (on != musicOn) {
            musicOn = on;
            version++;
        }
    }

    public int getMusicVolume() {
        return musicVolume;
    }

    public void setMusicVolume(int percent) {
        int next = clampVolume(percent);
        if (next != musicVolume) {
            musicVolume = next;
            version++;
        }
    }

    public String getMusic(MenuTheme theme) {
        return entry(theme).music;
    }

    public void setMusic(MenuTheme theme, String id) {
        String next = clean(id, MUSIC_THEME);
        Entry e = entry(theme);
        if (!next.equals(e.music)) {
            e.music = next;
            version++;
        }
    }
}
