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
    private boolean mageBeamHideParticles = true;
    private boolean mageBeamFade = false;
    private int mageBeamColor = DEFAULT_BEAM_COLOR;
    private float mageBeamWidth = 2.0f;
    private int mageBeamDurationTicks = 40;

    // Auto Dialogue (cheat)
    private boolean autoDialogueEnabled = false;
    private int autoDialogueDelayTicks = 5;
    /** Comma-separated NPC names; blank = any NPC (reference behaviour). */
    private String autoDialogueNpcFilter = "";
    /** Off (default): Auto Dialogue only acts while {@code DungeonState.isInDungeon()}. On: anywhere on Skyblock. */
    private boolean autoDialogueOutsideDungeons = false;

    // Breaker Aura (cheat)
    private boolean breakerAuraEnabled = false;
    /** The measured limit, from the constant rather than a literal that has to be kept in step. */
    private double breakerAuraReach = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH;
    /** The pick/unpick key. Semicolon by default - killer560 (2026-09-23): "default the breaker key to ;". */
    private int breakerAuraSelectKey = org.lwjgl.glfw.GLFW.GLFW_KEY_SEMICOLON;
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
      * Multi Break: break everything in your path that is in reach, every tick, capped only by the charges you
      * actually have left.
      * <p>
      * killer560 (2026-09-27): "make it a setting that doesnt have a slider [...] on multibreak means I should
      * never outrun the breaker aura", and then the acceptance test in one line: "the only breaker command thing
      * I care about is that i never am able to run into a wall while it is breaking."
      * <p>
      * So there is no number to tune. A per-tick cap was the wrong shape for the goal: any fixed number is either
      * higher than the wall needs, in which case it does nothing, or lower than it needs, in which case you walk
      * into the wall - and which of those it is changes with your speed. The only setting that can promise "never
      * outrun it" is no cap at all.
      * <p>
      * Replaces the old Blocks Per Cycle slider (1-5). The old value is still read from the config once, purely so
      * anyone who had set it above 1 gets Multi Break on rather than silently losing what they had asked for.
      */
     /**
      * Multi Break: several breaks on one client tick.
      *
      * <p>Default ON since 2026-09-29, and switched on ONCE for a config that predates that - see
      * {@link #breakerAuraMultiBreakDefaulted}. killer560: "for breaker aura it can multibreak if it doesnt
      * flag." Measured against a real GrimAC on the harness the same day, scenario 51 against scenario 50 as
      * the control: 904 break-starts over 139 ticks with up to TEN on a single tick, 930 blocks cleared,
      * <b>zero violations</b>, zero Post violations, furthest reach 4.50 to the box - and the positive
      * control fired in the same run, so the detector was demonstrably still watching. It is about twice as
      * fast through a wall: 0.54 blocks a tick against 0.28.
      *
      * <p>What that does NOT mean: GrimAC is not Watchdog. Ten interaction packets inside one client tick is
      * not a pattern a hand can produce and is visible as such to anything that looks for it, whether or not
      * Grim names it. Nothing here tries to disguise that - no jitter, no shaped spacing - because disguising
      * it is a different thing from doing it.
      */
     private boolean breakerAuraMultiBreak = true;

     /** Whether the Multi Break default has already been applied to this config. One-time, like Interop's. */
     private boolean breakerAuraMultiBreakDefaulted = false;
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

    public static void load() {
        DungeonExtrasConfig cfg = new DungeonExtrasConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.mageBeamEnabled = bool(o, "mageBeamEnabled", cfg.mageBeamEnabled);
                cfg.mageBeamHideParticles = bool(o, "mageBeamHideParticles", cfg.mageBeamHideParticles);
                cfg.mageBeamFade = bool(o, "mageBeamFade", cfg.mageBeamFade);
                cfg.mageBeamColor = o.has("mageBeamColor") ? o.get("mageBeamColor").getAsInt() : cfg.mageBeamColor;
                cfg.mageBeamWidth = clamp(o.has("mageBeamWidth") ? o.get("mageBeamWidth").getAsFloat() : cfg.mageBeamWidth, MIN_BEAM_WIDTH, MAX_BEAM_WIDTH);
                cfg.mageBeamDurationTicks = clampInt(o.has("mageBeamDurationTicks") ? o.get("mageBeamDurationTicks").getAsInt() : cfg.mageBeamDurationTicks, 5, 100);
                cfg.autoDialogueEnabled = bool(o, "autoDialogueEnabled", cfg.autoDialogueEnabled);
                cfg.autoDialogueDelayTicks = clampInt(o.has("autoDialogueDelayTicks") ? o.get("autoDialogueDelayTicks").getAsInt() : cfg.autoDialogueDelayTicks, 0, 40);
                cfg.autoDialogueNpcFilter = o.has("autoDialogueNpcFilter") ? o.get("autoDialogueNpcFilter").getAsString() : cfg.autoDialogueNpcFilter;
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
                // "send more than one a tick", so that is what it becomes. A config that already knows about Multi
                // Break is authoritative and the old number is ignored.
                cfg.breakerAuraMultiBreak = o.has("breakerAuraMultiBreak")
                        ? o.get("breakerAuraMultiBreak").getAsBoolean()
                        : cfg.breakerAuraBlocksPerCycle > 1;
                // The new default, applied ONCE. A config written before 2026-09-29 carries the old "false"
                // and would otherwise never see the change; after this it is his setting again and is never
                // touched, so turning it back off sticks.
                cfg.breakerAuraMultiBreakDefaulted =
                        bool(o, "breakerAuraMultiBreakDefaulted", cfg.breakerAuraMultiBreakDefaulted);
                if (!cfg.breakerAuraMultiBreakDefaulted) {
                    cfg.breakerAuraMultiBreakDefaulted = true;
                    cfg.breakerAuraMultiBreak = true;
                    com.killer560.hub.util.ModLog.get("killer560smod-dungeonextras").info(
                            "[DungeonExtras] Multi Break switched on once (new default - measured clean "
                                    + "against GrimAC on 2026-09-29). Turn it off in the Breaker Aura tab if "
                                    + "you would rather it sent one break a tick.");
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
            o.addProperty("mageBeamHideParticles", mageBeamHideParticles);
            o.addProperty("mageBeamFade", mageBeamFade);
            o.addProperty("mageBeamColor", mageBeamColor);
            o.addProperty("mageBeamWidth", mageBeamWidth);
            o.addProperty("mageBeamDurationTicks", mageBeamDurationTicks);
            o.addProperty("autoDialogueEnabled", autoDialogueEnabled);
            o.addProperty("autoDialogueDelayTicks", autoDialogueDelayTicks);
            o.addProperty("autoDialogueNpcFilter", autoDialogueNpcFilter);
            o.addProperty("autoDialogueOutsideDungeons", autoDialogueOutsideDungeons);
            o.addProperty("breakerAuraEnabled", breakerAuraEnabled);
            o.addProperty("breakerAuraReach", breakerAuraReach);
            o.addProperty("breakerAuraMultiBreak", breakerAuraMultiBreak);
            o.addProperty("breakerAuraMultiBreakDefaulted", breakerAuraMultiBreakDefaulted);
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

    /** A missing, misspelt or non-string value keeps the default instead of throwing - a throw here would reset
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
    public void setMageBeamEnabled(boolean v) { mageBeamEnabled = v; }
    public boolean isMageBeamHideParticles() { return mageBeamHideParticles; }
    public void setMageBeamHideParticles(boolean v) { mageBeamHideParticles = v; }
    public boolean isMageBeamFade() { return mageBeamFade; }
    public void setMageBeamFade(boolean v) { mageBeamFade = v; }
    public int getMageBeamColor() { return mageBeamColor; }
    public void setMageBeamColor(int v) { mageBeamColor = v; }
    public float getMageBeamWidth() { return mageBeamWidth; }
    public void setMageBeamWidth(float v) { mageBeamWidth = clamp(v, MIN_BEAM_WIDTH, MAX_BEAM_WIDTH); }
    public int getMageBeamDurationTicks() { return mageBeamDurationTicks; }
    public void setMageBeamDurationTicks(int v) { mageBeamDurationTicks = clampInt(v, 5, 100); }

    // ---- Auto Dialogue (cheat) ----
    public boolean isAutoDialogueEnabled() { return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoDialogueEnabled && com.killer560.hub.util.SkyblockGate.allows(); }
    public boolean isAutoDialogueEnabledRaw() { return autoDialogueEnabled; }
    public void setAutoDialogueEnabled(boolean v) { autoDialogueEnabled = v; }
    public int getAutoDialogueDelayTicks() { return autoDialogueDelayTicks; }
    public void setAutoDialogueDelayTicks(int v) { autoDialogueDelayTicks = clampInt(v, 0, 40); }
    public String getAutoDialogueNpcFilter() { return autoDialogueNpcFilter; }
    public void setAutoDialogueNpcFilter(String v) { autoDialogueNpcFilter = v == null ? "" : v; }
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
    public void setBreakerAuraMultiBreak(boolean v) { breakerAuraMultiBreak = v; }
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
