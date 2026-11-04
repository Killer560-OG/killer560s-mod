package com.killer560.hub.mining.ghosts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Ghost ESP settings ({@code killer560smod-mining-ghosts.json}). Cheat build only. Ships OFF. */
public final class GhostEspConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-mining-ghosts");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-mining-ghosts.json");

    public static final int DEFAULT_COLOR = 0xFF55FFFF;

    private static GhostEspConfig instance;

    private boolean enabled = false;
    private int color = DEFAULT_COLOR;
    private boolean throughWalls = true;

    private GhostEspConfig() {
    }

    public static GhostEspConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        GhostEspConfig cfg = new GhostEspConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.color = ConfigJson.getInt(o, "color", cfg.color);
                cfg.throughWalls = ConfigJson.getBool(o, "throughWalls", cfg.throughWalls);
            } catch (Exception e) {
                LOGGER.warn("[GhostEsp] Couldn't read {} - using defaults this session: {}",
                        CONFIG_PATH.getFileName(), e.toString());
                instance = new GhostEspConfig();
                return;
            }
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("color", color);
            o.addProperty("throughWalls", throughWalls);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[GhostEsp] Couldn't save {}: {}", CONFIG_PATH.getFileName(), e.toString());
        }
    }

    /** False in a legit jar whatever the file says. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public int getColor() {
        return color;
    }

    public void setColor(int argb) {
        color = argb;
    }

    public boolean isThroughWalls() {
        return throughWalls;
    }

    public void setThroughWalls(boolean v) {
        throughWalls = v;
    }
}
