package com.killer560.hub.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Persisted positions for draggable HUD overlays, plus the keybind that opens the position editor. */
public final class HudConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-hud.json");

    private static HudConfig instance;

    private final Map<String, int[]> positions = new HashMap<>();
    private final Map<String, Float> scales = new HashMap<>();
    /** GLFW key code for opening the HUD editor, or -1 if unbound. */
    private int editKeyCode = -1;

    private HudConfig() {
    }

    public static HudConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        HudConfig cfg = new HudConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                cfg.editKeyCode = com.killer560.hub.util.KeyUtil.sanitize(ConfigJson.getInt(obj, "editKeyCode", -1));
                JsonObject positions = ConfigJson.getObject(obj, "positions");
                if (positions != null) {
                    for (String id : positions.keySet()) {
                        // One bad element is skipped on its own instead of dropping every HUD position.
                        JsonObject pos = ConfigJson.getObject(positions, id);
                        if (pos == null) {
                            continue;
                        }
                        if (pos.has("x") && pos.has("y")) {
                            int x = ConfigJson.getInt(pos, "x", Integer.MIN_VALUE);
                            int y = ConfigJson.getInt(pos, "y", Integer.MIN_VALUE);
                            if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE) {
                                cfg.positions.put(id, new int[]{x, y});
                            }
                        }
                        float scale = ConfigJson.getFloat(pos, "scale", Float.NaN);
                        if (!Float.isNaN(scale) && scale > 0f) {
                            cfg.scales.put(id, scale);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("editKeyCode", editKeyCode);
            JsonObject positions = new JsonObject();
            for (Map.Entry<String, int[]> entry : this.positions.entrySet()) {
                JsonObject pos = new JsonObject();
                pos.addProperty("x", entry.getValue()[0]);
                pos.addProperty("y", entry.getValue()[1]);
                pos.addProperty("scale", scales.getOrDefault(entry.getKey(), 1.0f));
                positions.add(entry.getKey(), pos);
            }
            // Bug fix (2026-09-15 persistence audit): an element resized with the scroll wheel in the HUD
            // editor but never dragged has a scale and no position - it used to be dropped here, so the
            // resize reset on restart. Write it scale-only; load() leaves its position on the default.
            for (Map.Entry<String, Float> entry : this.scales.entrySet()) {
                if (!this.positions.containsKey(entry.getKey())) {
                    JsonObject pos = new JsonObject();
                    pos.addProperty("scale", entry.getValue());
                    positions.add(entry.getKey(), pos);
                }
            }
            obj.add("positions", positions);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public int[] getPosition(String id, int defaultX, int defaultY) {
        return positions.getOrDefault(id, new int[]{defaultX, defaultY});
    }

    public void setPosition(String id, int x, int y) {
        positions.put(id, new int[]{x, y});
    }

    public float getScale(String id, float defaultScale) {
        return scales.getOrDefault(id, defaultScale);
    }

    public void setScale(String id, float scale) {
        scales.put(id, scale);
    }

    public int getEditKeyCode() {
        return editKeyCode;
    }

    public void setEditKeyCode(int editKeyCode) {
        this.editKeyCode = editKeyCode;
    }
}
