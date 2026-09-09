package com.killer560.hub.storageoverlay;

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

/** Persisted Storage Overlay settings - the main on/off toggle, dark/light background, and custom
 *  display names for individual tracked storage units (renamed per killer560's request). Scale and
 *  screen position are handled by the shared {@link com.killer560.hub.hud.HudConfig}/HUD editor like
 *  every other HUD element instead of being duplicated here. */
public final class StorageOverlayConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-storageoverlay.json");

    private static StorageOverlayConfig instance;

    private boolean enabled = true;
    private boolean darkMode = true;
    /** Storage key (see {@link StorageOverlayFeature#storageKey}) -> killer560's custom display name. */
    private final Map<String, String> customNames = new HashMap<>();
    /** Uniform scale for the WHOLE feature - both the grid and the relocated Inventory panel - per
     *  killer560's "add a scale bar... this should rescale everything while still keeping my inventory
     *  at the bottom and it centered at the top" request (2026-09-08): a single explicit control in the
     *  settings tab, rather than the grid's own separate (and apparently not discovered/used) HUD-editor
     *  scroll-to-resize. Scaling never moves either panel's own anchor point - only their size. */
    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 2.0f;
    private float scale = 1.0f;

    private StorageOverlayConfig() {
    }

    public static StorageOverlayConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new StorageOverlayConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            StorageOverlayConfig cfg = new StorageOverlayConfig();
            cfg.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
            cfg.darkMode = !obj.has("darkMode") || obj.get("darkMode").getAsBoolean();
            cfg.scale = obj.has("scale") ? clampScale(obj.get("scale").getAsFloat()) : 1.0f;
            if (obj.has("customNames")) {
                JsonObject names = obj.getAsJsonObject("customNames");
                for (String key : names.keySet()) {
                    cfg.customNames.put(key, names.get(key).getAsString());
                }
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new StorageOverlayConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("darkMode", darkMode);
            obj.addProperty("scale", scale);
            JsonObject names = new JsonObject();
            for (Map.Entry<String, String> entry : customNames.entrySet()) {
                names.addProperty(entry.getKey(), entry.getValue());
            }
            obj.add("customNames", names);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDarkMode() {
        return darkMode;
    }

    public void setDarkMode(boolean darkMode) {
        this.darkMode = darkMode;
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = clampScale(scale);
    }

    private static float clampScale(float value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    public String getCustomName(String storageKey) {
        return customNames.get(storageKey);
    }

    public void setCustomName(String storageKey, String name) {
        if (name == null || name.isBlank()) {
            customNames.remove(storageKey);
        } else {
            customNames.put(storageKey, name);
        }
    }

    public Map<String, String> getCustomNames() {
        return customNames;
    }
}
