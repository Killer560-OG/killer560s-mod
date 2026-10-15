package com.killer560.hub.fonts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Themes tab's Font setting (killer560, 2026-10-15: "For themes add a font option. There should be a few preloaded
 * custom fonts as well as some way for them to import their own fonts. They should have a way of making it work for just
 * things in the mod menu, or every line of text they see"). Saved in {@code killer560smod-fonts.json}; re-read by
 * {@code ProfileManager.reloadAllConfigs}. The font itself is built by {@link CustomFonts}.
 */
public final class FontConfig {

    /** Where the chosen font is drawn. */
    public enum Scope {
        /** The mod's own screens (the mod menu, its editors and pickers). */
        MENU("Mod Menu Only"),
        /** Every line of text: chat, the HUD, scoreboards, tooltips, vanilla menus, name tags. */
        EVERYTHING("Everything");

        public final String label;

        Scope(String label) {
            this.label = label;
        }
    }

    public static final int MIN_SIZE = 70;
    public static final int MAX_SIZE = 140;
    public static final int SIZE_STEP = 10;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-fonts.json");

    private static volatile FontConfig instance;
    /** Bumped on every change (and re-read), so {@link CustomFonts} knows to rebuild. */
    private static volatile int version;

    /** {@link FontChoices#MINECRAFT}, a bundled id ({@code bundled:lato}) or an imported file ({@code file:My Font.ttf}). */
    private String font = FontChoices.MINECRAFT;
    private Scope scope = Scope.MENU;
    /** Glyph size in percent of the font's own default size. */
    private int size = 100;

    private FontConfig() {
    }

    public static FontConfig getInstance() {
        FontConfig c = instance;
        if (c == null) {
            load();
            c = instance;
        }
        return c;
    }

    public static synchronized void load() {
        FontConfig cfg = new FontConfig();
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                String f = ConfigJson.getString(obj, "font", cfg.font);
                cfg.font = f == null || f.isBlank() ? FontChoices.MINECRAFT : f.trim();
                cfg.scope = ConfigJson.getEnum(obj, "scope", Scope.class, cfg.scope);
                cfg.size = clampSize(ConfigJson.getInt(obj, "size", cfg.size));
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
            obj.addProperty("font", font);
            obj.addProperty("scope", scope.name());
            obj.addProperty("size", size);
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

    private static int clampSize(int s) {
        int v = Math.max(MIN_SIZE, Math.min(MAX_SIZE, s));
        return Math.round(v / (float) SIZE_STEP) * SIZE_STEP;
    }

    public String getFont() {
        return font;
    }

    public void setFont(String id) {
        String next = id == null || id.isBlank() ? FontChoices.MINECRAFT : id.trim();
        if (!next.equals(font)) {
            font = next;
            version++;
        }
    }

    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope s) {
        Scope next = s == null ? Scope.MENU : s;
        if (next != scope) {
            scope = next;
            version++;
        }
    }

    public int getSize() {
        return size;
    }

    public void setSize(int s) {
        int next = clampSize(s);
        if (next != size) {
            size = next;
            version++;
        }
    }
}
