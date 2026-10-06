package com.killer560.hub.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Correction Alarm (Home tab, cheat builds): whether {@link ModSounds#playCorrectionAlarm()} sounds when the server
 * corrects his position under an automation, and how loud (0-100%, default 100%). The chat line is sent either way.
 */
public final class CorrectionAlarmConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-correctionalarm.json");

    private static CorrectionAlarmConfig instance;

    private boolean enabled = true;
    private float volume = 1.0f;

    private CorrectionAlarmConfig() {
    }

    public static CorrectionAlarmConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        CorrectionAlarmConfig cfg = new CorrectionAlarmConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
                cfg.setVolume(ConfigJson.getFloat(obj, "volume", 1.0f));
            } catch (Exception ignored) {
                cfg = new CorrectionAlarmConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("volume", volume);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    /** 0..1. */
    public float getVolume() {
        return volume;
    }

    public void setVolume(float v) {
        volume = Float.isNaN(v) ? 1.0f : Math.max(0f, Math.min(1f, v));
    }
}
