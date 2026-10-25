package com.killer560.hub.zpew;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Zero Ping Etherwarp settings - see {@link ZeroPingEtherwarp}. Off by default. */
public final class ZeroPingEtherwarpConfig {

    public static final int MIN_TIMEOUT_MS = 250;
    public static final int MAX_TIMEOUT_MS = 3000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-zpew.json");

    private static ZeroPingEtherwarpConfig instance;

    private boolean enabled = false;
    /** How long a predicted landing waits for the server's teleport before the camera goes back. */
    private int timeoutMs = 1000;
    /** A warp sent while another is still pending is predicted from that one's landing (chained warps). */
    private boolean chain = true;
    /** The Etherwarp Overlay highlights from the predicted landing while a warp is pending. */
    private boolean overlayFromPrediction = true;

    private ZeroPingEtherwarpConfig() {
    }

    public static ZeroPingEtherwarpConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ZeroPingEtherwarpConfig cfg = new ZeroPingEtherwarpConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.setTimeoutMs(ConfigJson.getInt(obj, "timeoutMs", cfg.timeoutMs));
                cfg.chain = ConfigJson.getBool(obj, "chain", cfg.chain);
                cfg.overlayFromPrediction = ConfigJson.getBool(obj, "overlayFromPrediction", cfg.overlayFromPrediction);
            } catch (Exception e) {
                cfg = new ZeroPingEtherwarpConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("timeoutMs", timeoutMs);
            obj.addProperty("chain", chain);
            obj.addProperty("overlayFromPrediction", overlayFromPrediction);
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

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeoutMs));
    }

    public boolean isChain() {
        return chain;
    }

    public void setChain(boolean chain) {
        this.chain = chain;
    }

    public boolean isOverlayFromPrediction() {
        return overlayFromPrediction;
    }

    public void setOverlayFromPrediction(boolean overlayFromPrediction) {
        this.overlayFromPrediction = overlayFromPrediction;
    }
}
