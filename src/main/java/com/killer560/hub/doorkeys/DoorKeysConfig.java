package com.killer560.hub.doorkeys;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Door Keys settings - see {@link DoorKeysFeature}'s class doc for the real noamm-ported
 *  highlight this is built on. Ships disabled by default, same as every other new feature in this mod. */
public final class DoorKeysConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-doorkeys.json");

    private static DoorKeysConfig instance;

    public static final float MIN_TRACER_THICKNESS = 1f;
    public static final float MAX_TRACER_THICKNESS = 10f;

    private boolean enabled = false;
    private boolean highlightWither = true;
    private boolean highlightBlood = true;
    private boolean showTracer = true;
    /** killer560 (2026-09-20): tracer thickness setting. Visual only, both builds. */
    private float tracerThickness = 2f;
    /** Cheat build only: draw the key box + tracer through walls. Ships OFF. */
    private boolean throughWalls = false;
    /**
     * How the pickup range is shown (killer560, 2026-10-15: "For door keys if it is on have a toggle for sphere or
     * radius. Radius is just a circle drawn at its height. Sphere would be a shaded sphere where if i go inside it then
     * I get the key."): {@link #RANGE_RADIUS} the flat circle, {@link #RANGE_SPHERE} a shaded sphere of the same radius
     * around the key.
     */
    private int rangeStyle = RANGE_RADIUS;
    public static final int RANGE_RADIUS = 0;
    public static final int RANGE_SPHERE = 1;
    public static final String[] RANGE_STYLE_NAMES = {"Radius", "Sphere"};

    public static final float MIN_CIRCLE_THICKNESS = 1f;
    public static final float MAX_CIRCLE_THICKNESS = 10f;
    /** How far the Radius circle is moved from its default height, in blocks (killer560, 2026-10-18: "The key's outline
     *  for the circle is too low"). The default height is the middle of the key stand's box. */
    public static final float MIN_CIRCLE_LIFT = -2f;
    public static final float MAX_CIRCLE_LIFT = 3f;
    /** Colour 0 means "the key's own colour" (near-black for a Wither Key, red for a Blood Key). */
    public static final int COLOR_AUTO = 0;
    private float circleThickness = 7f;
    private float circleLift = 0.0f;
    private int tracerColor = COLOR_AUTO;
    private int circleColor = COLOR_AUTO;
    private int fillColor = COLOR_AUTO;

    private DoorKeysConfig() {
    }

    public static DoorKeysConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Loads, then tells FeatureBits a gated setting may have changed (profile apply reloads every config). */
    public static void load() {
        load0();
        com.killer560.hub.util.FeatureBits.changed();
    }

    /** The stored switch alone (no Skyblock Only gate): a FeatureBits gate. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    private static void load0() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new DoorKeysConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            DoorKeysConfig cfg = new DoorKeysConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.highlightWither = ConfigJson.getBool(obj, "highlightWither", cfg.highlightWither);
            cfg.highlightBlood = ConfigJson.getBool(obj, "highlightBlood", cfg.highlightBlood);
            cfg.showTracer = ConfigJson.getBool(obj, "showTracer", cfg.showTracer);
            cfg.tracerThickness = Math.max(MIN_TRACER_THICKNESS, Math.min(MAX_TRACER_THICKNESS,
                    ConfigJson.getFloat(obj, "tracerThickness", cfg.tracerThickness)));
            cfg.throughWalls = ConfigJson.getBool(obj, "throughWalls", cfg.throughWalls);
            cfg.setRangeStyle(ConfigJson.getInt(obj, "rangeStyle", RANGE_RADIUS));
            cfg.setCircleThickness(ConfigJson.getFloat(obj, "circleThickness", cfg.circleThickness));
            cfg.setCircleLift(ConfigJson.getFloat(obj, "circleLift", cfg.circleLift));
            cfg.tracerColor = ConfigJson.getInt(obj, "tracerColor", COLOR_AUTO);
            cfg.circleColor = ConfigJson.getInt(obj, "circleColor", COLOR_AUTO);
            cfg.fillColor = ConfigJson.getInt(obj, "fillColor", COLOR_AUTO);
            instance = cfg;
        } catch (Exception e) {
            instance = new DoorKeysConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("highlightWither", highlightWither);
            obj.addProperty("highlightBlood", highlightBlood);
            obj.addProperty("showTracer", showTracer);
            obj.addProperty("tracerThickness", tracerThickness);
            obj.addProperty("throughWalls", throughWalls);
            obj.addProperty("rangeStyle", rangeStyle);
            obj.addProperty("circleThickness", circleThickness);
            obj.addProperty("circleLift", circleLift);
            obj.addProperty("tracerColor", tracerColor);
            obj.addProperty("circleColor", circleColor);
            obj.addProperty("fillColor", fillColor);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled; com.killer560.hub.util.FeatureBits.changed();
    }

    public boolean isHighlightWither() {
        return highlightWither;
    }

    public void setHighlightWither(boolean highlightWither) {
        this.highlightWither = highlightWither;
    }

    public boolean isHighlightBlood() {
        return highlightBlood;
    }

    public void setHighlightBlood(boolean highlightBlood) {
        this.highlightBlood = highlightBlood;
    }

    public boolean isShowTracer() {
        return showTracer;
    }

    public void setShowTracer(boolean showTracer) {
        this.showTracer = showTracer;
    }

    public float getTracerThickness() {
        return tracerThickness;
    }

    public void setTracerThickness(float thickness) {
        this.tracerThickness = Math.max(MIN_TRACER_THICKNESS, Math.min(MAX_TRACER_THICKNESS, thickness));
    }

    /** Cheat-gated: the legit jar can never draw the key through walls, whatever the saved value says. */
    public boolean isThroughWalls() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && throughWalls;
    }

    public boolean isThroughWallsRaw() {
        return throughWalls;
    }

    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }

    public int getRangeStyle() {
        return rangeStyle;
    }

    public void setRangeStyle(int v) {
        this.rangeStyle = v < 0 || v >= RANGE_STYLE_NAMES.length ? RANGE_RADIUS : v;
    }

    public float getCircleThickness() {
        return circleThickness;
    }

    public void setCircleThickness(float v) {
        circleThickness = Float.isNaN(v) ? 7f : Math.max(MIN_CIRCLE_THICKNESS, Math.min(MAX_CIRCLE_THICKNESS, v));
    }

    public float getCircleLift() {
        return circleLift;
    }

    public void setCircleLift(float v) {
        circleLift = Float.isNaN(v) ? 0.0f : Math.max(MIN_CIRCLE_LIFT, Math.min(MAX_CIRCLE_LIFT, v));
    }

    /** ARGB, or {@link #COLOR_AUTO}. */
    public int getTracerColor() {
        return tracerColor;
    }

    public void setTracerColor(int argb) {
        tracerColor = argb;
    }

    public int getCircleColor() {
        return circleColor;
    }

    public void setCircleColor(int argb) {
        circleColor = argb;
    }

    public int getFillColor() {
        return fillColor;
    }

    public void setFillColor(int argb) {
        fillColor = argb;
    }
}
