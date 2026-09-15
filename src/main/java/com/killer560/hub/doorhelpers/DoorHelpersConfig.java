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
 * Persisted Door Helpers settings (cheat build only) - Auto Door Opener (QUOI {@code AutoDoorOpener.kt}) and Look At
 * Door. Both ship OFF. The effective {@code is...Enabled()} getters are gated on
 * {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} and {@link com.killer560.hub.util.SkyblockGate};
 * the tab reads the {@code ...Raw()} getters.
 */
public final class DoorHelpersConfig {

    public enum OpenerMode { AURA, TRIGGERBOT }

    public static final double RANGE_MIN = 2.0;
    public static final double RANGE_MAX = 6.0;
    public static final int RETRY_MIN_MS = 100;
    public static final int RETRY_MAX_MS = 2000;
    public static final int SPEED_MIN = 1;
    public static final int SPEED_MAX = 10;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-doorhelpers.json");

    private static DoorHelpersConfig instance;

    // Auto Door Opener - QUOI defaults: Triggerbot, Range 5.0, Retry delay 500ms, Swing off, In menus off.
    private boolean autoDoorEnabled = false;
    private OpenerMode autoDoorMode = OpenerMode.TRIGGERBOT;
    private double autoDoorRange = 5.0;
    private int autoDoorRetryDelayMs = 500;
    private boolean autoDoorSwing = false;
    private boolean autoDoorInMenus = false;

    // Look At Door
    private boolean lookAtDoorEnabled = false;
    private int lookAtDoorKey = -1;
    private boolean lookAtDoorOnKeyPickup = false;
    private int lookAtDoorSpeed = 5;

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
            cfg.autoDoorInMenus = ConfigJson.getBool(obj, "autoDoorInMenus", cfg.autoDoorInMenus);
            cfg.lookAtDoorEnabled = ConfigJson.getBool(obj, "lookAtDoorEnabled", cfg.lookAtDoorEnabled);
            cfg.lookAtDoorKey = ConfigJson.getInt(obj, "lookAtDoorKey", cfg.lookAtDoorKey);
            cfg.lookAtDoorOnKeyPickup = ConfigJson.getBool(obj, "lookAtDoorOnKeyPickup", cfg.lookAtDoorOnKeyPickup);
            cfg.setLookAtDoorSpeed(ConfigJson.getInt(obj, "lookAtDoorSpeed", cfg.lookAtDoorSpeed));
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
            obj.addProperty("autoDoorInMenus", autoDoorInMenus);
            obj.addProperty("lookAtDoorEnabled", lookAtDoorEnabled);
            obj.addProperty("lookAtDoorKey", lookAtDoorKey);
            obj.addProperty("lookAtDoorOnKeyPickup", lookAtDoorOnKeyPickup);
            obj.addProperty("lookAtDoorSpeed", lookAtDoorSpeed);
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

    public boolean isAutoDoorInMenus() {
        return autoDoorInMenus;
    }

    public void setAutoDoorInMenus(boolean autoDoorInMenus) {
        this.autoDoorInMenus = autoDoorInMenus;
    }

    // ---- Look At Door ----

    public boolean isLookAtDoorEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && lookAtDoorEnabled
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isLookAtDoorEnabledRaw() {
        return lookAtDoorEnabled;
    }

    public void setLookAtDoorEnabled(boolean lookAtDoorEnabled) {
        this.lookAtDoorEnabled = lookAtDoorEnabled;
    }

    public int getLookAtDoorKey() {
        return lookAtDoorKey;
    }

    public void setLookAtDoorKey(int lookAtDoorKey) {
        this.lookAtDoorKey = lookAtDoorKey;
    }

    public boolean isLookAtDoorOnKeyPickup() {
        return lookAtDoorOnKeyPickup;
    }

    public void setLookAtDoorOnKeyPickup(boolean lookAtDoorOnKeyPickup) {
        this.lookAtDoorOnKeyPickup = lookAtDoorOnKeyPickup;
    }

    public int getLookAtDoorSpeed() {
        return lookAtDoorSpeed;
    }

    public void setLookAtDoorSpeed(int speed) {
        this.lookAtDoorSpeed = Math.max(SPEED_MIN, Math.min(SPEED_MAX, speed));
    }
}
