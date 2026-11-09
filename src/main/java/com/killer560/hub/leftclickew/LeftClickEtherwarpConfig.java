package com.killer560.hub.leftclickew;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Persisted Left Click Etherwarp settings - see {@link LeftClickEtherwarp}. Off by default. */
public final class LeftClickEtherwarpConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-leftclickew.json");

    private static LeftClickEtherwarpConfig instance;

    private boolean enabled = false;

    private LeftClickEtherwarpConfig() {
    }

    public static LeftClickEtherwarpConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LeftClickEtherwarpConfig cfg = new LeftClickEtherwarpConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
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
