package com.killer560.hub.quiver;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Quiver Display settings - see {@link QuiverDisplayFeature}'s class doc for the real
 *  Odin-ported lore-reading this is built on. Ships disabled by default, same as every other new
 *  feature in this mod. */
public final class QuiverDisplayConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-quiverdisplay.json");

    private static QuiverDisplayConfig instance;

    private boolean enabled = false;
    private boolean showName = true;
    /** Colour of the whole HUD line (killer560, 2026-10-11: "add an option to change the text color"). */
    private int textColor = DEFAULT_TEXT_COLOR;
    /** Low-arrow alert: a title plus a sound once the count drops below {@link #alertThreshold}. */
    private boolean alertEnabled = false;
    private boolean alertSound = true;
    private int alertThreshold = 60;
    private String alertText = DEFAULT_ALERT_TEXT;

    public static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;
    public static final String DEFAULT_ALERT_TEXT = "&cLow on arrows! &f{count} left";
    /** The threshold slider: 20 to 200 in steps of 20 (killer560's spec). */
    public static final int MIN_THRESHOLD = 20;
    public static final int MAX_THRESHOLD = 200;
    public static final int THRESHOLD_STEP = 20;

    private QuiverDisplayConfig() {
    }

    public static QuiverDisplayConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new QuiverDisplayConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            QuiverDisplayConfig cfg = new QuiverDisplayConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showName = ConfigJson.getBool(obj, "showName", true);
            if (obj.has("textColor")) {
                cfg.textColor = obj.get("textColor").getAsInt();
            }
            cfg.alertEnabled = ConfigJson.getBool(obj, "alertEnabled", false);
            cfg.alertSound = ConfigJson.getBool(obj, "alertSound", true);
            if (obj.has("alertThreshold")) {
                cfg.setAlertThreshold(obj.get("alertThreshold").getAsInt());
            }
            if (obj.has("alertText")) {
                cfg.alertText = obj.get("alertText").getAsString();
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new QuiverDisplayConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showName", showName);
            obj.addProperty("textColor", textColor);
            obj.addProperty("alertEnabled", alertEnabled);
            obj.addProperty("alertSound", alertSound);
            obj.addProperty("alertThreshold", alertThreshold);
            obj.addProperty("alertText", alertText);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowName() {
        return showName;
    }

    public void setShowName(boolean showName) {
        this.showName = showName;
    }

    public int getTextColor() {
        return textColor;
    }

    public void setTextColor(int textColor) {
        this.textColor = textColor;
    }

    public boolean isAlertEnabled() {
        return alertEnabled;
    }

    public void setAlertEnabled(boolean alertEnabled) {
        this.alertEnabled = alertEnabled;
    }

    public boolean isAlertSound() {
        return alertSound;
    }

    public void setAlertSound(boolean alertSound) {
        this.alertSound = alertSound;
    }

    public int getAlertThreshold() {
        return alertThreshold;
    }

    /** Snapped to the slider's 20-step grid and clamped to 20..200. */
    public void setAlertThreshold(int threshold) {
        int snapped = Math.round(threshold / (float) THRESHOLD_STEP) * THRESHOLD_STEP;
        this.alertThreshold = Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, snapped));
    }

    public String getAlertText() {
        return alertText;
    }

    public void setAlertText(String alertText) {
        this.alertText = alertText == null ? "" : alertText;
    }
}
