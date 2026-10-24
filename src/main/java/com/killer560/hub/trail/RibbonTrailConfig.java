package com.killer560.hub.trail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings for the Ribbon Trail cosmetic (killer560, 2026-10-14: "make a trail setting under cosmetics now instead of its
 * own toggle for the ribbon ... The trail though for ribbon can be shared"). Until then the ribbon was a Style of the
 * Trail tab; it is now its own Cosmetics tab and its own file, and the squares {@link TrailConfig} is local only.
 * <p>
 * A vertical band behind the player like a Roblox trail: {@link #getRibbonHeight()} is how tall it stands (the old
 * Ribbon Width, the relay's {@code width}), {@link #getRibbonSeconds()} how long a point lasts. Old saved configs are
 * carried over by {@link TrailConfig#load} ({@link #migrateFrom}).
 */
public final class RibbonTrailConfig {

    public static final float MIN_RIBBON_HEIGHT = 0.1f;
    public static final float MAX_RIBBON_HEIGHT = 2.0f;
    public static final float MIN_RIBBON_SECONDS = 0.2f;
    public static final float MAX_RIBBON_SECONDS = 4.0f;
    public static final int MIN_OPACITY_PERCENT = 5;
    public static final int MAX_OPACITY_PERCENT = 100;
    public static final int DEFAULT_HEAD_COLOR = 0xFFFF2A1A;
    public static final int DEFAULT_MID_COLOR = 0xFFFF8C1A;
    public static final int DEFAULT_TAIL_COLOR = 0xFFFFE01A;

    /** Where the ribbon's colors come from. */
    public enum ColorMode {
        /** The ribbon's own Head / (Middle) / Tail colors. */
        GRADIENT("Gradient"),
        /** The Name Changer's own-name color and its fade target (falls back to Gradient when no color is set). */
        NAME_FADE("Name Fade"),
        /** A rainbow that flows from the head toward the tail ({@link com.killer560.hub.util.ChromaWave}). */
        CHROMA("Chroma");

        private final String label;

        ColorMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-ribbontrail.json");

    private static RibbonTrailConfig instance;

    private boolean enabled = false;
    private boolean showInFirstPerson = true;
    private float ribbonHeight = 1.0f;
    private float ribbonSeconds = 1.0f;
    private int opacityPercent = 60;
    private ColorMode colorMode = ColorMode.GRADIENT;
    private int colorCount = 3;
    private int headColor = DEFAULT_HEAD_COLOR;
    private int midColor = DEFAULT_MID_COLOR;
    private int tailColor = DEFAULT_TAIL_COLOR;
    private TeleportMode teleportMode = TeleportMode.CONNECT;

    private RibbonTrailConfig() {
    }

    public static RibbonTrailConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Gated by Skyblock Only like every other master toggle ({@link SkyblockGate}). */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    /** The raw saved toggle, ignoring {@link SkyblockGate}. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v; com.killer560.hub.util.FeatureBits.changed();
    }

    public boolean isShowInFirstPerson() {
        return showInFirstPerson;
    }

    public void setShowInFirstPerson(boolean v) {
        showInFirstPerson = v;
    }

    /** How tall the band stands, in blocks. */
    public float getRibbonHeight() {
        return ribbonHeight;
    }

    public void setRibbonHeight(float v) {
        ribbonHeight = clampF(v, MIN_RIBBON_HEIGHT, MAX_RIBBON_HEIGHT);
    }

    /** How long (seconds) a point of the ribbon lasts - so also how long it takes to shrink away when standing still. */
    public float getRibbonSeconds() {
        return ribbonSeconds;
    }

    public void setRibbonSeconds(float v) {
        ribbonSeconds = clampF(v, MIN_RIBBON_SECONDS, MAX_RIBBON_SECONDS);
    }

    public int getOpacityPercent() {
        return opacityPercent;
    }

    public void setOpacityPercent(int v) {
        opacityPercent = Math.max(MIN_OPACITY_PERCENT, Math.min(MAX_OPACITY_PERCENT, v));
    }

    public ColorMode getColorMode() {
        return colorMode;
    }

    public void setColorMode(ColorMode v) {
        colorMode = v == null ? ColorMode.GRADIENT : v;
    }

    /** 2 (head to tail) or 3 (head, middle, tail). */
    public int getColorCount() {
        return colorCount;
    }

    public void setColorCount(int v) {
        colorCount = Math.max(2, Math.min(3, v));
    }

    /** What the ribbon does across a teleport (an etherwarp, an AOTV): a gap, a straight line or an arc. */
    public TeleportMode getTeleportMode() {
        return teleportMode;
    }

    public void setTeleportMode(TeleportMode v) {
        teleportMode = v == null ? TeleportMode.CONNECT : v;
    }

    public int getHeadColor() {
        return headColor;
    }

    public void setHeadColor(int argb) {
        headColor = argb;
    }

    public int getMidColor() {
        return midColor;
    }

    public void setMidColor(int argb) {
        midColor = argb;
    }

    public int getTailColor() {
        return tailColor;
    }

    public void setTailColor(int argb) {
        tailColor = argb;
    }

    private static float clampF(float v, float min, float max) {
        if (Float.isNaN(v)) {
            return min;
        }
        return Math.max(min, Math.min(max, v));
    }

    private static <E extends Enum<E>> E enumOr(JsonObject obj, String key, Class<E> type, E fallback) {
        try {
            return obj.has(key) ? Enum.valueOf(type, obj.get(key).getAsString()) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Reads the ribbon's own keys; {@code heightKey} is "ribbonHeight" here and "ribbonWidth" in an old Trail file. */
    private void read(JsonObject obj, String heightKey) {
        setShowInFirstPerson(ConfigJson.getBool(obj, "showInFirstPerson", showInFirstPerson));
        setRibbonHeight(ConfigJson.getFloat(obj, heightKey, ribbonHeight));
        setRibbonSeconds(ConfigJson.getFloat(obj, "ribbonSeconds", ribbonSeconds));
        setOpacityPercent(ConfigJson.getInt(obj, "opacityPercent", opacityPercent));
        colorMode = enumOr(obj, "colorMode", ColorMode.class, colorMode);
        setColorCount(ConfigJson.getInt(obj, "colorCount", colorCount));
        headColor = ConfigJson.getInt(obj, "headColor", headColor);
        midColor = ConfigJson.getInt(obj, "midColor", midColor);
        tailColor = ConfigJson.getInt(obj, "tailColor", tailColor);
        teleportMode = enumOr(obj, "teleportMode", TeleportMode.class, teleportMode);
    }

    /** Loads, then tells FeatureBits a gated setting may have changed (profile apply reloads every config). */
    public static void load() {
        load0();
        com.killer560.hub.util.FeatureBits.changed();
    }

    private static void load0() {
        // An old Trail file (one with a Style) is split before this file is read.
        TrailConfig.getInstance();
        RibbonTrailConfig cfg = new RibbonTrailConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.read(obj, "ribbonHeight");
            } catch (Exception e) {
                cfg = new RibbonTrailConfig();
            }
        }
        instance = cfg;
    }

    /**
     * Carries an old Trail file's ribbon settings over (TrailConfig saw a {@code style} key). Style Ribbon turns the
     * Ribbon Trail on when the Trail was on, with the saved ribbon settings; Style Squares copies the settings only when
     * this file does not exist yet, and leaves the Ribbon Trail off - nothing visible changes.
     */
    static void migrateFrom(JsonObject old, boolean wasRibbon, boolean trailWasOn) {
        if (!wasRibbon && Files.exists(CONFIG_PATH)) {
            return;
        }
        RibbonTrailConfig cfg = new RibbonTrailConfig();
        cfg.read(old, "ribbonWidth");
        cfg.enabled = wasRibbon && trailWasOn;
        instance = cfg;
        cfg.save();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showInFirstPerson", showInFirstPerson);
            obj.addProperty("ribbonHeight", ribbonHeight);
            obj.addProperty("ribbonSeconds", ribbonSeconds);
            obj.addProperty("opacityPercent", opacityPercent);
            obj.addProperty("colorMode", colorMode.name());
            obj.addProperty("colorCount", colorCount);
            obj.addProperty("headColor", headColor);
            obj.addProperty("midColor", midColor);
            obj.addProperty("tailColor", tailColor);
            obj.addProperty("teleportMode", teleportMode.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
