package com.killer560.hub.smoothtp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Smooth Teleport settings - see {@link SmoothTeleport} for what the glide is and is not allowed to touch.
 *
 * <p>Off by default, like every new feature. The duration's default and range are Skyblocker 6.9.1's "Smooth AOTE"
 * ({@code maximumAddedLag}: 100 ms, slider 0-500); ours starts at 50 because a 0 ms glide is just the feature off.
 * The per-teleport toggles default ON so turning the master switch on is enough.
 */
public final class SmoothTeleportConfig {

    public static final int MIN_DURATION_MS = 50;
    public static final int MAX_DURATION_MS = 500;
    public static final int DEFAULT_DURATION_MS = 100;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-smoothteleport.json");

    private static SmoothTeleportConfig instance;

    private boolean enabled = false;
    private int durationMs = DEFAULT_DURATION_MS;
    private boolean etherwarp = true;
    private boolean instantTransmission = true;
    private boolean witherImpact = true;
    private boolean otherTeleports = true;

    private SmoothTeleportConfig() {
    }

    public static SmoothTeleportConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        SmoothTeleportConfig cfg = new SmoothTeleportConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.setDurationMs(ConfigJson.getInt(obj, "durationMs", cfg.durationMs));
                cfg.etherwarp = ConfigJson.getBool(obj, "etherwarp", cfg.etherwarp);
                cfg.instantTransmission = ConfigJson.getBool(obj, "instantTransmission", cfg.instantTransmission);
                cfg.witherImpact = ConfigJson.getBool(obj, "witherImpact", cfg.witherImpact);
                cfg.otherTeleports = ConfigJson.getBool(obj, "otherTeleports", cfg.otherTeleports);
            } catch (Exception e) {
                cfg = new SmoothTeleportConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("durationMs", durationMs);
            obj.addProperty("etherwarp", etherwarp);
            obj.addProperty("instantTransmission", instantTransmission);
            obj.addProperty("witherImpact", witherImpact);
            obj.addProperty("otherTeleports", otherTeleports);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The master switch as the game should obey it (Skyblock Only applies). */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** The saved master switch, for the settings screen. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(int ms) {
        this.durationMs = Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, ms));
    }

    public boolean isEtherwarp() {
        return etherwarp;
    }

    public void setEtherwarp(boolean on) {
        this.etherwarp = on;
    }

    public boolean isInstantTransmission() {
        return instantTransmission;
    }

    public void setInstantTransmission(boolean on) {
        this.instantTransmission = on;
    }

    public boolean isWitherImpact() {
        return witherImpact;
    }

    public void setWitherImpact(boolean on) {
        this.witherImpact = on;
    }

    public boolean isOtherTeleports() {
        return otherTeleports;
    }

    public void setOtherTeleports(boolean on) {
        this.otherTeleports = on;
    }
}
