package com.killer560.hub.mining.scanner;

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

/** Structure Scanner settings ({@code killer560smod-mining-scanner.json}). Cheat build only. Ships OFF. */
public final class StructureScannerConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-mining-scanner");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-mining-scanner.json");

    private static StructureScannerConfig instance;

    private boolean enabled = false;
    private boolean waypoints = true;
    private boolean chatOnFind = true;
    private boolean showOnMap = false;
    private boolean grottos = true;

    private StructureScannerConfig() {
    }

    public static StructureScannerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        StructureScannerConfig cfg = new StructureScannerConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.waypoints = ConfigJson.getBool(o, "waypoints", cfg.waypoints);
                cfg.chatOnFind = ConfigJson.getBool(o, "chatOnFind", cfg.chatOnFind);
                cfg.showOnMap = ConfigJson.getBool(o, "showOnMap", cfg.showOnMap);
                cfg.grottos = ConfigJson.getBool(o, "grottos", cfg.grottos);
            } catch (Exception e) {
                LOGGER.warn("[StructureScanner] Couldn't read {} - using defaults this session: {}",
                        CONFIG_PATH.getFileName(), e.toString());
                instance = new StructureScannerConfig();
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
            o.addProperty("waypoints", waypoints);
            o.addProperty("chatOnFind", chatOnFind);
            o.addProperty("showOnMap", showOnMap);
            o.addProperty("grottos", grottos);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[StructureScanner] Couldn't save {}: {}", CONFIG_PATH.getFileName(), e.toString());
        }
    }

    /** False in a legit jar whatever the file says (javac folds the constant). */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public boolean isWaypoints() {
        return waypoints;
    }

    public void setWaypoints(boolean v) {
        waypoints = v;
    }

    public boolean isChatOnFind() {
        return chatOnFind;
    }

    public void setChatOnFind(boolean v) {
        chatOnFind = v;
    }

    public boolean isShowOnMap() {
        return showOnMap;
    }

    public void setShowOnMap(boolean v) {
        showOnMap = v;
    }

    public boolean isGrottos() {
        return grottos;
    }

    public void setGrottos(boolean v) {
        grottos = v;
    }
}
