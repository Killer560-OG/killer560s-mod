package com.killer560.hub.revertmasterstars;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Revert Master Stars settings - see {@link RevertMasterStarsFeature}. Ships disabled by
 *  default. */
public final class RevertMasterStarsConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-revertmasterstars.json");

    private static RevertMasterStarsConfig instance;

    private boolean enabled = false;

    private RevertMasterStarsConfig() {
    }

    public static RevertMasterStarsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new RevertMasterStarsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            RevertMasterStarsConfig cfg = new RevertMasterStarsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new RevertMasterStarsConfig();
        }
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return enabled && com.killer560.hub.util.SkyblockGate.allows(); }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
