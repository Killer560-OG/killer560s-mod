package com.killer560.hub.kickhub;

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
 * Kicked To Hub settings ({@link KickHubFeature}). The switch is off by default like every new feature; inside it the
 * alerts and the clickable warp-back line are on, and Auto Warp Back is off.
 */
public final class KickHubConfig {

    public static final int DELAY_MIN = 3;
    public static final int DELAY_MAX = 60;
    public static final int DELAY_DEFAULT = 5;
    public static final int WARN_MIN = 5;
    public static final int WARN_MAX = 300;
    public static final int WARN_DEFAULT = 60;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-kickhub.json");

    private static KickHubConfig instance;

    private boolean enabled = false;
    private boolean hubAlert = true;
    private boolean alertSound = true;
    private boolean warpBackLine = true;
    private boolean autoWarpBack = false;
    private int autoDelaySeconds = DELAY_DEFAULT;
    private boolean restartAlert = true;
    private boolean restartCountdown = true;
    private boolean rejoinTimer = true;
    private int rejoinWarnSeconds = WARN_DEFAULT;

    private KickHubConfig() {
    }

    public static KickHubConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        KickHubConfig cfg = new KickHubConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.hubAlert = ConfigJson.getBool(obj, "hubAlert", true);
                cfg.alertSound = ConfigJson.getBool(obj, "alertSound", true);
                cfg.warpBackLine = ConfigJson.getBool(obj, "warpBackLine", true);
                cfg.autoWarpBack = ConfigJson.getBool(obj, "autoWarpBack", false);
                cfg.setAutoDelaySeconds(obj.has("autoDelaySeconds") ? obj.get("autoDelaySeconds").getAsInt() : DELAY_DEFAULT);
                cfg.restartAlert = ConfigJson.getBool(obj, "restartAlert", true);
                cfg.restartCountdown = ConfigJson.getBool(obj, "restartCountdown", true);
                cfg.rejoinTimer = ConfigJson.getBool(obj, "rejoinTimer", true);
                cfg.setRejoinWarnSeconds(obj.has("rejoinWarnSeconds") ? obj.get("rejoinWarnSeconds").getAsInt() : WARN_DEFAULT);
            } catch (Exception ignored) {
                // unreadable file - defaults
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("hubAlert", hubAlert);
            obj.addProperty("alertSound", alertSound);
            obj.addProperty("warpBackLine", warpBackLine);
            obj.addProperty("autoWarpBack", autoWarpBack);
            obj.addProperty("autoDelaySeconds", autoDelaySeconds);
            obj.addProperty("restartAlert", restartAlert);
            obj.addProperty("restartCountdown", restartCountdown);
            obj.addProperty("rejoinTimer", rejoinTimer);
            obj.addProperty("rejoinWarnSeconds", rejoinWarnSeconds);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean isHubAlert() {
        return hubAlert;
    }

    public void setHubAlert(boolean value) {
        hubAlert = value;
    }

    public boolean isAlertSound() {
        return alertSound;
    }

    public void setAlertSound(boolean value) {
        alertSound = value;
    }

    public boolean isWarpBackLine() {
        return warpBackLine;
    }

    public void setWarpBackLine(boolean value) {
        warpBackLine = value;
    }

    public boolean isAutoWarpBack() {
        return autoWarpBack;
    }

    public void setAutoWarpBack(boolean value) {
        autoWarpBack = value;
    }

    public int getAutoDelaySeconds() {
        return autoDelaySeconds;
    }

    public void setAutoDelaySeconds(int value) {
        autoDelaySeconds = Math.max(DELAY_MIN, Math.min(DELAY_MAX, value));
    }

    public boolean isRestartAlert() {
        return restartAlert;
    }

    public void setRestartAlert(boolean value) {
        restartAlert = value;
    }

    public boolean isRestartCountdown() {
        return restartCountdown;
    }

    public void setRestartCountdown(boolean value) {
        restartCountdown = value;
    }

    public boolean isRejoinTimer() {
        return rejoinTimer;
    }

    public void setRejoinTimer(boolean value) {
        rejoinTimer = value;
    }

    public int getRejoinWarnSeconds() {
        return rejoinWarnSeconds;
    }

    public void setRejoinWarnSeconds(int value) {
        rejoinWarnSeconds = Math.max(WARN_MIN, Math.min(WARN_MAX, value));
    }
}
