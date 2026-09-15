package com.killer560.hub.arrowalign;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Arrow Align (F7/M7 P3 third device) settings - see {@link ArrowAlignFeature}. Every feature ships
 *  disabled. Legit getters: {@code raw && SkyblockGate.allows()}; cheat getters additionally require
 *  {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED}. Tab labels read the {@code ...Raw()} getters so a
 *  toggle never flips from a gated value. */
public final class ArrowAlignConfig {

    public static final float MIN_NUMBER_SCALE = 0.5f;
    public static final float MAX_NUMBER_SCALE = 3.0f;
    public static final int MAX_TRIGGER_BOT_DELAY_MS = 500;
    public static final int MAX_AURA_DELAY_MS = 1000;
    public static final double MIN_AURA_RANGE = 2.0;
    public static final double MAX_AURA_RANGE = 6.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-arrowalign.json");

    private static ArrowAlignConfig instance;

    // Legit
    private boolean solverEnabled = false;
    private boolean highlightFrames = false;
    private float numberScale = 1.0f;
    private boolean preventMisclicksEnabled = false;
    // Sneak lets a blocked click through (Odin/NoammAddons "sneak to disable"). On by default so Prevent Misclicks
    // can never fully lock a manual click out - it only matters once Prevent Misclicks itself is switched on.
    private boolean crouchOverride = true;
    private boolean solveTimeEnabled = false;

    // Cheat build
    private boolean triggerBotEnabled = false;
    private int triggerBotDelayMs = 200;
    private boolean auraEnabled = false;
    private int auraMinDelayMs = 150;
    private int auraMaxDelayMs = 200;
    private double auraRange = 5.0;

    private ArrowAlignConfig() {
    }

    public static ArrowAlignConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ArrowAlignConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ArrowAlignConfig cfg = new ArrowAlignConfig();
            cfg.solverEnabled = ConfigJson.getBool(obj, "solverEnabled", false);
            cfg.highlightFrames = ConfigJson.getBool(obj, "highlightFrames", false);
            cfg.setNumberScale(ConfigJson.getFloat(obj, "numberScale", 1.0f));
            cfg.preventMisclicksEnabled = ConfigJson.getBool(obj, "preventMisclicksEnabled", false);
            cfg.crouchOverride = ConfigJson.getBool(obj, "crouchOverride", true);
            cfg.solveTimeEnabled = ConfigJson.getBool(obj, "solveTimeEnabled", false);
            cfg.triggerBotEnabled = ConfigJson.getBool(obj, "triggerBotEnabled", false);
            cfg.setTriggerBotDelayMs(ConfigJson.getInt(obj, "triggerBotDelayMs", 200));
            cfg.auraEnabled = ConfigJson.getBool(obj, "auraEnabled", false);
            cfg.setAuraMinDelayMs(ConfigJson.getInt(obj, "auraMinDelayMs", 150));
            cfg.setAuraMaxDelayMs(ConfigJson.getInt(obj, "auraMaxDelayMs", 200));
            cfg.setAuraRange(ConfigJson.getDouble(obj, "auraRange", 5.0));
            instance = cfg;
        } catch (Exception e) {
            instance = new ArrowAlignConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("solverEnabled", solverEnabled);
            obj.addProperty("highlightFrames", highlightFrames);
            obj.addProperty("numberScale", numberScale);
            obj.addProperty("preventMisclicksEnabled", preventMisclicksEnabled);
            obj.addProperty("crouchOverride", crouchOverride);
            obj.addProperty("solveTimeEnabled", solveTimeEnabled);
            obj.addProperty("triggerBotEnabled", triggerBotEnabled);
            obj.addProperty("triggerBotDelayMs", triggerBotDelayMs);
            obj.addProperty("auraEnabled", auraEnabled);
            obj.addProperty("auraMinDelayMs", auraMinDelayMs);
            obj.addProperty("auraMaxDelayMs", auraMaxDelayMs);
            obj.addProperty("auraRange", auraRange);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ---- Solver (legit) ----

    public boolean isSolverEnabled() {
        return solverEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getSolverRaw() {
        return solverEnabled;
    }

    public void setSolverEnabled(boolean solverEnabled) {
        this.solverEnabled = solverEnabled;
    }

    public boolean isHighlightFrames() {
        return highlightFrames;
    }

    public void setHighlightFrames(boolean highlightFrames) {
        this.highlightFrames = highlightFrames;
    }

    public float getNumberScale() {
        return numberScale;
    }

    public void setNumberScale(float numberScale) {
        this.numberScale = Math.max(MIN_NUMBER_SCALE, Math.min(MAX_NUMBER_SCALE, numberScale));
    }

    // ---- Prevent Misclicks (legit - blocks a real click, never sends one) ----

    public boolean isPreventMisclicksEnabled() {
        return preventMisclicksEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getPreventMisclicksRaw() {
        return preventMisclicksEnabled;
    }

    public void setPreventMisclicksEnabled(boolean preventMisclicksEnabled) {
        this.preventMisclicksEnabled = preventMisclicksEnabled;
    }

    public boolean isCrouchOverride() {
        return crouchOverride;
    }

    public void setCrouchOverride(boolean crouchOverride) {
        this.crouchOverride = crouchOverride;
    }

    // ---- Solve time (legit) ----

    public boolean isSolveTimeEnabled() {
        return solveTimeEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getSolveTimeRaw() {
        return solveTimeEnabled;
    }

    public void setSolveTimeEnabled(boolean solveTimeEnabled) {
        this.solveTimeEnabled = solveTimeEnabled;
    }

    // ---- Trigger Bot (cheat) ----

    public boolean isTriggerBotEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && triggerBotEnabled
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getTriggerBotRaw() {
        return triggerBotEnabled;
    }

    public void setTriggerBotEnabled(boolean triggerBotEnabled) {
        this.triggerBotEnabled = triggerBotEnabled;
    }

    public int getTriggerBotDelayMs() {
        return triggerBotDelayMs;
    }

    public void setTriggerBotDelayMs(int ms) {
        this.triggerBotDelayMs = Math.max(0, Math.min(MAX_TRIGGER_BOT_DELAY_MS, ms));
    }

    // ---- Aura (cheat) ----

    public boolean isAuraEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && auraEnabled
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getAuraRaw() {
        return auraEnabled;
    }

    public void setAuraEnabled(boolean auraEnabled) {
        this.auraEnabled = auraEnabled;
    }

    public int getAuraMinDelayMs() {
        return auraMinDelayMs;
    }

    /** Pushes Max up if Min passes it, so min <= max always holds. */
    public void setAuraMinDelayMs(int ms) {
        this.auraMinDelayMs = Math.max(0, Math.min(MAX_AURA_DELAY_MS, ms));
        if (auraMaxDelayMs < auraMinDelayMs) {
            auraMaxDelayMs = auraMinDelayMs;
        }
    }

    public int getAuraMaxDelayMs() {
        return auraMaxDelayMs;
    }

    /** Pulls Min down if Max drops below it, so min <= max always holds. */
    public void setAuraMaxDelayMs(int ms) {
        this.auraMaxDelayMs = Math.max(0, Math.min(MAX_AURA_DELAY_MS, ms));
        if (auraMinDelayMs > auraMaxDelayMs) {
            auraMinDelayMs = auraMaxDelayMs;
        }
    }

    public double getAuraRange() {
        return auraRange;
    }

    public void setAuraRange(double range) {
        double clamped = Math.max(MIN_AURA_RANGE, Math.min(MAX_AURA_RANGE, range));
        this.auraRange = Math.round(clamped * 10.0) / 10.0;
    }
}
