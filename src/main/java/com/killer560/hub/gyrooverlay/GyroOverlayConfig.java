package com.killer560.hub.gyrooverlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Gyrokinetic Wand Overlay settings ({@link GyroOverlayFeature}): the switch (off), Style, Color, Line Width. */
public final class GyroOverlayConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-gyrooverlay.json");

    /** Skyblocker's default gyro overlay color: a half see-through purple (0x7F761594). */
    public static final int DEFAULT_COLOR = 0x7F761594;

    /** Skyblocker's three shapes. */
    public enum Style {
        CIRCLE_OUTLINE("Circle Outline"),
        CIRCLE("Circle"),
        SPHERE("Sphere");

        public final String label;

        Style(String label) {
            this.label = label;
        }
    }

    private static GyroOverlayConfig instance;

    private boolean enabled = false;
    private Style style = Style.CIRCLE_OUTLINE;
    private int color = DEFAULT_COLOR;
    private float lineWidth = 3.0f;

    private GyroOverlayConfig() {
    }

    public static GyroOverlayConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        GyroOverlayConfig cfg = new GyroOverlayConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.style = ConfigJson.getEnum(obj, "style", Style.class, Style.CIRCLE_OUTLINE);
            cfg.color = ConfigJson.getInt(obj, "color", DEFAULT_COLOR);
            cfg.setLineWidth(ConfigJson.getFloat(obj, "lineWidth", 3.0f));
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("style", style.name());
            obj.addProperty("color", color);
            obj.addProperty("lineWidth", lineWidth);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style value) {
        style = value == null ? Style.CIRCLE_OUTLINE : value;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int value) {
        color = value;
    }

    public float getLineWidth() {
        return lineWidth;
    }

    public void setLineWidth(float value) {
        lineWidth = Math.max(1.0f, Math.min(10.0f, value));
    }
}
