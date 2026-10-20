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

    /** Built-in designs: one per menu theme (killer560, 2026-10-19), in the order the cape button cycles through them. */
    public static final List<String> DESIGNS = List.of("amber", "dark", "light", "ocean", "green");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-cape.json");

    private static CapeConfig instance;

    private String selected = NONE;
    private int customColourA = 0x2D6CDF;
    private int customColourB = 0xF2B134;
    private Pattern customPattern = Pattern.STRIPES;
    /** The hand-painted back panel, row by row (10 wide, 16 tall), RGB per pixel; {@link #UNSET} takes the pattern's colour. */
    public static final int PANEL_W = 10;
    public static final int PANEL_H = 16;
    public static final int UNSET = -1;
    private int[] pixels = unsetPanel();
    /** Bumped on every change, so the cape texture knows to rebuild (killer560, 2026-10-19: Make Your Own designer). */
    private int revision;

    private CapeConfig() {
    }

    private static int[] unsetPanel() {
        int[] p = new int[PANEL_W * PANEL_H];
        java.util.Arrays.fill(p, UNSET);
        return p;
    }

    /** The painted colour of back-panel pixel (x, y), or {@link #UNSET}. */
    public int getPixel(int x, int y) {
        return pixels[y * PANEL_W + x];
    }

    public void setPixel(int x, int y, int rgb) {
        pixels[y * PANEL_W + x] = rgb == UNSET ? UNSET : rgb & 0xFFFFFF;
        revision++;
    }

    public void clearPixels() {
        java.util.Arrays.fill(pixels, UNSET);
        revision++;
    }

    public int getRevision() {
        return revision;
    }

    /** A named design saved from Make Your Own (killer560, 2026-10-19: "an option to name it and save it"). */
    public record SavedDesign(String name, int[] pixels) {
    }

    private final List<SavedDesign> saved = new java.util.ArrayList<>();

    public List<SavedDesign> getSaved() {
        return java.util.Collections.unmodifiableList(saved);
    }

    /** Saves the current panel under {@code name}, replacing a saved design with the same name. */
    public void saveDesign(String name) {
        SavedDesign d = new SavedDesign(name, pixels.clone());
        saved.removeIf(s -> s.name().equals(name));
        saved.add(d);
    }

    /** Puts saved design {@code index} on the panel. */
    public void loadDesign(int index) {
        if (index < 0 || index >= saved.size()) {
            return;
        }
        System.arraycopy(saved.get(index).pixels(), 0, pixels, 0, pixels.length);
        revision++;
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
            if (obj.has("saved") && obj.get("saved").isJsonArray()) {
                for (var el : obj.getAsJsonArray("saved")) {
                    var o = el.getAsJsonObject();
                    int[] p = new int[PANEL_W * PANEL_H];
                    java.util.Arrays.fill(p, UNSET);
                    var arr = o.getAsJsonArray("panel");
                    for (int i = 0; i < Math.min(arr.size(), p.length); i++) {
                        p[i] = arr.get(i).getAsInt();
                    }
                    cfg.saved.add(new SavedDesign(o.get("name").getAsString(), p));
                }
            }
            if (obj.has("panel") && obj.get("panel").isJsonArray()) {
                var arr = obj.getAsJsonArray("panel");
                for (int i = 0; i < Math.min(arr.size(), cfg.pixels.length); i++) {
                    cfg.pixels[i] = arr.get(i).getAsInt();
                }
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
            com.google.gson.JsonArray panel = new com.google.gson.JsonArray();
            for (int p : pixels) {
                panel.add(p);
            }
            obj.add("panel", panel);
            com.google.gson.JsonArray savedArr = new com.google.gson.JsonArray();
            for (SavedDesign s : saved) {
                JsonObject o = new JsonObject();
                o.addProperty("name", s.name());
                com.google.gson.JsonArray sp = new com.google.gson.JsonArray();
                for (int p : s.pixels()) {
                    sp.add(p);
                }
                o.add("panel", sp);
                savedArr.add(o);
            }
            obj.add("saved", savedArr);
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
