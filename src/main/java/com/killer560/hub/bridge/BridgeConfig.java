package com.killer560.hub.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Cross-Mod Bridge settings - see {@link BridgeFeature}. Everything defaults OFF, master and per-mod,
 * like every new feature until killer560 confirms it live. Same load/save shape as {@code InteropConfig};
 * registered in {@code ProfileManager.reloadAllConfigs} (see the staging notes' patch list).
 */
public final class BridgeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-bridge.json");

    private static BridgeConfig instance;

    private boolean enabled = false;
    private boolean devonian = false;
    private boolean noamm = false;
    private boolean odin = false;

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
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.devonian = ConfigJson.getBool(obj, "devonian", false);
            cfg.noamm = ConfigJson.getBool(obj, "noammAddons", false);
            cfg.odin = ConfigJson.getBool(obj, "odin", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new BridgeConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("devonian", devonian);
            obj.addProperty("noammAddons", noamm);
            obj.addProperty("odin", odin);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
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

    public boolean isNoamm() {
        return noamm;
    }

    public void setNoamm(boolean noamm) {
        this.noamm = noamm;
    }

    public boolean isOdin() {
        return odin;
    }

    public void setOdin(boolean odin) {
        this.odin = odin;
    }
}
