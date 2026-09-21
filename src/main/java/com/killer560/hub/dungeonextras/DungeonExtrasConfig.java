package com.killer560.hub.dungeonextras;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted settings for Custom Mage Beam (both builds), Auto Dialogue and Breaker Aura (cheat build only).
 *  Everything ships OFF. Cheat getters are gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED}. */
public final class DungeonExtrasConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-dungeonextras.json");

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
    private double breakerAuraReach = 4.5;
    /** Kept so old configs still load. killer560 (2026-09-20) asked every aura to take one target per tick, so
     *  Breaker Aura now always breaks exactly one block per cycle and this value is no longer read. */
    private int breakerAuraBlocksPerCycle = 1;
    private int breakerAuraCooldownTicks = 6;
    private boolean breakerAuraZeroPing = false;
    /** killer560: "when I am in the edit mode, the breaker aura will not work ... toggle that in the actual setting." */
    private boolean breakerAuraRespectEditMode = true;
    private boolean breakerAuraAutoSwap = false;
    private int breakerAuraSwapDelayTicks = 4;
    private boolean breakerAuraSwapBack = true;
    private int breakerAuraSwapBackIdleTicks = 20;

    // Shared automation gate (global; lives here because this config is already in ProfileManager.reloadAllConfigs).
    private boolean actionGateEnabled = true;
    private int actionGateMinSpacingTicks = 2;

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
                cfg.breakerAuraReach = clamp((float) (o.has("breakerAuraReach") ? o.get("breakerAuraReach").getAsDouble() : cfg.breakerAuraReach), 1f, 5.5f);
                cfg.breakerAuraBlocksPerCycle = clampInt(o.has("breakerAuraBlocksPerCycle") ? o.get("breakerAuraBlocksPerCycle").getAsInt() : cfg.breakerAuraBlocksPerCycle, 1, 5);
                cfg.breakerAuraCooldownTicks = clampInt(o.has("breakerAuraCooldownTicks") ? o.get("breakerAuraCooldownTicks").getAsInt() : cfg.breakerAuraCooldownTicks, 1, 20);
                cfg.breakerAuraZeroPing = bool(o, "breakerAuraZeroPing", cfg.breakerAuraZeroPing);
                cfg.breakerAuraRespectEditMode = bool(o, "breakerAuraRespectEditMode", cfg.breakerAuraRespectEditMode);
                cfg.breakerAuraAutoSwap = bool(o, "breakerAuraAutoSwap", cfg.breakerAuraAutoSwap);
                cfg.breakerAuraSwapDelayTicks = clampInt(o.has("breakerAuraSwapDelayTicks") ? o.get("breakerAuraSwapDelayTicks").getAsInt() : cfg.breakerAuraSwapDelayTicks, 1, 20);
                cfg.breakerAuraSwapBack = bool(o, "breakerAuraSwapBack", cfg.breakerAuraSwapBack);
                cfg.breakerAuraSwapBackIdleTicks = clampInt(o.has("breakerAuraSwapBackIdleTicks") ? o.get("breakerAuraSwapBackIdleTicks").getAsInt() : cfg.breakerAuraSwapBackIdleTicks, 5, 100);
                cfg.actionGateEnabled = bool(o, "actionGateEnabled", cfg.actionGateEnabled);
                cfg.actionGateMinSpacingTicks = clampInt(o.has("actionGateMinSpacingTicks") ? o.get("actionGateMinSpacingTicks").getAsInt() : cfg.actionGateMinSpacingTicks, 0, 10);
            } catch (Exception e) {
                cfg = new DungeonExtrasConfig();
            }
        }
        instance = cfg;
        cfg.pushActionGate();
    }

    /** The gate itself holds no file of its own, so every load/save republishes the two settings to it. */
    private void pushActionGate() {
        com.killer560.hub.util.ActionGate.setEnabled(actionGateEnabled);
        com.killer560.hub.util.ActionGate.setMinSpacingTicks(actionGateMinSpacingTicks);
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
            o.addProperty("breakerAuraBlocksPerCycle", breakerAuraBlocksPerCycle);
            o.addProperty("breakerAuraCooldownTicks", breakerAuraCooldownTicks);
            o.addProperty("breakerAuraZeroPing", breakerAuraZeroPing);
            o.addProperty("breakerAuraRespectEditMode", breakerAuraRespectEditMode);
            o.addProperty("breakerAuraAutoSwap", breakerAuraAutoSwap);
            o.addProperty("breakerAuraSwapDelayTicks", breakerAuraSwapDelayTicks);
            o.addProperty("breakerAuraSwapBack", breakerAuraSwapBack);
            o.addProperty("breakerAuraSwapBackIdleTicks", breakerAuraSwapBackIdleTicks);
            o.addProperty("actionGateEnabled", actionGateEnabled);
            o.addProperty("actionGateMinSpacingTicks", actionGateMinSpacingTicks);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        pushActionGate();
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
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
    public boolean isBreakerAuraEnabled() { return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && breakerAuraEnabled && com.killer560.hub.util.SkyblockGate.allows(); }
    public boolean isBreakerAuraEnabledRaw() { return breakerAuraEnabled; }
    public void setBreakerAuraEnabled(boolean v) { breakerAuraEnabled = v; }
    public double getBreakerAuraReach() { return breakerAuraReach; }
    public void setBreakerAuraReach(double v) { breakerAuraReach = clamp((float) v, 1f, 5.5f); }
    public int getBreakerAuraBlocksPerCycle() { return breakerAuraBlocksPerCycle; }
    public void setBreakerAuraBlocksPerCycle(int v) { breakerAuraBlocksPerCycle = clampInt(v, 1, 5); }
    public int getBreakerAuraCooldownTicks() { return breakerAuraCooldownTicks; }
    public void setBreakerAuraCooldownTicks(int v) { breakerAuraCooldownTicks = clampInt(v, 1, 20); }
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

    // ---- Shared automation gate (both builds; it only ever delays, never acts) ----
    public boolean isActionGateEnabled() { return actionGateEnabled; }
    public void setActionGateEnabled(boolean v) { actionGateEnabled = v; com.killer560.hub.util.ActionGate.setEnabled(v); }
    public int getActionGateMinSpacingTicks() { return actionGateMinSpacingTicks; }
    public void setActionGateMinSpacingTicks(int v) {
        actionGateMinSpacingTicks = clampInt(v, 0, 10);
        com.killer560.hub.util.ActionGate.setMinSpacingTicks(actionGateMinSpacingTicks);
    }
}
