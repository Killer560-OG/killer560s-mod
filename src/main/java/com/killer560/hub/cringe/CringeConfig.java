package com.killer560.hub.cringe;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Cringe's master switch (killer560, 2026-10-13): off, {@code /cringe} and Leap Message's cringe line send nothing.
 *  On by default, so nothing changes until he flips it. The lines themselves stay in {@link CringeLines}' text file. */
public final class CringeConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-cringe.json");

    private static CringeConfig instance;

    private boolean enabled = true;

    private CringeConfig() {
    }

    public static CringeConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        CringeConfig cfg = new CringeConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
