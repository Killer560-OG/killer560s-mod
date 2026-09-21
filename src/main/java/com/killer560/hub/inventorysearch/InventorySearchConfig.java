package com.killer560.hub.inventorysearch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Inventory Search settings - see {@link InventorySearchFeature}. Ships disabled by
 *  default. */
public final class InventorySearchConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-inventorysearch.json");

    public static final float MIN_BOX_SCALE = 0.5f;
    public static final float MAX_BOX_SCALE = 3.0f;

    private static InventorySearchConfig instance;

    private boolean enabled = false;
    private boolean searchLore = true;
    private boolean ignoreCase = true;
    private int highlightColor = 0xFFFF5555;
    /** Scale of the standalone floating search bar (not drawn at all when the Item Browser panel's own
     *  header already has a search box - see {@link InventorySearchFeature#render}) - killer560
     *  (2026-09-21): "make it so I can... adjust the scale of it". */
    private float boxScale = 1.0f;

    private InventorySearchConfig() {
    }

    public static InventorySearchConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new InventorySearchConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            InventorySearchConfig cfg = new InventorySearchConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.searchLore = ConfigJson.getBool(obj, "searchLore", true);
            cfg.ignoreCase = ConfigJson.getBool(obj, "ignoreCase", true);
            cfg.highlightColor = ConfigJson.getInt(obj, "highlightColor", 0xFFFF5555);
            cfg.setBoxScale(ConfigJson.getFloat(obj, "boxScale", 1.0f));
            instance = cfg;
        } catch (Exception e) {
            instance = new InventorySearchConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("searchLore", searchLore);
            obj.addProperty("ignoreCase", ignoreCase);
            obj.addProperty("highlightColor", highlightColor);
            obj.addProperty("boxScale", boxScale);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSearchLore() {
        return searchLore;
    }

    public void setSearchLore(boolean searchLore) {
        this.searchLore = searchLore;
    }

    public boolean isIgnoreCase() {
        return ignoreCase;
    }

    public void setIgnoreCase(boolean ignoreCase) {
        this.ignoreCase = ignoreCase;
    }

    public int getHighlightColor() {
        return highlightColor;
    }

    public void setHighlightColor(int highlightColor) {
        this.highlightColor = highlightColor;
    }

    public float getBoxScale() {
        return boxScale;
    }

    public void setBoxScale(float boxScale) {
        this.boxScale = Math.max(MIN_BOX_SCALE, Math.min(MAX_BOX_SCALE, boxScale));
    }
}
