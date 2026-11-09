package com.killer560.hub.bridge;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Cross-Mod Bridge settings - see {@link BridgeFeature}. Everything defaults OFF, master and per-mod,
 * like every new feature until killer560 confirms it live. Same load/save shape as {@code InteropConfig};
 * registered in {@code ProfileManager.reloadAllConfigs} (see the staging notes' patch list).
 */
public final class BridgeConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-bridge.json");

    private static BridgeConfig instance;

    private boolean enabled = true;
    private boolean devonian = true;
    private boolean odin = true;

    private BridgeConfig() {
    }

    public static BridgeConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BridgeConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            BridgeConfig cfg = new BridgeConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
            cfg.devonian = ConfigJson.getBool(obj, "devonian", true);
            // "noammAddons" (the NoammAddons socket toggle) is no longer read: that socket was removed on 2026-11-01.
            cfg.odin = ConfigJson.getBool(obj, "odin", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new BridgeConfig();
        }
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("devonian", devonian);
        obj.addProperty("odin", odin);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDevonian() {
        return devonian;
    }

    public void setDevonian(boolean devonian) {
        this.devonian = devonian;
    }

    public boolean isOdin() {
        return odin;
    }

    public void setOdin(boolean odin) {
        this.odin = odin;
    }
}
