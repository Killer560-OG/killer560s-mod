package com.killer560.hub.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
                cfg.editKeyCode = obj.has("editKeyCode") ? obj.get("editKeyCode").getAsInt() : -1;
                if (obj.has("positions")) {
                    JsonObject positions = obj.getAsJsonObject("positions");
                    for (String id : positions.keySet()) {
                        JsonObject pos = positions.getAsJsonObject(id);
                        cfg.positions.put(id, new int[]{pos.get("x").getAsInt(), pos.get("y").getAsInt()});
                        if (pos.has("scale")) {
                            cfg.scales.put(id, pos.get("scale").getAsFloat());
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
