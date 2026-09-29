package com.killer560.hub.doorhelpers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Auto Door Opener settings (cheat build only; QUOI {@code AutoDoorOpener.kt}). Ships OFF. The effective
 * {@code is...Enabled()} getters are gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} and
 * {@link com.killer560.hub.util.SkyblockGate}; the tab reads the {@code ...Raw()} getters.
 * <p>
 * killer560, 2026-09-27: "Remove look at doors as a setting from door helpers. can you make it so triggerbot and
 * aura only click the door the second the key is grabbed for door helpers. And rename it to auto door opener." -
 * Look At Door (its own camera-turn feature) is gone entirely, along with its 4 settings below; the whole
 * former "Door Helpers" tab is just Auto Door Opener now. See {@link AutoDoorOpenerFeature} for the new
 * fire-on-key-pickup behaviour.
 */
public final class DoorHelpersConfig {

    public enum OpenerMode { AURA, TRIGGERBOT }

    public static final double RANGE_MIN = 2.0;
    /**
     * Measured block reach.
     *
     * <p>Was 6.0, inherited from QUOI. Only the DEFAULT had been moved to the measurement, so a config saved
     * at the old 5.0 - or anything up to 6.0 - loaded through this clamp untouched and kept reaching a block
     * and a half past what the server allows.
     */
    public static final double RANGE_MAX = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH;
    public static final int RETRY_MIN_MS = 100;
    public static final int RETRY_MAX_MS = 2000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-doorhelpers.json");

    private static DoorHelpersConfig instance;

    // Auto Door Opener - QUOI defaults: Triggerbot, Range 5.0, Retry delay 500ms, Swing off, In menus off.
    private boolean autoDoorEnabled = false;
    private OpenerMode autoDoorMode = OpenerMode.TRIGGERBOT;
    // Capped at the reach measured to be accepted and unflagged - see CheatUtilsConfig.MEASURED_MAX_REACH.
    private double autoDoorRange = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH;
    private int autoDoorRetryDelayMs = 500;
    private boolean autoDoorSwing = false;

    private DoorHelpersConfig() {
    }

    public static DoorHelpersConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new DoorHelpersConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            DoorHelpersConfig cfg = new DoorHelpersConfig();
            cfg.autoDoorEnabled = ConfigJson.getBool(obj, "autoDoorEnabled", cfg.autoDoorEnabled);
            cfg.autoDoorMode = ConfigJson.getEnum(obj, "autoDoorMode", OpenerMode.class, cfg.autoDoorMode);
            cfg.setAutoDoorRange(ConfigJson.getDouble(obj, "autoDoorRange", cfg.autoDoorRange));
            cfg.setAutoDoorRetryDelayMs(ConfigJson.getInt(obj, "autoDoorRetryDelayMs", cfg.autoDoorRetryDelayMs));
            cfg.autoDoorSwing = ConfigJson.getBool(obj, "autoDoorSwing", cfg.autoDoorSwing);
            instance = cfg;
        } catch (Exception e) {
            instance = new DoorHelpersConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("autoDoorEnabled", autoDoorEnabled);
            obj.addProperty("autoDoorMode", autoDoorMode.name());
            obj.addProperty("autoDoorRange", autoDoorRange);
            obj.addProperty("autoDoorRetryDelayMs", autoDoorRetryDelayMs);
            obj.addProperty("autoDoorSwing", autoDoorSwing);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ---- Auto Door Opener ----

    public boolean isAutoDoorEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoDoorEnabled
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isAutoDoorEnabledRaw() {
        return autoDoorEnabled;
    }

    public void setAutoDoorEnabled(boolean autoDoorEnabled) {
        this.autoDoorEnabled = autoDoorEnabled;
    }

    public OpenerMode getAutoDoorMode() {
        return autoDoorMode;
    }

    public void setAutoDoorMode(OpenerMode autoDoorMode) {
        this.autoDoorMode = autoDoorMode == null ? OpenerMode.TRIGGERBOT : autoDoorMode;
    }

    public double getAutoDoorRange() {
        return autoDoorRange;
    }

    public void setAutoDoorRange(double range) {
        double clamped = Math.max(RANGE_MIN, Math.min(RANGE_MAX, range));
        this.autoDoorRange = Math.round(clamped * 10.0) / 10.0;
    }

    public int getAutoDoorRetryDelayMs() {
        return autoDoorRetryDelayMs;
    }

    public void setAutoDoorRetryDelayMs(int ms) {
        int clamped = Math.max(RETRY_MIN_MS, Math.min(RETRY_MAX_MS, ms));
        this.autoDoorRetryDelayMs = Math.round(clamped / 50f) * 50;
    }

    public boolean isAutoDoorSwing() {
        return autoDoorSwing;
    }

    public void setAutoDoorSwing(boolean autoDoorSwing) {
        this.autoDoorSwing = autoDoorSwing;
    }
}
