package com.killer560.hub.boss;

import com.killer560.hub.util.ModPaths;
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

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-lividsolver.json");

    private static LividSolverConfig instance;

    private boolean enabled = false;
    private boolean showTimer = true;
    /** killer560, 2026-11-01: "an option for seconds as well as ticks" - the invuln timer in seconds instead of ticks. */
    private boolean timerSeconds = false;
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
    // Auto M5 (cheat build, stormm5-1111; AutoM5Feature): runs only under the Livid Solver's switch. Default OFF.
    public static final int MAX_LOADOUT = 12;
    public static final int MIN_SMOOTHING = 1;
    public static final int MAX_SMOOTHING = 10;
    private boolean autoM5 = false;
    private boolean m5Mid = true;
    private boolean m5MidSet = false;
    private int m5MidX;
    private int m5MidY;
    private int m5MidZ;
    private boolean m5ArmorSwap = false;
    private int m5Loadout = 1;
    private boolean m5Buff = true;
    private boolean m5Aim = true;
    private boolean m5AimHead = true;
    private boolean m5Kill = true;
    private int m5Smoothing = 3;

    private static final com.killer560.hub.util.BoolFields<LividSolverConfig> M5_BOOLS =
            new com.killer560.hub.util.BoolFields<LividSolverConfig>()
                    .add("autoM5", false, c -> c.autoM5, (c, v) -> c.autoM5 = v)
                    .add("m5Mid", true, c -> c.m5Mid, (c, v) -> c.m5Mid = v)
                    .add("m5MidSet", false, c -> c.m5MidSet, (c, v) -> c.m5MidSet = v)
                    .add("m5ArmorSwap", false, c -> c.m5ArmorSwap, (c, v) -> c.m5ArmorSwap = v)
                    .add("m5Buff", true, c -> c.m5Buff, (c, v) -> c.m5Buff = v)
                    .add("m5Aim", true, c -> c.m5Aim, (c, v) -> c.m5Aim = v)
                    .add("m5AimHead", true, c -> c.m5AimHead, (c, v) -> c.m5AimHead = v)
                    .add("m5Kill", true, c -> c.m5Kill, (c, v) -> c.m5Kill = v);

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
            cfg.timerSeconds = ConfigJson.getBool(obj, "timerSeconds", false);
            cfg.showLine = ConfigJson.getBool(obj, "showLine", true);
            cfg.tracerThickness = clampThickness(ConfigJson.getFloat(obj, "tracerThickness", DEFAULT_TRACER_THICKNESS));
            cfg.colourMode = ConfigJson.getEnum(obj, "colourMode", ColourMode.class, ColourMode.MATCH);
            cfg.highlightColour = ConfigJson.getInt(obj, "highlightColour", DEFAULT_CUSTOM_COLOUR);
            cfg.tracerColour = ConfigJson.getInt(obj, "tracerColour", DEFAULT_CUSTOM_COLOUR);
            cfg.hideWrong = ConfigJson.getBool(obj, "hideWrong", false);
            cfg.iceSprayAlert = ConfigJson.getBool(obj, "iceSprayAlert", false);
            cfg.announceColor = ConfigJson.getBool(obj, "announceColor", false);
            M5_BOOLS.load(cfg, obj);
            cfg.m5MidX = ConfigJson.getInt(obj, "m5MidX", 0);
            cfg.m5MidY = ConfigJson.getInt(obj, "m5MidY", 0);
            cfg.m5MidZ = ConfigJson.getInt(obj, "m5MidZ", 0);
            cfg.setM5Loadout(ConfigJson.getInt(obj, "m5Loadout", 1));
            cfg.setM5Smoothing(ConfigJson.getInt(obj, "m5Smoothing", 3));
            instance = cfg;
        } catch (Exception e) {
            instance = new LividSolverConfig();
        }
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("showTimer", showTimer);
        obj.addProperty("timerSeconds", timerSeconds);
        obj.addProperty("showLine", showLine);
        obj.addProperty("tracerThickness", tracerThickness);
        obj.addProperty("colourMode", colourMode.name());
        obj.addProperty("highlightColour", highlightColour);
        obj.addProperty("tracerColour", tracerColour);
        obj.addProperty("hideWrong", hideWrong);
        obj.addProperty("iceSprayAlert", iceSprayAlert);
        obj.addProperty("announceColor", announceColor);
        M5_BOOLS.save(this, obj);
        obj.addProperty("m5MidX", m5MidX);
        obj.addProperty("m5MidY", m5MidY);
        obj.addProperty("m5MidZ", m5MidZ);
        obj.addProperty("m5Loadout", m5Loadout);
        obj.addProperty("m5Smoothing", m5Smoothing);
        ConfigJson.write(CONFIG_PATH, obj);
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

    public boolean isTimerSeconds() {
        return timerSeconds;
    }

    public void setTimerSeconds(boolean timerSeconds) {
        this.timerSeconds = timerSeconds;
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

    // ---- Auto M5 (cheat build) ----
    /** On only with the Livid Solver's own switch (its rows show under it), in a cheat build. */
    public boolean isAutoM5() { return autoM5 && com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && isEnabled(); }
    public boolean getAutoM5Raw() { return autoM5; }
    public void setAutoM5(boolean v) { autoM5 = v; com.killer560.hub.util.FeatureBits.changed(); }
    public boolean isM5Mid() { return m5Mid; }
    public void setM5Mid(boolean v) { m5Mid = v; }
    /** The saved mid spot (block his feet stand in), or null before Set Mid Here was pressed. */
    public net.minecraft.core.BlockPos getM5MidSpot() { return m5MidSet ? new net.minecraft.core.BlockPos(m5MidX, m5MidY, m5MidZ) : null; }
    public void setM5MidSpot(net.minecraft.core.BlockPos p) {
        m5MidSet = p != null;
        if (p != null) {
            m5MidX = p.getX();
            m5MidY = p.getY();
            m5MidZ = p.getZ();
        }
    }
    public boolean isM5ArmorSwap() { return m5ArmorSwap; }
    public void setM5ArmorSwap(boolean v) { m5ArmorSwap = v; }
    public int getM5Loadout() { return m5Loadout; }
    public void setM5Loadout(int v) { m5Loadout = Math.max(1, Math.min(MAX_LOADOUT, v)); }
    public boolean isM5Buff() { return m5Buff; }
    public void setM5Buff(boolean v) { m5Buff = v; }
    public boolean isM5Aim() { return m5Aim; }
    public void setM5Aim(boolean v) { m5Aim = v; }
    public boolean isM5AimHead() { return m5AimHead; }
    public void setM5AimHead(boolean v) { m5AimHead = v; }
    public boolean isM5Kill() { return m5Kill; }
    public void setM5Kill(boolean v) { m5Kill = v; }
    public int getM5Smoothing() { return m5Smoothing; }
    public void setM5Smoothing(int v) { m5Smoothing = Math.max(MIN_SMOOTHING, Math.min(MAX_SMOOTHING, v)); }

    private static float clampThickness(float v) {
        return Math.max(MIN_TRACER_THICKNESS, Math.min(MAX_TRACER_THICKNESS, v));
    }
}
