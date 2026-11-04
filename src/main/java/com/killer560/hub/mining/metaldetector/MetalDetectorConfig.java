package com.killer560.hub.mining.metaldetector;

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

/** Metal Detector settings ({@code killer560smod-mining-metaldetector.json}). Ships OFF; every change saves at once. */
public final class MetalDetectorConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-mining-metaldetector");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-mining-metaldetector.json");

    public static final int DEFAULT_COLOR = 0xFF55FF55;
    public static final int DEFAULT_CANDIDATE_COLOR = 0xFFFFFF55;

    private static MetalDetectorConfig instance;

    private boolean enabled = false;
    private boolean showLine = true;
    private boolean showCandidates = true;
    private boolean chatUpdates = true;
    private int color = DEFAULT_COLOR;
    private int candidateColor = DEFAULT_CANDIDATE_COLOR;

    private MetalDetectorConfig() {
    }

    public static MetalDetectorConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        MetalDetectorConfig cfg = new MetalDetectorConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.showLine = ConfigJson.getBool(o, "showLine", cfg.showLine);
                cfg.showCandidates = ConfigJson.getBool(o, "showCandidates", cfg.showCandidates);
                cfg.chatUpdates = ConfigJson.getBool(o, "chatUpdates", cfg.chatUpdates);
                cfg.color = ConfigJson.getInt(o, "color", cfg.color);
                cfg.candidateColor = ConfigJson.getInt(o, "candidateColor", cfg.candidateColor);
            } catch (Exception e) {
                LOGGER.warn("[MetalDetector] Couldn't read {} - using defaults this session: {}",
                        CONFIG_PATH.getFileName(), e.toString());
                instance = new MetalDetectorConfig();
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
            o.addProperty("showLine", showLine);
            o.addProperty("showCandidates", showCandidates);
            o.addProperty("chatUpdates", chatUpdates);
            o.addProperty("color", color);
            o.addProperty("candidateColor", candidateColor);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[MetalDetector] Couldn't save {}: {}", CONFIG_PATH.getFileName(), e.toString());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public boolean isShowLine() {
        return showLine;
    }

    public void setShowLine(boolean v) {
        showLine = v;
    }

    public boolean isShowCandidates() {
        return showCandidates;
    }

    public void setShowCandidates(boolean v) {
        showCandidates = v;
    }

    public boolean isChatUpdates() {
        return chatUpdates;
    }

    public void setChatUpdates(boolean v) {
        chatUpdates = v;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int argb) {
        color = argb;
    }

    public int getCandidateColor() {
        return candidateColor;
    }

    public void setCandidateColor(int argb) {
        candidateColor = argb;
    }
}
