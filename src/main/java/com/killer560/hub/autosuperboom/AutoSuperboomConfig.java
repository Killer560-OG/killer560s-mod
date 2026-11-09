package com.killer560.hub.autosuperboom;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/** Auto Superboom's one switch ({@code killer560smod-autosuperboom.json}), default OFF, cheat build only. */
public final class AutoSuperboomConfig {

    private static final Path PATH = ModPaths.config("killer560smod-autosuperboom.json");
    private static AutoSuperboomConfig instance;
    private boolean enabled = false;

    private AutoSuperboomConfig() {
    }

    public static AutoSuperboomConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AutoSuperboomConfig c = new AutoSuperboomConfig();
        JsonObject o = ConfigJson.read(PATH);
        if (o != null) {
            c.enabled = ConfigJson.getBool(o, "enabled", false);
        }
        instance = c;
        FeatureBits.changed();
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        ConfigJson.write(PATH, o);
    }

    /** The stored switch alone (no Skyblock Only gate): a FeatureBits gate. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public void setEnabled(boolean v) {
        enabled = v;
        FeatureBits.changed();
    }
}
