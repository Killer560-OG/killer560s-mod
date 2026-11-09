package com.killer560.hub.fullbright;

import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.ConfigJson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Fullbright toggle - forces the world to render at maximum brightness regardless of
 *  actual block/sky light or the vanilla gamma/brightness slider. */
public final class FullbrightConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-fullbright.json");

    private static FullbrightConfig instance;

    private boolean enabled = false;

    private FullbrightConfig() {
    }

    public static FullbrightConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new FullbrightConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            FullbrightConfig cfg = new FullbrightConfig();
            cfg.enabled = com.killer560.hub.util.ConfigJson.getBool(obj, "enabled", cfg.enabled);
            instance = cfg;
        } catch (Exception e) {
            instance = new FullbrightConfig();
        }
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
