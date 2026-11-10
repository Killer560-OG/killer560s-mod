package com.killer560.hub.dungeonextras;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted settings for Custom Mage Beam (both builds), Auto Dialogue and Breaker Aura (cheat build only).
 *  Everything ships OFF. Cheat getters are gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED}. */
public final class DungeonExtrasConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-dungeonextras.json");

    public static final int DEFAULT_BEAM_COLOR = 0xFFCC6600;
    /** Mage beam thickness range. killer560 (2026-09-20): "make it so the width is much more impactful" -
     *  the old 1-6 range fed a GL line width, which the driver clamps to 1px on a core profile, so the
     *  slider did almost nothing. The beam is now a world-space billboard quad (see
     *  {@link MageBeamFeature}) and this is its thickness in {@link #BEAM_WIDTH_BLOCKS} units. */
    public static final float MIN_BEAM_WIDTH = 1f;
    public static final float MAX_BEAM_WIDTH = 20f;
    /** Blocks of real beam thickness per width unit: 1.0 -> 5cm, the default 2.0 -> 10cm, 20 -> a full block. */
    public static final float BEAM_WIDTH_BLOCKS = 0.05f;

    private static DungeonExtrasConfig instance;

    // Custom Mage Beam
    private boolean mageBeamEnabled = false;
    private boolean mageBeamFade = false;
    private int mageBeamColor = DEFAULT_BEAM_COLOR;
    private float mageBeamWidth = 2.0f;
    private int mageBeamDurationTicks = 40;
    /** The beam's cross-section (killer560, 2026-10-18: "should have the option to be flat, square, cylindrical, ect. Make
     *  square the default"): an index into {@link #BEAM_SHAPE_NAMES}. */
    private int mageBeamShape = BEAM_SHAPE_SQUARE;
    public static final int BEAM_SHAPE_SQUARE = 0;
    public static final int BEAM_SHAPE_FLAT = 1;
    public static final int BEAM_SHAPE_CYLINDER = 2;
    public static final String[] BEAM_SHAPE_NAMES = {"Square", "Flat", "Cylinder"};
    /** The beam's coloring (killer560, 2026-11-01: "for color have a left to right fade option and rotating color
     *  options"): Solid (Beam Color), Fade (Beam Color at the start, Fade Color at the end) or Rotating (a rainbow
     *  that runs along the beam and turns over time). */
    private int mageBeamColorMode = BEAM_COLOR_SOLID;
    public static final int BEAM_COLOR_SOLID = 0;
    public static final int BEAM_COLOR_FADE = 1;
    public static final int BEAM_COLOR_ROTATING = 2;
    public static final String[] BEAM_COLOR_MODE_NAMES = {"Solid", "Fade", "Rotating"};
    public static final int DEFAULT_BEAM_COLOR_2 = 0xFF55FFFF;
    private int mageBeamColor2 = DEFAULT_BEAM_COLOR_2;

    // Auto Dialogue (cheat)
    private boolean autoDialogueEnabled = false;
    private int autoDialogueDelayTicks = 5;
    /** Comma-separated NPC names; blank = any NPC (reference behavior). */
    /** Off (default): Auto Dialogue only acts while {@code DungeonState.isInDungeon()}. On: anywhere on Skyblock. */
    private boolean autoDialogueOutsideDungeons = false;

    // Breaker Aura (cheat)
    private boolean breakerAuraEnabled = false;
    /** The measured limit, from the constant rather than a literal that has to be kept in step. */
    private double breakerAuraReach = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH;
    /** The pick/unpick key. Semicolon by default - killer560 (2026-09-23): "default the breaker key to ;". */
    private int breakerAuraSelectKey = com.killer560.hub.util.Keys.SEMICOLON;
    /**
     * LEGACY (2026-09-27): the picks used to live here, and only here. {@link BreakerAuraStore} is the live copy
     * now - one file per named config, swappable "just like the auto routes can swap" (killer560) - so nothing
     * here is read by {@link BreakerAuraFeature} any more. The field stays, and still round-trips through
     * {@link #load} / {@link #save} exactly as before, purely as the seed {@link BreakerAuraStore} migrates his
     * real picks from into {@code default.json} the first time that folder has no file of its own; see
     * {@code BreakerAuraStore#migrateLegacyPicks}.
     */
    private final java.util.LinkedHashSet<String> breakerAuraSelected = new java.util.LinkedHashSet<>();
    /** Which {@link BreakerAuraStore} config file is active, by name inside {@link BreakerAuraStore#directory()} -
     *  "Choose Breaker Aura Config". Always a plain {@code *.json} name {@link BreakerAuraStore#validateConfigName}
     *  accepts; anything else in a hand-edited settings file falls back to the default. */
    private String breakerAuraConfigFile = BreakerAuraStore.DEFAULT_CONFIG_NAME;
    /**
     * Multi Break: every picked block in reach (and every block of an Auto Routes breaker node, its linger and re-dig)
     * is queued and broken BACK TO BACK, one a client tick with no tick between and the next block's turn riding the
     * previous dig's movement packet; no cooldown or Interact Delay between the blocks of one run (multibreak-1102).
     * OFF: one block per break cycle - Breaker Aura's Cooldown after every block, Auto Routes' legit-mode Interact Delay.
     *
     * <p>Until 2026-11-02 it sent every block in reach on ONE tick. GrimAC 2.3.74's experimental MultiBreak allows one
     * block (and face) per server tick, and a vanilla client sends exactly one tick packet per client tick, so one a tick
     * is the fastest clean pace (util/DigWindow has the source lines). Removed that day (gridexp-1102), restored the same
     * day at one a tick with the same keys, so his old value comes back.
     *
     * <p>Default ON since 2026-09-29, switched on ONCE for a config that predates that - see
     * {@link #breakerAuraMultiBreakDefaulted}.
     */
    private boolean breakerAuraMultiBreak = true;

    /** Whether the Multi Break default has already been applied to this config. One-time, like Interop's. A new
     * install starts with it applied (Multi Break is already on): with false here, a fresh client's first save wrote
     * "false" and the next load switched Multi Break back on over his "off" (testkit 310, 2026-10-22). Only a file
     * with no such key, written before 2026-09-29, gets the one-time switch. */
    private boolean breakerAuraMultiBreakDefaulted = true;

    /**
     * Burst Multi Break (owner exception, killer560 2026-11-02: "Have a setting that says use multibreak - flags newest
     * test grim. So someone can still use the old one because I don't think it flags on hypixel yet."). Every block in
     * reach goes on ONE tick, exactly as Multi Break did before 2026-11-02 (d2da5856). GrimAC 2.3.74's experimental
     * MultiBreak flags it, and its label says so. Default OFF. Since breaker3-1110 (killer560 2026-11-10: "one for legit
     * multi break (doesnt flag any grim) and one for burst multi break (flags grim)") it is its own row, and it and
     * {@link #breakerAuraMultiBreak} (Legit Multi Break) are never both on: each setter turns the other off, and a file
     * from before with both on loads as Burst.
     */
    private boolean breakerAuraBurstMultiBreak = false;
     /**
      * Edit Mode: show the picked blocks and let him change them, but break NOTHING.
      *
      * <p>killer560 (2026-09-29): "There should be a button to toggle edit mode that makes it not break them
      * for me to add or remove them." Until now the only way to edit a wall was to turn the whole feature off
      * - and with it off the boxes were still drawn, which was the other half of the complaint.
      */
     private boolean breakerAuraEditMode = false;
     /** Legacy, read at load only, to migrate a Blocks Per Cycle above 1 into {@link #breakerAuraMultiBreak}. */
     private int breakerAuraBlocksPerCycle = 1;
    /**
     * Ticks to wait between breaks, on top of the one-a-tick the gate already enforces. Zero by default now: the
     * tick is the rate limit, twenty a second, which is what QUOI does and what he asked for.
     */
    private int breakerAuraCooldownTicks = 0;
    private boolean breakerAuraZeroPing = false;
    /** killer560: "when I am in the edit mode, the breaker aura will not work ... toggle that in the actual setting." */
    private boolean breakerAuraRespectEditMode = true;
    private boolean breakerAuraAutoSwap = false;
    private int breakerAuraSwapDelayTicks = 4;
    private boolean breakerAuraSwapBack = true;
    private int breakerAuraSwapBackIdleTicks = 20;

    /**
     * How picked blocks are drawn (killer560, 2026-10-06: "make an option for dungeon breaker aura to have the blocks
     * as highlights or as waypoints, waypoints should just show as long as it is in its render distance").
     * HIGHLIGHT is the original look: depth-tested, brighter once in reach. WAYPOINT draws through walls, out to the
     * render distance.
     */
    public enum BreakerDisplay {
        HIGHLIGHT("Highlight"), WAYPOINT("Waypoint");

        public final String label;

        BreakerDisplay(String label) {
            this.label = label;
        }

        public BreakerDisplay next() {
            BreakerDisplay[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    /** Outline (the original look), Fill, or both - "allow an option for them to be outlines, fills, or filled
     *  outlines" (killer560, 2026-10-06). Applies to either {@link BreakerDisplay}. */
    public enum BreakerStyle {
        OUTLINE("Outline"), FILL("Fill"), FILLED_OUTLINE("Filled Outline");

        public final String label;

        BreakerStyle(String label) {
            this.label = label;
        }

        public BreakerStyle next() {
            BreakerStyle[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    private BreakerDisplay breakerAuraDisplay = BreakerDisplay.HIGHLIGHT;
    private BreakerStyle breakerAuraStyle = BreakerStyle.OUTLINE;

    // Shared automation gate (global; lives here because this config is already in ProfileManager.reloadAllConfigs).

    private DungeonExtrasConfig() {
    }

    public static DungeonExtrasConfig getInstance() {
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
    public boolean isMageBeamEnabledRaw() {
        return mageBeamEnabled;
    }

    private static void load0() {
        DungeonExtrasConfig cfg = new DungeonExtrasConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.mageBeamEnabled = bool(o, "mageBeamEnabled", cfg.mageBeamEnabled);
                cfg.mageBeamFade = bool(o, "mageBeamFade", cfg.mageBeamFade);
                cfg.mageBeamColor = o.has("mageBeamColor") ? o.get("mageBeamColor").getAsInt() : cfg.mageBeamColor;
                cfg.mageBeamWidth = clamp(o.has("mageBeamWidth") ? o.get("mageBeamWidth").getAsFloat() : cfg.mageBeamWidth, MIN_BEAM_WIDTH, MAX_BEAM_WIDTH);
                cfg.setMageBeamShape(o.has("mageBeamShape") ? o.get("mageBeamShape").getAsInt() : BEAM_SHAPE_SQUARE);
                cfg.setMageBeamColorMode(o.has("mageBeamColorMode") ? o.get("mageBeamColorMode").getAsInt() : BEAM_COLOR_SOLID);
                cfg.mageBeamColor2 = o.has("mageBeamColor2") ? o.get("mageBeamColor2").getAsInt() : cfg.mageBeamColor2;
                cfg.mageBeamDurationTicks = clampInt(o.has("mageBeamDurationTicks") ? o.get("mageBeamDurationTicks").getAsInt() : cfg.mageBeamDurationTicks, 5, 100);
                cfg.autoDialogueEnabled = bool(o, "autoDialogueEnabled", cfg.autoDialogueEnabled);
                cfg.autoDialogueDelayTicks = clampInt(o.has("autoDialogueDelayTicks") ? o.get("autoDialogueDelayTicks").getAsInt() : cfg.autoDialogueDelayTicks, 0, 40);
                cfg.autoDialogueOutsideDungeons = bool(o, "autoDialogueOutsideDungeons", cfg.autoDialogueOutsideDungeons);
                cfg.breakerAuraEnabled = bool(o, "breakerAuraEnabled", cfg.breakerAuraEnabled);
                // 4.5, not 5.5: past the measured limit the server refuses the break outright. Clamped on LOAD as
                // well as in the setter, or a config saved at 5.5 before this change would load unchanged.
                cfg.breakerAuraReach = clamp((float) (o.has("breakerAuraReach") ? o.get("breakerAuraReach").getAsDouble() : cfg.breakerAuraReach), 1f, (float) com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH);
                // breakerAuraSideReach and breakerAuraSelectedOnly (removed 2026-10-05: picked blocks only, always)
                // are simply not read any more; an old file carrying them loads fine and the next save drops them.
                cfg.breakerAuraSelectKey = o.has("breakerAuraSelectKey")
                        ? o.get("breakerAuraSelectKey").getAsInt() : cfg.breakerAuraSelectKey;
                cfg.breakerAuraSelected.clear();
                if (o.has("breakerAuraSelected") && o.get("breakerAuraSelected").isJsonArray()) {
                    for (com.google.gson.JsonElement e : o.get("breakerAuraSelected").getAsJsonArray()) {
                        if (e != null && e.isJsonPrimitive()) {
                            cfg.breakerAuraSelected.add(e.getAsString());
                        }
                    }
                }
                cfg.breakerAuraBlocksPerCycle = clampInt(o.has("breakerAuraBlocksPerCycle") ? o.get("breakerAuraBlocksPerCycle").getAsInt() : cfg.breakerAuraBlocksPerCycle, 1, 20);
                // Migration: a config written before Multi Break existed carries only the old slider. Above 1 meant
                // "send more than one", so that is what it becomes. A config that knows about Multi Break is
                // authoritative and the old number is ignored.
                cfg.breakerAuraMultiBreak = o.has("breakerAuraMultiBreak")
                        ? o.get("breakerAuraMultiBreak").getAsBoolean()
                        : cfg.breakerAuraBlocksPerCycle > 1;
                // The 2026-09-29 default, applied ONCE. A config written before then carries the old "false" and would
                // otherwise never see the change; after this it is his setting and is never touched again.
                cfg.breakerAuraMultiBreakDefaulted = bool(o, "breakerAuraMultiBreakDefaulted", false);
                if (!cfg.breakerAuraMultiBreakDefaulted) {
                    cfg.breakerAuraMultiBreakDefaulted = true;
                    cfg.breakerAuraMultiBreak = true;
                    com.killer560.hub.util.ModLog.get("killer560smod-dungeonextras").info(
                            "[DungeonExtras] Multi Break switched on once (new default). Turn it off in the Breaker Aura"
                                    + " tab for one block per break cycle.");
                }
                cfg.breakerAuraBurstMultiBreak = bool(o, "breakerAuraBurstMultiBreak", false);
                if (cfg.breakerAuraBurstMultiBreak) {
                    cfg.breakerAuraMultiBreak = false; // breaker3-1110: Burst and Legit Multi Break are one or the other
                }
                cfg.breakerAuraCooldownTicks = clampInt(o.has("breakerAuraCooldownTicks") ? o.get("breakerAuraCooldownTicks").getAsInt() : cfg.breakerAuraCooldownTicks, 0, 20);
                cfg.breakerAuraZeroPing = bool(o, "breakerAuraZeroPing", cfg.breakerAuraZeroPing);
                cfg.breakerAuraRespectEditMode = bool(o, "breakerAuraRespectEditMode", cfg.breakerAuraRespectEditMode);
                cfg.breakerAuraEditMode = bool(o, "breakerAuraEditMode", cfg.breakerAuraEditMode);
                cfg.breakerAuraAutoSwap = bool(o, "breakerAuraAutoSwap", cfg.breakerAuraAutoSwap);
                cfg.breakerAuraSwapDelayTicks = clampInt(o.has("breakerAuraSwapDelayTicks") ? o.get("breakerAuraSwapDelayTicks").getAsInt() : cfg.breakerAuraSwapDelayTicks, 1, 20);
                cfg.breakerAuraSwapBack = bool(o, "breakerAuraSwapBack", cfg.breakerAuraSwapBack);
                cfg.breakerAuraSwapBackIdleTicks = clampInt(o.has("breakerAuraSwapBackIdleTicks") ? o.get("breakerAuraSwapBackIdleTicks").getAsInt() : cfg.breakerAuraSwapBackIdleTicks, 5, 100);
                cfg.breakerAuraDisplay = enumOr(o, "breakerAuraDisplay", BreakerDisplay.class, cfg.breakerAuraDisplay);
                cfg.breakerAuraStyle = enumOr(o, "breakerAuraStyle", BreakerStyle.class, cfg.breakerAuraStyle);
                cfg.setBreakerAuraConfigFile(o.has("breakerAuraConfigFile") && o.get("breakerAuraConfigFile").isJsonPrimitive()
                        ? o.get("breakerAuraConfigFile").getAsString() : cfg.breakerAuraConfigFile);
            } catch (Exception e) {
                cfg = new DungeonExtrasConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("mageBeamEnabled", mageBeamEnabled);
            o.addProperty("mageBeamFade", mageBeamFade);
            o.addProperty("mageBeamColor", mageBeamColor);
            o.addProperty("mageBeamWidth", mageBeamWidth);
            o.addProperty("mageBeamDurationTicks", mageBeamDurationTicks);
            o.addProperty("mageBeamShape", mageBeamShape);
            o.addProperty("mageBeamColorMode", mageBeamColorMode);
            o.addProperty("mageBeamColor2", mageBeamColor2);
            o.addProperty("autoDialogueEnabled", autoDialogueEnabled);
            o.addProperty("autoDialogueDelayTicks", autoDialogueDelayTicks);
            o.addProperty("autoDialogueOutsideDungeons", autoDialogueOutsideDungeons);
            o.addProperty("breakerAuraEnabled", breakerAuraEnabled);
            o.addProperty("breakerAuraReach", breakerAuraReach);
            o.addProperty("breakerAuraMultiBreak", breakerAuraMultiBreak);
            o.addProperty("breakerAuraMultiBreakDefaulted", breakerAuraMultiBreakDefaulted);
            o.addProperty("breakerAuraBurstMultiBreak", breakerAuraBurstMultiBreak);
            o.addProperty("breakerAuraBlocksPerCycle", breakerAuraBlocksPerCycle);
            o.addProperty("breakerAuraSelectKey", breakerAuraSelectKey);
            com.google.gson.JsonArray sel = new com.google.gson.JsonArray();
            for (String k : breakerAuraSelected) {
                sel.add(k);
            }
            o.add("breakerAuraSelected", sel);
            o.addProperty("breakerAuraCooldownTicks", breakerAuraCooldownTicks);
            o.addProperty("breakerAuraZeroPing", breakerAuraZeroPing);
            o.addProperty("breakerAuraRespectEditMode", breakerAuraRespectEditMode);
            o.addProperty("breakerAuraEditMode", breakerAuraEditMode);
            o.addProperty("breakerAuraAutoSwap", breakerAuraAutoSwap);
            o.addProperty("breakerAuraSwapDelayTicks", breakerAuraSwapDelayTicks);
            o.addProperty("breakerAuraSwapBack", breakerAuraSwapBack);
            o.addProperty("breakerAuraSwapBackIdleTicks", breakerAuraSwapBackIdleTicks);
            o.addProperty("breakerAuraConfigFile", breakerAuraConfigFile);
            o.addProperty("breakerAuraDisplay", breakerAuraDisplay.name());
            o.addProperty("breakerAuraStyle", breakerAuraStyle.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
    }

    /** A missing, misspelled or non-string value keeps the default instead of throwing - a throw here would reset
     *  the WHOLE config (load's catch). */
    private static <E extends Enum<E>> E enumOr(JsonObject o, String key, Class<E> type, E def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? Enum.valueOf(type, o.get(key).getAsString()) : def;
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---- Custom Mage Beam (both builds) ----
    public boolean isMageBeamEnabled() { return mageBeamEnabled && com.killer560.hub.util.SkyblockGate.allows(); }
    public void setMageBeamEnabled(boolean v) { mageBeamEnabled = v; com.killer560.hub.util.FeatureBits.changed(); }
    public boolean isMageBeamFade() { return mageBeamFade; }
    public void setMageBeamFade(boolean v) { mageBeamFade = v; }
    public int getMageBeamColor() { return mageBeamColor; }
    public void setMageBeamColor(int v) { mageBeamColor = v; }
    public float getMageBeamWidth() { return mageBeamWidth; }
    public void setMageBeamWidth(float v) { mageBeamWidth = clamp(v, MIN_BEAM_WIDTH, MAX_BEAM_WIDTH); }
    public int getMageBeamShape() { return mageBeamShape; }
    public void setMageBeamShape(int v) { mageBeamShape = v < 0 || v >= BEAM_SHAPE_NAMES.length ? BEAM_SHAPE_SQUARE : v; }
    public int getMageBeamColorMode() { return mageBeamColorMode; }
    public void setMageBeamColorMode(int v) { mageBeamColorMode = v < 0 || v >= BEAM_COLOR_MODE_NAMES.length ? BEAM_COLOR_SOLID : v; }
    public int getMageBeamColor2() { return mageBeamColor2; }
    public void setMageBeamColor2(int v) { mageBeamColor2 = v; }
    public int getMageBeamDurationTicks() { return mageBeamDurationTicks; }
    public void setMageBeamDurationTicks(int v) { mageBeamDurationTicks = clampInt(v, 5, 100); }

    // ---- Auto Dialogue (cheat) ----
    public boolean isAutoDialogueEnabled() { return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoDialogueEnabled && com.killer560.hub.util.SkyblockGate.allows(); }
    public boolean isAutoDialogueEnabledRaw() { return autoDialogueEnabled; }
    public void setAutoDialogueEnabled(boolean v) { autoDialogueEnabled = v; }
    public int getAutoDialogueDelayTicks() { return autoDialogueDelayTicks; }
    public void setAutoDialogueDelayTicks(int v) { autoDialogueDelayTicks = clampInt(v, 0, 40); }
    public boolean isAutoDialogueOutsideDungeons() { return autoDialogueOutsideDungeons; }
    public void setAutoDialogueOutsideDungeons(boolean v) { autoDialogueOutsideDungeons = v; }

    // ---- Breaker Aura (cheat) ----
    public int getBreakerAuraSelectKey() { return breakerAuraSelectKey; }
    public void setBreakerAuraSelectKey(int v) { breakerAuraSelectKey = v; }
    /** LEGACY read only - see the field doc. {@link BreakerAuraFeature} no longer reads or writes this set. */
    public java.util.LinkedHashSet<String> getBreakerAuraSelected() { return breakerAuraSelected; }

    /** File name (inside {@link BreakerAuraStore#directory()}) of the config in use - never a path. */
    public String getBreakerAuraConfigFile() { return breakerAuraConfigFile; }

    /** Rejects anything {@link BreakerAuraStore#validateConfigName} would (path separators, "..", odd characters)
     *  rather than let a hand-edited settings file point the store outside its folder. */
    public void setBreakerAuraConfigFile(String name) {
        String clean = BreakerAuraStore.normalizeConfigName(name);
        breakerAuraConfigFile = clean != null && BreakerAuraStore.validateConfigName(clean) == null
                ? clean : BreakerAuraStore.DEFAULT_CONFIG_NAME;
    }

    public boolean isBreakerAuraEnabled() { return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && breakerAuraEnabled && com.killer560.hub.util.SkyblockGate.allows(); }
    public boolean isBreakerAuraEnabledRaw() { return breakerAuraEnabled; }
    public void setBreakerAuraEnabled(boolean v) { breakerAuraEnabled = v; }
    public double getBreakerAuraReach() { return breakerAuraReach; }
    public void setBreakerAuraReach(double v) { breakerAuraReach = clamp((float) v, 1f, (float) com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH); }
    public boolean isBreakerAuraEditMode() { return breakerAuraEditMode; }
    public void setBreakerAuraEditMode(boolean v) { breakerAuraEditMode = v; }
    public boolean isBreakerAuraMultiBreak() { return breakerAuraMultiBreak; }
    /** The switch itself (the menu row). */
    public boolean isBreakerAuraBurstMultiBreakRaw() { return breakerAuraBurstMultiBreak; }
    public void setBreakerAuraBurstMultiBreak(boolean v) {
        breakerAuraBurstMultiBreak = v;
        if (v) {
            breakerAuraMultiBreak = false;
        }
    }
    /** True when breaks go as the old burst: Burst Multi Break on, cheat build only. */
    public boolean isBreakerAuraBurstMultiBreak() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && breakerAuraBurstMultiBreak;
    }
    /** Legit Multi Break: back to back, one a server tick (the menu row). */
    public void setBreakerAuraMultiBreak(boolean v) {
        breakerAuraMultiBreak = v;
        if (v) {
            breakerAuraBurstMultiBreak = false;
        }
    }
    /** Either Multi Break: blocks in reach go with no cooldown between them (Auto Routes breaker nodes too). */
    public boolean isBreakerAuraAnyMultiBreak() { return breakerAuraMultiBreak || isBreakerAuraBurstMultiBreak(); }
    public int getBreakerAuraCooldownTicks() { return breakerAuraCooldownTicks; }
    // Lower bound is 0, matching the field's own default and what the loader accepts. It clamped to 1, so once
    // this setter had been called there was no way back to the shipped value of "no cooldown".
    public void setBreakerAuraCooldownTicks(int v) { breakerAuraCooldownTicks = clampInt(v, 0, 20); }
    public boolean isBreakerAuraZeroPing() { return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && breakerAuraZeroPing; }
    public boolean isBreakerAuraZeroPingRaw() { return breakerAuraZeroPing; }
    public void setBreakerAuraZeroPing(boolean v) { breakerAuraZeroPing = v; }
    public boolean isBreakerAuraRespectEditMode() { return breakerAuraRespectEditMode; }
    public void setBreakerAuraRespectEditMode(boolean v) { breakerAuraRespectEditMode = v; }
    public boolean isBreakerAuraAutoSwap() { return breakerAuraAutoSwap; }
    public void setBreakerAuraAutoSwap(boolean v) { breakerAuraAutoSwap = v; }
    public int getBreakerAuraSwapDelayTicks() { return breakerAuraSwapDelayTicks; }
    public void setBreakerAuraSwapDelayTicks(int v) { breakerAuraSwapDelayTicks = clampInt(v, 1, 20); }
    public boolean isBreakerAuraSwapBack() { return breakerAuraSwapBack; }
    public void setBreakerAuraSwapBack(boolean v) { breakerAuraSwapBack = v; }
    public int getBreakerAuraSwapBackIdleTicks() { return breakerAuraSwapBackIdleTicks; }
    public void setBreakerAuraSwapBackIdleTicks(int v) { breakerAuraSwapBackIdleTicks = clampInt(v, 5, 100); }
    public BreakerDisplay getBreakerAuraDisplay() { return breakerAuraDisplay; }
    public void setBreakerAuraDisplay(BreakerDisplay v) { breakerAuraDisplay = v == null ? BreakerDisplay.HIGHLIGHT : v; }
    public BreakerStyle getBreakerAuraStyle() { return breakerAuraStyle; }
    public void setBreakerAuraStyle(BreakerStyle v) { breakerAuraStyle = v == null ? BreakerStyle.OUTLINE : v; }

    // ---- Shared automation gate (both builds; it only ever delays, never acts) ----
}
