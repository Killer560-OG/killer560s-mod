package com.killer560.hub.milestone;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/** Milestone Display settings ({@code killer560smod-milestone.json}); ships OFF. */
public final class MilestoneConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-milestone.json");

    private static MilestoneConfig instance;

    private boolean enabled = false;

    private MilestoneConfig() {
    }

    public static MilestoneConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        MilestoneConfig cfg = new MilestoneConfig();
        JsonObject o = ConfigJson.read(CONFIG_PATH);
        if (o != null) {
            cfg.enabled = ConfigJson.getBool(o, "enabled", false);
        }
        instance = cfg;
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        ConfigJson.write(CONFIG_PATH, o);
    }

    public boolean isEnabled() { return enabled && SkyblockGate.allows(); }
    public boolean isEnabledRaw() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
}
