package com.killer560.hub.autoanvil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Auto Anvil settings ({@code killer560smod-autoanvil.json}). Off by default; cheat build only. */
public final class AutoAnvilConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-autoanvil.json");

    public static final int MIN_DELAY_MS = 50;
    public static final int MAX_DELAY_MS = 1000;

    private static AutoAnvilConfig instance;

    private boolean enabled = false;
    /** Random wait after the server has answered one click and before the next, low end. */
    private int minDelayMs = 150;
    /** ... and high end. */
    private int maxDelayMs = 300;
    /** Feed books this session made back in (two new IV into a V). */
    private boolean cascade = true;
    /** Close the anvil once nothing is left to combine, if anything was combined. */
    private boolean closeWhenDone = false;

    private AutoAnvilConfig() {
    }

    public static AutoAnvilConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AutoAnvilConfig cfg = new AutoAnvilConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.setMinDelayMs(ConfigJson.getInt(obj, "minDelayMs", cfg.minDelayMs));
                cfg.setMaxDelayMs(ConfigJson.getInt(obj, "maxDelayMs", cfg.maxDelayMs));
                cfg.cascade = ConfigJson.getBool(obj, "cascade", cfg.cascade);
                cfg.closeWhenDone = ConfigJson.getBool(obj, "closeWhenDone", cfg.closeWhenDone);
            } catch (Exception e) {
                cfg = new AutoAnvilConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("minDelayMs", minDelayMs);
            obj.addProperty("maxDelayMs", maxDelayMs);
            obj.addProperty("cascade", cascade);
            obj.addProperty("closeWhenDone", closeWhenDone);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Cheat build, the toggle, and the Skyblock Only gate. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMinDelayMs() {
        return minDelayMs;
    }

    public void setMinDelayMs(int v) {
        this.minDelayMs = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, v));
    }

    public int getMaxDelayMs() {
        return maxDelayMs;
    }

    public void setMaxDelayMs(int v) {
        this.maxDelayMs = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, v));
    }

    public boolean isCascade() {
        return cascade;
    }

    public void setCascade(boolean cascade) {
        this.cascade = cascade;
    }

    public boolean isCloseWhenDone() {
        return closeWhenDone;
    }

    public void setCloseWhenDone(boolean closeWhenDone) {
        this.closeWhenDone = closeWhenDone;
    }
}
