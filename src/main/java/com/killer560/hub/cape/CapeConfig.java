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

    /** Built-in designs, in the order the cape button cycles through them. */
    public static final List<String> DESIGNS = List.of(
            "ember", "ocean", "forest", "royal", "midnight", "sunset", "monochrome", "gold");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-cape.json");

    private static CapeConfig instance;

    private String selected = NONE;

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
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public String getSelected() {
        return selected;
    }

    /** Anything that is not a built-in design becomes {@link #NONE}. */
    public void setSelected(String id) {
        this.selected = id != null && (id.equals(NONE) || DESIGNS.contains(id)) ? id : NONE;
    }

    public boolean hasCape() {
        return !NONE.equals(selected);
    }

    /** Cycles None, then each design, then back to None. */
    public void cycle(int dir) {
        int idx = selected.equals(NONE) ? -1 : DESIGNS.indexOf(selected);
        int size = DESIGNS.size() + 1;
        int next = ((idx + 1 + dir) % size + size) % size;
        selected = next == 0 ? NONE : DESIGNS.get(next - 1);
    }
}
