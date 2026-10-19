package com.killer560.hub.cape;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Custom cape (killer560, 2026-10-19). The built-in designs are PNG files in the mod's own assets (64x32, the vanilla cape
 * sheet); the chosen one is worn by your own player only. Off by default ({@link #NONE}).
 */
public final class CapeConfig {

    public static final String NONE = "none";
    /** The Make Your Own cape: generated at runtime from the two colours and the pattern below. */
    public static final String CUSTOM = "custom";

    /** Layouts for the Make Your Own cape. */
    public enum Pattern {
        SOLID("Solid"), STRIPES("Stripes"), GRADIENT("Gradient"), CENTRE_BAND("Centre Band");

        public final String label;

        Pattern(String label) {
            this.label = label;
        }
    }

    /** Built-in designs, in the order the cape button cycles through them. */
    public static final List<String> DESIGNS = List.of(
            "ember", "ocean", "forest", "royal", "midnight", "sunset", "monochrome", "gold");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-cape.json");

    private static CapeConfig instance;

    private String selected = NONE;
    private int customColourA = 0x2D6CDF;
    private int customColourB = 0xF2B134;
    private Pattern customPattern = Pattern.STRIPES;

    private CapeConfig() {
    }

    public static CapeConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new CapeConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            CapeConfig cfg = new CapeConfig();
            cfg.setSelected(ConfigJson.getString(obj, "selected", NONE));
            cfg.setCustomColourA(ConfigJson.getInt(obj, "customColourA", cfg.customColourA));
            cfg.setCustomColourB(ConfigJson.getInt(obj, "customColourB", cfg.customColourB));
            try {
                cfg.customPattern = Pattern.valueOf(ConfigJson.getString(obj, "customPattern", cfg.customPattern.name()));
            } catch (IllegalArgumentException ignored) {
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new CapeConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("selected", selected);
            obj.addProperty("customColourA", customColourA);
            obj.addProperty("customColourB", customColourB);
            obj.addProperty("customPattern", customPattern.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public String getSelected() {
        return selected;
    }

    public boolean isCustom() {
        return CUSTOM.equals(selected);
    }

    public int getCustomColourA() {
        return customColourA;
    }

    public void setCustomColourA(int rgb) {
        this.customColourA = rgb & 0xFFFFFF;
    }

    public int getCustomColourB() {
        return customColourB;
    }

    public void setCustomColourB(int rgb) {
        this.customColourB = rgb & 0xFFFFFF;
    }

    public Pattern getCustomPattern() {
        return customPattern;
    }

    public void setCustomPattern(Pattern p) {
        this.customPattern = p == null ? Pattern.STRIPES : p;
    }

    /** Anything that is not a built-in design becomes {@link #NONE}. */
    public void setSelected(String id) {
        this.selected = id != null && (id.equals(NONE) || id.equals(CUSTOM) || DESIGNS.contains(id)) ? id : NONE;
    }

    public boolean hasCape() {
        return !NONE.equals(selected);
    }

    /** Cycles None, each design, Make Your Own, then back to None. */
    public void cycle(int dir) {
        // Order: None, the built-in designs, then Make Your Own.
        int idx = selected.equals(NONE) ? -1 : selected.equals(CUSTOM) ? DESIGNS.size() : DESIGNS.indexOf(selected);
        int size = DESIGNS.size() + 2;
        int next = ((idx + 1 + dir) % size + size) % size;
        selected = next == 0 ? NONE : next == size - 1 ? CUSTOM : DESIGNS.get(next - 1);
    }
}
