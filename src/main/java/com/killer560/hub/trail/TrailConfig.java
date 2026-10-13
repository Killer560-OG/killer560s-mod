package com.killer560.hub.trail;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted settings for the movement trail - killer560's item 8.4, verbatim: "Trail: one square per
 * tick, 1-100, orange while grounded, blue while airborne, nothing while standing still." Purely
 * cosmetic and client-side (no gameplay effect), so it ships in both the legit and cheat builds; per
 * the "new features default OFF and live in the New tab" rule, {@link #enabled} starts false.
 * <p>
 * {@code length}/{@code squareSize}/{@code opacity}/{@code fadeOut} are the small set of controls the
 * spec obviously needs to be usable (on/off, how long the trail is, how big each square is, how visible
 * it is, and whether it tapers toward the tail) - nothing beyond that was added.
 */
public final class TrailConfig {

    public static final int MIN_LENGTH = 1;
    public static final int MAX_LENGTH = 100;
    public static final float MIN_SQUARE_SIZE = 0.1f;
    public static final float MAX_SQUARE_SIZE = 1.0f;
    public static final int MIN_OPACITY_PERCENT = 5;
    public static final int MAX_OPACITY_PERCENT = 100;
    public static final float MIN_RIBBON_WIDTH = 0.1f;
    public static final float MAX_RIBBON_WIDTH = 2.0f;
    public static final float MIN_RIBBON_SECONDS = 0.2f;
    public static final float MAX_RIBBON_SECONDS = 4.0f;
    public static final int DEFAULT_HEAD_COLOR = 0xFFFF2A1A;
    public static final int DEFAULT_MID_COLOR = 0xFFFF8C1A;
    public static final int DEFAULT_TAIL_COLOR = 0xFFFFE01A;

    /** How the trail is drawn. Squares is the original item 8.4 trail; Ribbon is killer560's 2026-10-13 request
     *  ("can you make trails have an option to do something like this as well?" - a Roblox trail: one smooth band
     *  streaming behind the player, red at the body through orange to yellow, fading out at the end). */
    public enum Style {
        SQUARES("Squares"), RIBBON("Ribbon");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Where the ribbon's colours come from. */
    public enum ColorMode {
        /** The ribbon's own Head / (Middle) / Tail colours. */
        GRADIENT("Gradient"),
        /** The Name Changer's own-name colour and its fade target (falls back to Gradient when no colour is set). */
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
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-trail.json");

    private static TrailConfig instance;

    private boolean enabled = false;
    private int length = 40;
    private float squareSize = 0.3f;
    private int opacityPercent = 60;
    private boolean fadeOut = true;
    private Style style = Style.SQUARES;
    private boolean showInFirstPerson = true;
    private float ribbonWidth = 0.6f;
    private float ribbonSeconds = 1.0f;
    private ColorMode colorMode = ColorMode.GRADIENT;
    private int colorCount = 3;
    private int headColor = DEFAULT_HEAD_COLOR;
    private int midColor = DEFAULT_MID_COLOR;
    private int tailColor = DEFAULT_TAIL_COLOR;

    private TrailConfig() {
    }

    public static TrailConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Same "Skyblock Only" gating every other feature's master toggle goes through - see
     *  {@link SkyblockGate}. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    /** The raw saved toggle, ignoring {@link SkyblockGate} - lets the tab's checkbox reflect what's
     *  actually saved instead of flipping off just because Skyblock Only is hiding it right now (same
     *  pattern as {@code AbilityCooldownConfig#isEnabledRaw}). */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int v) {
        length = Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, v));
    }

    public float getSquareSize() {
        return squareSize;
    }

    public void setSquareSize(float v) {
        squareSize = Math.max(MIN_SQUARE_SIZE, Math.min(MAX_SQUARE_SIZE, v));
    }

    public int getOpacityPercent() {
        return opacityPercent;
    }

    public void setOpacityPercent(int v) {
        opacityPercent = Math.max(MIN_OPACITY_PERCENT, Math.min(MAX_OPACITY_PERCENT, v));
    }

    public boolean isFadeOut() {
        return fadeOut;
    }

    public void setFadeOut(boolean v) {
        fadeOut = v;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style v) {
        style = v == null ? Style.SQUARES : v;
    }

    public boolean isShowInFirstPerson() {
        return showInFirstPerson;
    }

    public void setShowInFirstPerson(boolean v) {
        showInFirstPerson = v;
    }

    public float getRibbonWidth() {
        return ribbonWidth;
    }

    public void setRibbonWidth(float v) {
        ribbonWidth = clampF(v, MIN_RIBBON_WIDTH, MAX_RIBBON_WIDTH);
    }

    /** How long (seconds) a point of the ribbon lasts - so also how long it takes to shrink away when standing still. */
    public float getRibbonSeconds() {
        return ribbonSeconds;
    }

    public void setRibbonSeconds(float v) {
        ribbonSeconds = clampF(v, MIN_RIBBON_SECONDS, MAX_RIBBON_SECONDS);
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

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TrailConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            TrailConfig cfg = new TrailConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.length = Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, ConfigJson.getInt(obj, "length", cfg.length)));
            cfg.squareSize = Math.max(MIN_SQUARE_SIZE, Math.min(MAX_SQUARE_SIZE,
                    ConfigJson.getFloat(obj, "squareSize", cfg.squareSize)));
            cfg.opacityPercent = Math.max(MIN_OPACITY_PERCENT, Math.min(MAX_OPACITY_PERCENT,
                    ConfigJson.getInt(obj, "opacityPercent", cfg.opacityPercent)));
            cfg.fadeOut = ConfigJson.getBool(obj, "fadeOut", cfg.fadeOut);
            cfg.style = enumOr(obj, "style", Style.class, cfg.style);
            cfg.showInFirstPerson = ConfigJson.getBool(obj, "showInFirstPerson", cfg.showInFirstPerson);
            cfg.setRibbonWidth(ConfigJson.getFloat(obj, "ribbonWidth", cfg.ribbonWidth));
            cfg.setRibbonSeconds(ConfigJson.getFloat(obj, "ribbonSeconds", cfg.ribbonSeconds));
            cfg.colorMode = enumOr(obj, "colorMode", ColorMode.class, cfg.colorMode);
            cfg.setColorCount(ConfigJson.getInt(obj, "colorCount", cfg.colorCount));
            cfg.headColor = ConfigJson.getInt(obj, "headColor", cfg.headColor);
            cfg.midColor = ConfigJson.getInt(obj, "midColor", cfg.midColor);
            cfg.tailColor = ConfigJson.getInt(obj, "tailColor", cfg.tailColor);
            instance = cfg;
        } catch (Exception e) {
            instance = new TrailConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("length", length);
            obj.addProperty("squareSize", squareSize);
            obj.addProperty("opacityPercent", opacityPercent);
            obj.addProperty("fadeOut", fadeOut);
            obj.addProperty("style", style.name());
            obj.addProperty("showInFirstPerson", showInFirstPerson);
            obj.addProperty("ribbonWidth", ribbonWidth);
            obj.addProperty("ribbonSeconds", ribbonSeconds);
            obj.addProperty("colorMode", colorMode.name());
            obj.addProperty("colorCount", colorCount);
            obj.addProperty("headColor", headColor);
            obj.addProperty("midColor", midColor);
            obj.addProperty("tailColor", tailColor);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
