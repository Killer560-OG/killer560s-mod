package com.killer560.hub.itembrowser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Item Browser settings - see {@link ItemBrowserFeature}. Ships disabled by default. */
public final class ItemBrowserConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-itembrowser.json");

    private static ItemBrowserConfig instance;

    private boolean enabled = false;
    private int columns = 5;
    private int rows = 6;

    private ItemBrowserConfig() {
    }

    public static ItemBrowserConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ItemBrowserConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ItemBrowserConfig cfg = new ItemBrowserConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            // Through the clamping setters so a hand-edited out-of-range value can't break the panel layout.
            cfg.setColumns(ConfigJson.getInt(obj, "columns", 5));
            cfg.setRows(ConfigJson.getInt(obj, "rows", 6));
            instance = cfg;
        } catch (Exception e) {
            instance = new ItemBrowserConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("columns", columns);
            obj.addProperty("rows", rows);
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

    public int getColumns() {
        return columns;
    }

    public void setColumns(int columns) {
        this.columns = Math.max(3, Math.min(9, columns));
    }

    public int getRows() {
        return rows;
    }

    public void setRows(int rows) {
        this.rows = Math.max(3, Math.min(10, rows));
    }
}
