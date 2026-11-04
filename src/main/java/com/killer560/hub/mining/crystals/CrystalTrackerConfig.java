package com.killer560.hub.mining.crystals;

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

/** Crystal Tracker settings ({@code killer560smod-mining-crystals.json}). Ships OFF. */
public final class CrystalTrackerConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-mining-crystals");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-mining-crystals.json");

    private static CrystalTrackerConfig instance;

    private boolean enabled = false;
    private boolean allFoundAlert = true;

    private CrystalTrackerConfig() {
    }

    public static CrystalTrackerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        CrystalTrackerConfig cfg = new CrystalTrackerConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.allFoundAlert = ConfigJson.getBool(o, "allFoundAlert", cfg.allFoundAlert);
            } catch (Exception e) {
                LOGGER.warn("[CrystalTracker] Couldn't read {} - using defaults this session: {}",
                        CONFIG_PATH.getFileName(), e.toString());
                instance = new CrystalTrackerConfig();
                return;
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("allFoundAlert", allFoundAlert);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[CrystalTracker] Couldn't save {}: {}", CONFIG_PATH.getFileName(), e.toString());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isAllFoundAlert() {
        return allFoundAlert;
    }

    public void setAllFoundAlert(boolean v) {
        allFoundAlert = v;
    }
}
