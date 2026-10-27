package com.killer560.hub.boss;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Livid Solver settings - see {@link LividSolverFeature}'s class doc for the real
 *  Odin-ported wool/entity identification this is built on. Ships disabled by default, same as every
 *  other new feature in this mod. */
public final class LividSolverConfig {

    /** Tracer thickness range (killer560, 2026-10-18: "tracer thickness" for the Livid solver). */
    public static final float MIN_TRACER_THICKNESS = 1f;
    public static final float MAX_TRACER_THICKNESS = 10f;
    public static final float DEFAULT_TRACER_THICKNESS = 2f;
    public static final int DEFAULT_CUSTOM_COLOUR = 0xFFFFFFFF;

    /** Where the highlight and the tracer get their color (killer560, 2026-10-18): the Livid's own wool color, or the
     *  two colors set in the Custom settings. */
    public enum ColourMode {
        MATCH("Match Livid"), CUSTOM("Custom");

        public final String label;

        ColourMode(String label) {
            this.label = label;
        }

        public ColourMode next() {
            return this == MATCH ? CUSTOM : MATCH;
        }

        public ColourMode previous() {
            return this == MATCH ? CUSTOM : MATCH;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-lividsolver.json");

    private static LividSolverConfig instance;

    private boolean enabled = false;
    private boolean showTimer = true;
    /** killer560, 2026-09-20: "add an option to draw a line to the correct livid". */
    private boolean showLine = true;
    private float tracerThickness = DEFAULT_TRACER_THICKNESS;
    private ColourMode colourMode = ColourMode.MATCH;
    private int highlightColour = DEFAULT_CUSTOM_COLOUR;
    private int tracerColour = DEFAULT_CUSTOM_COLOUR;
    // 2026-10-25 extras, all OFF: Hide Wrong Livids, the Ice Spray title, the party color announcement.
    private boolean hideWrong = false;
    private boolean iceSprayAlert = false;
    private boolean announceColor = false;

    private LividSolverConfig() {
    }

    public static LividSolverConfig getInstance() {
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

    private static void load0() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new LividSolverConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            LividSolverConfig cfg = new LividSolverConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showTimer = ConfigJson.getBool(obj, "showTimer", true);
            cfg.showLine = ConfigJson.getBool(obj, "showLine", true);
            cfg.tracerThickness = clampThickness(ConfigJson.getFloat(obj, "tracerThickness", DEFAULT_TRACER_THICKNESS));
            cfg.colourMode = ConfigJson.getEnum(obj, "colourMode", ColourMode.class, ColourMode.MATCH);
            cfg.highlightColour = ConfigJson.getInt(obj, "highlightColour", DEFAULT_CUSTOM_COLOUR);
            cfg.tracerColour = ConfigJson.getInt(obj, "tracerColour", DEFAULT_CUSTOM_COLOUR);
            cfg.hideWrong = ConfigJson.getBool(obj, "hideWrong", false);
            cfg.iceSprayAlert = ConfigJson.getBool(obj, "iceSprayAlert", false);
            cfg.announceColor = ConfigJson.getBool(obj, "announceColor", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new LividSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showTimer", showTimer);
            obj.addProperty("showLine", showLine);
            obj.addProperty("tracerThickness", tracerThickness);
            obj.addProperty("colourMode", colourMode.name());
            obj.addProperty("highlightColour", highlightColour);
            obj.addProperty("tracerColour", tracerColour);
            obj.addProperty("hideWrong", hideWrong);
            obj.addProperty("iceSprayAlert", iceSprayAlert);
            obj.addProperty("announceColor", announceColor);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** The stored switch alone (no Skyblock Only gate): FeatureBits' gate. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public boolean isShowTimer() {
        return showTimer;
    }

    public void setShowTimer(boolean showTimer) {
        this.showTimer = showTimer;
    }

    public boolean isShowLine() {
        return showLine;
    }

    public void setShowLine(boolean showLine) {
        this.showLine = showLine;
    }

    public float getTracerThickness() {
        return tracerThickness;
    }

    public void setTracerThickness(float tracerThickness) {
        this.tracerThickness = clampThickness(tracerThickness);
    }

    public ColourMode getColourMode() {
        return colourMode;
    }

    public void setColourMode(ColourMode colourMode) {
        this.colourMode = colourMode == null ? ColourMode.MATCH : colourMode;
    }

    public int getHighlightColour() {
        return highlightColour;
    }

    public void setHighlightColour(int argb) {
        this.highlightColour = argb;
    }

    public int getTracerColour() {
        return tracerColour;
    }

    public void setTracerColour(int argb) {
        this.tracerColour = argb;
    }

    public boolean isHideWrong() {
        return hideWrong;
    }

    public void setHideWrong(boolean v) {
        hideWrong = v;
    }

    public boolean isIceSprayAlert() {
        return iceSprayAlert;
    }

    public void setIceSprayAlert(boolean v) {
        iceSprayAlert = v;
    }

    public boolean isAnnounceColor() {
        return announceColor;
    }

    public void setAnnounceColor(boolean v) {
        announceColor = v;
    }

    private static float clampThickness(float v) {
        return Math.max(MIN_TRACER_THICKNESS, Math.min(MAX_TRACER_THICKNESS, v));
    }
}
