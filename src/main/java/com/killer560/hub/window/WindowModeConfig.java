package com.killer560.hub.window;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted borderless-fullscreen toggle, plus the last known normal windowed bounds so they can
 *  be restored when the mod switches back out of borderless (Minecraft's own {@code Window} class
 *  only tracks its "windowed" bounds internally for its own real-fullscreen toggle - it has no
 *  getters for them, so this mod keeps its own copy). */
public final class WindowModeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-window.json");

    private static WindowModeConfig instance;

    private boolean borderlessFullscreenEnabled = false;
    private int savedWindowedX;
    private int savedWindowedY;
    private int savedWindowedWidth = 854;
    private int savedWindowedHeight = 480;
    private boolean hasSavedWindowedBounds = false;

    private WindowModeConfig() {
    }

    public static WindowModeConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new WindowModeConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            WindowModeConfig cfg = new WindowModeConfig();
            cfg.borderlessFullscreenEnabled = ConfigJson.getBool(obj, "borderlessFullscreenEnabled", false);
            cfg.hasSavedWindowedBounds = ConfigJson.getBool(obj, "hasSavedWindowedBounds", false);
            cfg.savedWindowedX = ConfigJson.getInt(obj, "savedWindowedX", 0);
            cfg.savedWindowedY = ConfigJson.getInt(obj, "savedWindowedY", 0);
            cfg.savedWindowedWidth = ConfigJson.getInt(obj, "savedWindowedWidth", 854);
            cfg.savedWindowedHeight = ConfigJson.getInt(obj, "savedWindowedHeight", 480);
            // A zero/negative size would hand GLFW an invalid window when leaving borderless.
            if (cfg.savedWindowedWidth <= 0 || cfg.savedWindowedHeight <= 0) {
                cfg.savedWindowedWidth = 854;
                cfg.savedWindowedHeight = 480;
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new WindowModeConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("borderlessFullscreenEnabled", borderlessFullscreenEnabled);
            obj.addProperty("hasSavedWindowedBounds", hasSavedWindowedBounds);
            obj.addProperty("savedWindowedX", savedWindowedX);
            obj.addProperty("savedWindowedY", savedWindowedY);
            obj.addProperty("savedWindowedWidth", savedWindowedWidth);
            obj.addProperty("savedWindowedHeight", savedWindowedHeight);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isBorderlessFullscreenEnabled() {
        return borderlessFullscreenEnabled;
    }

    public void setBorderlessFullscreenEnabled(boolean enabled) {
        this.borderlessFullscreenEnabled = enabled;
    }

    public boolean hasSavedWindowedBounds() {
        return hasSavedWindowedBounds;
    }

    public void saveWindowedBounds(int x, int y, int width, int height) {
        this.savedWindowedX = x;
        this.savedWindowedY = y;
        this.savedWindowedWidth = width;
        this.savedWindowedHeight = height;
        this.hasSavedWindowedBounds = true;
    }

    public int getSavedWindowedX() {
        return savedWindowedX;
    }

    public int getSavedWindowedY() {
        return savedWindowedY;
    }

    public int getSavedWindowedWidth() {
        return savedWindowedWidth;
    }

    public int getSavedWindowedHeight() {
        return savedWindowedHeight;
    }
}
