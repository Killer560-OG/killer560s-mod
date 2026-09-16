package com.killer560.hub.lagdisplay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Lag Display settings ({@code killer560smod-lagdisplay.json}). Ships OFF; reads go through
 * {@link ConfigJson} per key, and every GUI change calls {@link #save()} at the call site.
 *
 * <p><b>Skyblock gate:</b> deliberately NOT applied here. Devonian gates its own {@code LagDisplay} on
 * {@code Location.stateInSkyblock}, but ping / FPS / CPS / "last tick was N ms ago" are plain client and
 * network readouts that are just as useful on p3sim, on a test server or in a lobby, and none of them
 * reads or reacts to anything Skyblock-specific. The drawn element is still covered by
 * {@code HudInGameRenderer}'s own {@code SkyblockGate.allows()} check, so with "Skyblock Only" on it
 * stops drawing outside Skyblock/p3sim anyway - the difference is only that the setting itself stays
 * honest about what the player turned on.
 */
public final class LagDisplayConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-lagdisplay");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-lagdisplay.json");

    /** Devonian's own slider range for the "server has not responded for this long" threshold
     *  ({@code misc/LagDisplay.kt}: {@code addSlider("thresh", 300.0, 50.0, 1000.0)}). */
    public static final int MIN_LAG_THRESHOLD_MS = 50;
    public static final int MAX_LAG_THRESHOLD_MS = 1000;

    private static LagDisplayConfig instance;

    private boolean enabled = false;
    private boolean showLag = true;
    private int lagThresholdMs = 300;
    private boolean showPing = true;
    private boolean showFps = true;
    private boolean showCps = false;
    private boolean colorByValue = true;

    private LagDisplayConfig() {
    }

    public static LagDisplayConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LagDisplayConfig cfg = new LagDisplayConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(root, "enabled", cfg.enabled);
                cfg.showLag = ConfigJson.getBool(root, "showLag", cfg.showLag);
                cfg.lagThresholdMs = clampThreshold(ConfigJson.getInt(root, "lagThresholdMs", cfg.lagThresholdMs));
                cfg.showPing = ConfigJson.getBool(root, "showPing", cfg.showPing);
                cfg.showFps = ConfigJson.getBool(root, "showFps", cfg.showFps);
                cfg.showCps = ConfigJson.getBool(root, "showCps", cfg.showCps);
                cfg.colorByValue = ConfigJson.getBool(root, "colorByValue", cfg.colorByValue);
            } catch (Exception e) {
                LOGGER.warn("[LagDisplay] Couldn't read {} - keeping the file, using defaults this session: {}",
                        CONFIG_PATH.getFileName(), e.toString());
                instance = new LagDisplayConfig();
                return;
            }
        }
        instance = cfg;
    }

    private static int clampThreshold(int v) {
        return Math.max(MIN_LAG_THRESHOLD_MS, Math.min(MAX_LAG_THRESHOLD_MS, v));
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("showLag", showLag);
            root.addProperty("lagThresholdMs", lagThresholdMs);
            root.addProperty("showPing", showPing);
            root.addProperty("showFps", showFps);
            root.addProperty("showCps", showCps);
            root.addProperty("colorByValue", colorByValue);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[LagDisplay] Couldn't save {}: {}", CONFIG_PATH.getFileName(), e.toString());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isShowLag() {
        return showLag;
    }

    public void setShowLag(boolean v) {
        showLag = v;
    }

    public int getLagThresholdMs() {
        return lagThresholdMs;
    }

    public void setLagThresholdMs(int v) {
        lagThresholdMs = clampThreshold(v);
    }

    public boolean isShowPing() {
        return showPing;
    }

    public void setShowPing(boolean v) {
        showPing = v;
    }

    public boolean isShowFps() {
        return showFps;
    }

    public void setShowFps(boolean v) {
        showFps = v;
    }

    public boolean isShowCps() {
        return showCps;
    }

    public void setShowCps(boolean v) {
        showCps = v;
    }

    public boolean isColorByValue() {
        return colorByValue;
    }

    public void setColorByValue(boolean v) {
        colorByValue = v;
    }
}
