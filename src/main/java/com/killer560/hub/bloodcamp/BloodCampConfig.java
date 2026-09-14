package com.killer560.hub.bloodcamp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Blood Camp settings - see {@link BloodCampFeature}'s class doc for the real Noamm-ported
 *  mechanic this is built on. Ships disabled by default, same as every other new feature in this mod.
 *  <p>
 *  Scope note (2026-09-14): real per-color customization (box/line/timer colors, decimal places) that
 *  Noamm's own real reference exposes is deliberately NOT included here yet, to keep this first version
 *  manageable - fixed real defaults (green=ready, red=already broken, matching Noamm's own real choices)
 *  are used instead. Can be added later if killer560 wants it. */
public final class BloodCampConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-bloodcamp.json");

    private static BloodCampConfig instance;

    private boolean enabled = false;
    private boolean showOverlay = true;
    private boolean triggerBotEnabled = false;
    private boolean auraEnabled = false;
    private boolean autoDetectLag = true;
    private int manualTickOffset = 0;

    private BloodCampConfig() {
    }

    public static BloodCampConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BloodCampConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            BloodCampConfig cfg = new BloodCampConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.showOverlay = !obj.has("showOverlay") || obj.get("showOverlay").getAsBoolean();
            cfg.triggerBotEnabled = obj.has("triggerBotEnabled") && obj.get("triggerBotEnabled").getAsBoolean();
            cfg.auraEnabled = obj.has("auraEnabled") && obj.get("auraEnabled").getAsBoolean();
            cfg.autoDetectLag = !obj.has("autoDetectLag") || obj.get("autoDetectLag").getAsBoolean();
            cfg.setManualTickOffset(obj.has("manualTickOffset") ? obj.get("manualTickOffset").getAsInt() : 0);
            instance = cfg;
        } catch (Exception e) {
            instance = new BloodCampConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showOverlay", showOverlay);
            obj.addProperty("triggerBotEnabled", triggerBotEnabled);
            obj.addProperty("auraEnabled", auraEnabled);
            obj.addProperty("autoDetectLag", autoDetectLag);
            obj.addProperty("manualTickOffset", manualTickOffset);
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

    public boolean isShowOverlay() {
        return showOverlay;
    }

    public void setShowOverlay(boolean showOverlay) {
        this.showOverlay = showOverlay;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - a real click-assist
     *  macro, same pattern as Simon Says' Trigger Bot. */
    public boolean isTriggerBotEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && triggerBotEnabled;
    }

    public void setTriggerBotEnabled(boolean triggerBotEnabled) {
        this.triggerBotEnabled = triggerBotEnabled;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - real simulated camera
     *  rotation, a real macro. */
    public boolean isAuraEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && auraEnabled;
    }

    public void setAuraEnabled(boolean auraEnabled) {
        this.auraEnabled = auraEnabled;
    }

    public boolean isAutoDetectLag() {
        return autoDetectLag;
    }

    public void setAutoDetectLag(boolean autoDetectLag) {
        this.autoDetectLag = autoDetectLag;
    }

    public int getManualTickOffset() {
        return manualTickOffset;
    }

    public void setManualTickOffset(int manualTickOffset) {
        this.manualTickOffset = Math.max(-20, Math.min(20, manualTickOffset));
    }
}
