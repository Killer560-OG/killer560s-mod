package com.killer560.hub.kickrejoin;

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
 * Kick Rejoin Timer settings ({@link KickRejoinFeature}): off by default like every new feature; the title and sound
 * when the wait is over are on, and the wait is 60 s (what his own logs show Hypixel enforcing, 2026-11-03).
 */
public final class KickRejoinConfig {

    public static final int COOLDOWN_MIN = 5;
    public static final int COOLDOWN_MAX = 300;
    public static final int COOLDOWN_DEFAULT = 60;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-kickrejoin.json");

    private static KickRejoinConfig instance;

    private boolean enabled = false;
    private boolean readyTitle = true;
    private boolean readySound = true;
    private int cooldownSeconds = COOLDOWN_DEFAULT;

    private KickRejoinConfig() {
    }

    public static KickRejoinConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        KickRejoinConfig cfg = new KickRejoinConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.readyTitle = ConfigJson.getBool(obj, "readyTitle", true);
                cfg.readySound = ConfigJson.getBool(obj, "readySound", true);
                cfg.setCooldownSeconds(obj.has("cooldownSeconds") ? obj.get("cooldownSeconds").getAsInt() : COOLDOWN_DEFAULT);
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
            obj.addProperty("readyTitle", readyTitle);
            obj.addProperty("readySound", readySound);
            obj.addProperty("cooldownSeconds", cooldownSeconds);
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

    public boolean isReadyTitle() {
        return readyTitle;
    }

    public void setReadyTitle(boolean value) {
        readyTitle = value;
    }

    public boolean isReadySound() {
        return readySound;
    }

    public void setReadySound(boolean value) {
        readySound = value;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(int value) {
        cooldownSeconds = Math.max(COOLDOWN_MIN, Math.min(COOLDOWN_MAX, value));
    }
}
