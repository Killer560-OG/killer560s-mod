package com.killer560.hub.sessionrecorder;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** The Session Recorder's one setting (Misc, General): whether it records. Ships OFF. See {@link SessionRecorder}. */
public final class SessionRecorderConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-sessionrecorder.json");

    private static SessionRecorderConfig instance;

    private boolean enabled = false;

    private SessionRecorderConfig() {
    }

    public static SessionRecorderConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        SessionRecorderConfig cfg = new SessionRecorderConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean v) { enabled = v; }
}
