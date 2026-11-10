package com.killer560.hub.darkmode;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Dark Mode settings: enabled (default off), opacity 1-80 % (default 25) and Tint HUD (default off). */
public final class DarkModeConfig {

    public static final int MIN_OPACITY = 1;
    public static final int MAX_OPACITY = 80;
    public static final int DEFAULT_OPACITY = 25;

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-darkmode.json");

    private static DarkModeConfig instance;

    private boolean enabled = false;
    private int opacity = DEFAULT_OPACITY;
    private boolean tintHud = true;

    private DarkModeConfig() {
    }

    public static DarkModeConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        DarkModeConfig cfg = new DarkModeConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.opacity = clampOpacity(ConfigJson.getInt(obj, "opacity", cfg.opacity));
                cfg.tintHud = ConfigJson.getBool(obj, "tintHud", cfg.tintHud);
            } catch (Exception ignored) {
                // unreadable file: keep defaults for this session
            }
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("opacity", opacity);
        obj.addProperty("tintHud", tintHud);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public static int clampOpacity(int v) {
        return Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, v));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getOpacity() {
        return opacity;
    }

    public void setOpacity(int opacity) {
        this.opacity = clampOpacity(opacity);
    }

    public boolean isTintHud() {
        return tintHud;
    }

    public void setTintHud(boolean tintHud) {
        this.tintHud = tintHud;
    }
}
