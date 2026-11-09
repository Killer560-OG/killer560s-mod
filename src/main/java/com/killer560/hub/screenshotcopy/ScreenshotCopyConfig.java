package com.killer560.hub.screenshotcopy;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Screenshot Copy toggle - see {@link ScreenshotCopyFeature}. Ships disabled by default,
 *  same as every other new feature added going forward per killer560's standing instruction. */
public final class ScreenshotCopyConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-screenshotcopy.json");

    private static ScreenshotCopyConfig instance;

    private boolean enabled = false;

    private ScreenshotCopyConfig() {
    }

    public static ScreenshotCopyConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ScreenshotCopyConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ScreenshotCopyConfig cfg = new ScreenshotCopyConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new ScreenshotCopyConfig();
        }
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
