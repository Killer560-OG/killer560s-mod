package com.killer560.hub.mining.nucleus;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings for {@link NucleusRunProfitTracker}. Ships OFF like every other new Mining (WIP) feature. */
public final class NucleusRunProfitConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-nucleus-profit-settings.json");

    private static NucleusRunProfitConfig instance;

    /** ON by default since 2026-10-04 (killer560: "Make all profit trackers on by default"). */
    private boolean enabled = false;

    private NucleusRunProfitConfig() {
    }

    public static NucleusRunProfitConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new NucleusRunProfitConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            NucleusRunProfitConfig cfg = new NucleusRunProfitConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new NucleusRunProfitConfig();
        }
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
