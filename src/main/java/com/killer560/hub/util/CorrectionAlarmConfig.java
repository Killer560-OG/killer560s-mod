package com.killer560.hub.util;

import com.google.gson.JsonObject;

import java.nio.file.Path;

/**
 * The Correction Alarm (Home tab, cheat builds): whether {@link ModSounds#playCorrectionAlarm()} sounds when the server
 * corrects his position under an automation, and how loud (0-100%, default 100%). The chat line is sent either way.
 */
public final class CorrectionAlarmConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-correctionalarm.json");

    private static volatile CorrectionAlarmConfig instance;

    // Read from the netty thread (ServerCorrections -> ModSounds), written from the menu.
    private volatile boolean enabled = true;
    private volatile float volume = 1.0f;

    private CorrectionAlarmConfig() {
    }

    public static CorrectionAlarmConfig getInstance() {
        CorrectionAlarmConfig cfg = instance;
        if (cfg == null) {
            synchronized (CorrectionAlarmConfig.class) {
                if (instance == null) {
                    load();
                }
                cfg = instance;
            }
        }
        return cfg;
    }

    public static void load() {
        CorrectionAlarmConfig cfg = new CorrectionAlarmConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
            cfg.setVolume(ConfigJson.getFloat(obj, "volume", 1.0f));
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("volume", volume);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean v) { enabled = v; }

    /** 0..1. */
    public float getVolume() { return volume; }

    public void setVolume(float v) { volume = Float.isNaN(v) ? 1.0f : Math.max(0f, Math.min(1f, v)); }
}
