package com.killer560.hub.zoom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Zoom settings (killer560, 2026-10-19: "a Zoom module just like OptiFine Zoom"). Legit: it only narrows the view. */
public final class ZoomConfig {

    public static final float MIN_LEVEL = 1.5f;
    public static final float MAX_LEVEL = 30f;
    /** GLFW's C key, OptiFine's default zoom key. */
    private static final int DEFAULT_KEY = 67;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-zoom.json");

    private static ZoomConfig instance;

    private boolean enabled = false;
    private int keyCode = DEFAULT_KEY;
    private float zoomLevel = 4f;
    private boolean scrollWhileZoomed = true;
    /** Smooth pans in and out over a fraction of a second; off snaps straight to the zoom level (killer560, 2026-10-19). */
    private boolean smooth = true;

    private ZoomConfig() {
    }

    public static ZoomConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ZoomConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            ZoomConfig cfg = new ZoomConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.keyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "keyCode", DEFAULT_KEY));
            cfg.setZoomLevel(ConfigJson.getFloat(obj, "zoomLevel", 4f));
            cfg.scrollWhileZoomed = ConfigJson.getBool(obj, "scrollWhileZoomed", true);
            cfg.smooth = ConfigJson.getBool(obj, "smooth", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new ZoomConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("keyCode", keyCode);
            obj.addProperty("zoomLevel", zoomLevel);
            obj.addProperty("scrollWhileZoomed", scrollWhileZoomed);
            obj.addProperty("smooth", smooth);
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

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = KeyUtil.sanitizeBind(keyCode);
    }

    public float getZoomLevel() {
        return zoomLevel;
    }

    public void setZoomLevel(float zoomLevel) {
        this.zoomLevel = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, zoomLevel));
    }

    public boolean isScrollWhileZoomed() {
        return scrollWhileZoomed;
    }

    public void setScrollWhileZoomed(boolean scrollWhileZoomed) {
        this.scrollWhileZoomed = scrollWhileZoomed;
    }

    public boolean isSmooth() {
        return smooth;
    }

    public void setSmooth(boolean smooth) {
        this.smooth = smooth;
    }
}
