package com.killer560.hub.autojoinskyblock;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Auto Join Skyblock toggle - see {@link AutoJoinSkyblockFeature}. Ships disabled by
 *  default, same as every other new feature added going forward per killer560's standing instruction. */
public final class AutoJoinSkyblockConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-autojoinskyblock.json");

    private static AutoJoinSkyblockConfig instance;

    private boolean enabled = false;

    private AutoJoinSkyblockConfig() {
    }

    public static AutoJoinSkyblockConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new AutoJoinSkyblockConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AutoJoinSkyblockConfig cfg = new AutoJoinSkyblockConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new AutoJoinSkyblockConfig();
        }
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
