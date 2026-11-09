package com.killer560.hub.instaclearwarning;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/**
 * Insta Clear Warning settings ({@link InstaClearWarningFeature}): the switch (off), HUD Popup (on), Party Message (on)
 * with its text, and Requeue Same Floor (off).
 */
public final class InstaClearWarningConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-instaclearwarning.json");

    /** The owner's default line (to-do Doc 2026-11-01). */
    public static final String DEFAULT_MESSAGE = "Blood rush room was instacleared we must restart";
    /** Hypixel caps a chat line at 256 characters; "/pc " and some room are left. */
    public static final int MAX_MESSAGE = 200;

    private static InstaClearWarningConfig instance;

    private boolean enabled = false;
    private boolean hudPopup = true;
    private boolean partyMessage = true;
    private String message = DEFAULT_MESSAGE;
    private boolean requeue = false;

    private InstaClearWarningConfig() {
    }

    public static InstaClearWarningConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        InstaClearWarningConfig cfg = new InstaClearWarningConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.hudPopup = ConfigJson.getBool(obj, "hudPopup", true);
            cfg.partyMessage = ConfigJson.getBool(obj, "partyMessage", true);
            cfg.setMessage(ConfigJson.getString(obj, "message", DEFAULT_MESSAGE));
            cfg.requeue = ConfigJson.getBool(obj, "requeue", false);
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("hudPopup", hudPopup);
        obj.addProperty("partyMessage", partyMessage);
        obj.addProperty("message", message);
        obj.addProperty("requeue", requeue);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean isHudPopup() {
        return hudPopup;
    }

    public void setHudPopup(boolean value) {
        hudPopup = value;
    }

    public boolean isPartyMessage() {
        return partyMessage;
    }

    public void setPartyMessage(boolean value) {
        partyMessage = value;
    }

    public String getMessage() {
        return message;
    }

    /** Blank puts the default back; longer than {@link #MAX_MESSAGE} is cut. */
    public void setMessage(String value) {
        String v = value == null ? "" : value;
        message = v.isBlank() ? DEFAULT_MESSAGE : (v.length() > MAX_MESSAGE ? v.substring(0, MAX_MESSAGE) : v);
    }

    public boolean isRequeue() {
        return requeue;
    }

    public void setRequeue(boolean value) {
        requeue = value;
    }
}
