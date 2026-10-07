package com.killer560.hub.witherdoors;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Wither Doors settings. killer560: "Also add wither door highlight. Legit should only
 * highlight the closest wither door, cheat should have an option to show every wither door/blood door.
 * Make them red and the closest one green once you get a key and a different color than the other ones."
 * <p>
 * The master toggle and the two Wither colors work in both builds - only "Show All Doors" (draw every
 * locked Wither/Blood door instead of just the closest Wither door) and "Through Walls" are cheat-only,
 * gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} the same way
 * {@code doorkeys.DoorKeysConfig.isThroughWalls()} gates its own ESP option. Ships fully OFF.
 */
public final class WitherDoorsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-witherdoors.json");

    public static final int MIN_RENDER_DISTANCE = 32;
    public static final int MAX_RENDER_DISTANCE = 256;

    public static final int DEFAULT_LOCKED_COLOR = 0xFFFF0000; // red - killer560: "make them red"
    public static final int DEFAULT_READY_COLOR = 0xFF00FF00;  // green - "the closest one green once you get a key"

    public static final int MIN_FILL_OPACITY = 5;
    public static final int MAX_FILL_OPACITY = 100;
    public static final int DEFAULT_FILL_OPACITY = 35;
    public static final int DEFAULT_FILL_COLOR = DEFAULT_LOCKED_COLOR;

    /** Outline (the original look), Fill, or both - killer560, 2026-10-07: "make it so wither doors also have a fill
     *  color option". Same three choices as Breaker Aura's Box Style. */
    public enum Style {
        OUTLINE("Outline"), FILL("Fill"), FILLED_OUTLINE("Filled Outline");

        public final String label;

        Style(String label) {
            this.label = label;
        }

        public Style next() {
            Style[] v = values();
            return v[(ordinal() + 1) % v.length];
        }

        public boolean fills() {
            return this != OUTLINE;
        }

        public boolean outlines() {
            return this != FILL;
        }
    }

    private static WitherDoorsConfig instance;

    private boolean enabled = false;
    private int witherLockedColor = DEFAULT_LOCKED_COLOR;
    private int witherReadyColor = DEFAULT_READY_COLOR;
    private int bloodLockedColor = DEFAULT_LOCKED_COLOR;
    private int bloodReadyColor = DEFAULT_READY_COLOR;
    private int renderDistance = 96;
    /** Ships OUTLINE, so nothing changes for anyone who never touches it. */
    private Style style = Style.OUTLINE;
    /** Percent, {@link #MIN_FILL_OPACITY}..{@link #MAX_FILL_OPACITY}. */
    private int fillOpacity = DEFAULT_FILL_OPACITY;
    /** false: the fill takes the door's current state colour (locked / key), so the two stay readable. */
    private boolean customFillColor = false;
    private int fillColor = DEFAULT_FILL_COLOR;
    /** Cheat build only: draw every locked Wither door and the Blood door, not just the closest Wither
     *  door. Ships OFF - the legit jar can never set this true regardless of a hand-edited config file. */
    private boolean showAllDoors = false;
    /** Cheat build only: draw the highlight(s) through walls. Ships OFF. */
    private boolean throughWalls = false;

    private WitherDoorsConfig() {
    }

    public static WitherDoorsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new WitherDoorsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            WitherDoorsConfig cfg = new WitherDoorsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.witherLockedColor = ConfigJson.getInt(obj, "witherLockedColor", cfg.witherLockedColor);
            cfg.witherReadyColor = ConfigJson.getInt(obj, "witherReadyColor", cfg.witherReadyColor);
            cfg.bloodLockedColor = ConfigJson.getInt(obj, "bloodLockedColor", cfg.bloodLockedColor);
            cfg.bloodReadyColor = ConfigJson.getInt(obj, "bloodReadyColor", cfg.bloodReadyColor);
            cfg.setRenderDistance(ConfigJson.getInt(obj, "renderDistance", cfg.renderDistance));
            cfg.setStyle(ConfigJson.getEnum(obj, "style", Style.class, cfg.style));
            cfg.setFillOpacity(ConfigJson.getInt(obj, "fillOpacity", cfg.fillOpacity));
            cfg.customFillColor = ConfigJson.getBool(obj, "customFillColor", cfg.customFillColor);
            cfg.fillColor = ConfigJson.getInt(obj, "fillColor", cfg.fillColor);
            cfg.showAllDoors = ConfigJson.getBool(obj, "showAllDoors", cfg.showAllDoors);
            cfg.throughWalls = ConfigJson.getBool(obj, "throughWalls", cfg.throughWalls);
            instance = cfg;
        } catch (Exception e) {
            instance = new WitherDoorsConfig();
        }
        WitherDoorsFeature.invalidateCache();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("witherLockedColor", witherLockedColor);
            obj.addProperty("witherReadyColor", witherReadyColor);
            obj.addProperty("bloodLockedColor", bloodLockedColor);
            obj.addProperty("bloodReadyColor", bloodReadyColor);
            obj.addProperty("renderDistance", renderDistance);
            obj.addProperty("style", style.name());
            obj.addProperty("fillOpacity", fillOpacity);
            obj.addProperty("customFillColor", customFillColor);
            obj.addProperty("fillColor", fillColor);
            obj.addProperty("showAllDoors", showAllDoors);
            obj.addProperty("throughWalls", throughWalls);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        WitherDoorsFeature.invalidateCache();
    }

    private static int clampDistance(int value) {
        return Math.max(MIN_RENDER_DISTANCE, Math.min(MAX_RENDER_DISTANCE, value));
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWitherLockedColor() {
        return witherLockedColor;
    }

    public void setWitherLockedColor(int witherLockedColor) {
        this.witherLockedColor = witherLockedColor;
    }

    public int getWitherReadyColor() {
        return witherReadyColor;
    }

    public void setWitherReadyColor(int witherReadyColor) {
        this.witherReadyColor = witherReadyColor;
    }

    public int getBloodLockedColor() {
        return bloodLockedColor;
    }

    public void setBloodLockedColor(int bloodLockedColor) {
        this.bloodLockedColor = bloodLockedColor;
    }

    public int getBloodReadyColor() {
        return bloodReadyColor;
    }

    public void setBloodReadyColor(int bloodReadyColor) {
        this.bloodReadyColor = bloodReadyColor;
    }

    public int getRenderDistance() {
        return renderDistance;
    }

    public void setRenderDistance(int renderDistance) {
        this.renderDistance = clampDistance(renderDistance);
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style == null ? Style.OUTLINE : style;
    }

    public int getFillOpacity() {
        return fillOpacity;
    }

    public void setFillOpacity(int fillOpacity) {
        this.fillOpacity = Math.max(MIN_FILL_OPACITY, Math.min(MAX_FILL_OPACITY, fillOpacity));
    }

    public boolean isCustomFillColor() {
        return customFillColor;
    }

    public void setCustomFillColor(boolean customFillColor) {
        this.customFillColor = customFillColor;
    }

    public int getFillColor() {
        return fillColor;
    }

    public void setFillColor(int fillColor) {
        this.fillColor = fillColor;
    }

    /** Cheat-gated: the legit jar can never show every door, whatever the saved value says. */
    public boolean isShowAllDoors() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && showAllDoors;
    }

    public boolean isShowAllDoorsRaw() {
        return showAllDoors;
    }

    public void setShowAllDoors(boolean showAllDoors) {
        this.showAllDoors = showAllDoors;
    }

    /** Cheat-gated: the legit jar can never draw through walls, whatever the saved value says. */
    public boolean isThroughWalls() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && throughWalls;
    }

    public boolean isThroughWallsRaw() {
        return throughWalls;
    }

    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }
}
