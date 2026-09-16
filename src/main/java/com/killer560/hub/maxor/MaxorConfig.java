package com.killer560.hub.maxor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Maxor's Crystals settings - {@code killer560smod-maxor.json}. Everything ships OFF. Per-key
 * {@link ConfigJson} readers, {@link #save()} on every GUI change, reloaded by
 * {@code ProfileManager.reloadAllConfigs()}. Feature getters AND {@link SkyblockGate#allows()}.
 * <p>
 * {@code bestPlaceMs} is state, not a setting: the personal best for "picked up -> placed", the equivalent of
 * NoammAddons' {@code PersonalBest.getPB("F7_crystal_placement")} (this repo has no PersonalBest store, so the
 * one value lives here and therefore survives a restart, per killer560's persistence rule).
 */
public final class MaxorConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-maxor.json");

    /** Cyan, matching NoammAddons' cyan ("&b") crystal spawn timer text. */
    public static final int DEFAULT_HIGHLIGHT_COLOR = 0xFF55FFFF;

    private static MaxorConfig instance;

    private boolean spawnTimer = false;
    private boolean placeTimer = false;
    private boolean placeAlert = false;
    private boolean activeCounter = false;
    private boolean highlight = false;
    private boolean highlightFilled = false;
    private int highlightColor = DEFAULT_HIGHLIGHT_COLOR;
    /** 0 = no personal best yet. */
    private long bestPlaceMs = 0L;

    private MaxorConfig() {
    }

    public static MaxorConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        MaxorConfig cfg = new MaxorConfig();
        JsonObject obj = null;
        if (Files.exists(CONFIG_PATH)) {
            try {
                obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                obj = null;
            }
        }
        if (obj != null) {
            cfg.spawnTimer = ConfigJson.getBool(obj, "spawnTimer", false);
            cfg.placeTimer = ConfigJson.getBool(obj, "placeTimer", false);
            cfg.placeAlert = ConfigJson.getBool(obj, "placeAlert", false);
            cfg.activeCounter = ConfigJson.getBool(obj, "activeCounter", false);
            cfg.highlight = ConfigJson.getBool(obj, "highlight", false);
            cfg.highlightFilled = ConfigJson.getBool(obj, "highlightFilled", false);
            cfg.highlightColor = ConfigJson.getInt(obj, "highlightColor", DEFAULT_HIGHLIGHT_COLOR);
            cfg.bestPlaceMs = Math.max(0L, ConfigJson.getLong(obj, "bestPlaceMs", 0L));
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("spawnTimer", spawnTimer);
            obj.addProperty("placeTimer", placeTimer);
            obj.addProperty("placeAlert", placeAlert);
            obj.addProperty("activeCounter", activeCounter);
            obj.addProperty("highlight", highlight);
            obj.addProperty("highlightFilled", highlightFilled);
            obj.addProperty("highlightColor", highlightColor);
            obj.addProperty("bestPlaceMs", bestPlaceMs);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isSpawnTimerEnabled() { return spawnTimer && SkyblockGate.allows(); }
    public boolean getSpawnTimerRaw() { return spawnTimer; }
    public void setSpawnTimer(boolean v) { spawnTimer = v; }

    public boolean isPlaceTimerEnabled() { return placeTimer && SkyblockGate.allows(); }
    public boolean getPlaceTimerRaw() { return placeTimer; }
    public void setPlaceTimer(boolean v) { placeTimer = v; }

    public boolean isPlaceAlertEnabled() { return placeAlert && SkyblockGate.allows(); }
    public boolean getPlaceAlertRaw() { return placeAlert; }
    public void setPlaceAlert(boolean v) { placeAlert = v; }

    public boolean isActiveCounterEnabled() { return activeCounter && SkyblockGate.allows(); }
    public boolean getActiveCounterRaw() { return activeCounter; }
    public void setActiveCounter(boolean v) { activeCounter = v; }

    public boolean isHighlightEnabled() { return highlight && SkyblockGate.allows(); }
    public boolean getHighlightRaw() { return highlight; }
    public void setHighlight(boolean v) { highlight = v; }

    public boolean isHighlightFilled() { return highlightFilled; }
    public void setHighlightFilled(boolean v) { highlightFilled = v; }


    public int getHighlightColor() { return highlightColor; }
    public void setHighlightColor(int v) { highlightColor = v; }

    public long getBestPlaceMs() { return bestPlaceMs; }

    public void setBestPlaceMs(long v) { bestPlaceMs = Math.max(0L, v); }
}
