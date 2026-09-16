package com.killer560.hub.terminals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Terminal QoL settings - see {@link TerminalQolFeature}. Ported from Devonian's own
 *  {@code features/dungeons/f7/{TerminalProtection, TerminalDropKey, MelodyKeys, CustomTerminalScale,
 *  TerminalHideCompletion}.kt} (source read 2026-09-16 at a local Devonian checkout).
 *  <p>
 *  Deliberately a SEPARATE file from {@link TerminalSolverConfig} ({@code killer560smod-terminalsolver.json})
 *  rather than more keys inside it: none of these five settings touch the solver's own highlighting at all -
 *  they work whether the solver is on or off - and the solver config had just been reworked for custom overlay
 *  colours, so folding an unrelated feature pack into it would have made that diff unreadable. Same
 *  per-key {@code ConfigJson} read + {@code save()}-on-every-change pattern, so every setting here survives
 *  a Minecraft restart like every other setting in this mod.
 *  <p>
 *  Everything ships OFF, per killer560's standing "all default OFF" rule for new features. */
public final class TerminalQolConfig {

    /** Devonian's own slider bounds for its {@code terminalProtection.threshold} (100-700, default 400). */
    public static final int MIN_PROTECTION_MS = 100;
    public static final int MAX_PROTECTION_MS = 700;
    public static final int DEFAULT_PROTECTION_MS = 400;

    /** 0 = "auto" (leave Minecraft's own GUI scale alone), matching Devonian's own {@code 0 = auto} sliders. */
    public static final int MIN_GUI_SCALE = 0;
    public static final int MAX_GUI_SCALE = 5;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-terminalqol.json");

    private static TerminalQolConfig instance;

    // ---- Terminal Protection ----
    private boolean protectionEnabled = false;
    private int protectionThresholdMs = DEFAULT_PROTECTION_MS;
    /** Devonian's slider tooltip is literally "lowest current safe value is 400 - ping" - LegendaryJG, i.e. the
     *  real threshold people use is the slider MINUS their ping. Doing that subtraction here instead of making
     *  the player re-tune the slider every time their ping changes. */
    private boolean protectionSubtractPing = true;

    // ---- Drop Key ----
    private boolean dropKeyEnabled = false;
    /** GLFW key code the vanilla Drop key is temporarily re-bound to while a terminal is open.
     *  -1 ({@code GLFW_KEY_UNKNOWN}) = unbound, i.e. nothing can drop an item while a terminal is open, which is
     *  the whole point of the feature (Devonian's own default is {@code GLFW_KEY_UNKNOWN} too). */
    private int dropKeyCode = -1;
    /** Crash recovery, see {@link TerminalQolFeature#restoreDropKeyAfterCrash()}. Non-empty ONLY while the
     *  vanilla Drop key is currently swapped out; holds the real key's {@code InputConstants.Key#getName()}
     *  so a crash (or an /exit) with a terminal open can't leave the player permanently unable to drop items. */
    private String savedDropKeyName = "";

    // ---- Melody Keys ----
    private boolean melodyKeysEnabled = false;

    // ---- Custom Terminal Scale ----
    private int terminalGuiScale = 0;
    private int melodyGuiScale = 0;

    // ---- Hide Completion ----
    private boolean hideCompletionTitles = false;
    private boolean hideCompletionChat = false;
    /** Devonian's own {@code onlyShowOwn} (default true): your OWN "activated a terminal! (n/m)" still shows,
     *  everyone else's is hidden. */
    private boolean hideCompletionOnlyOthers = true;

    private TerminalQolConfig() {
    }

    public static TerminalQolConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TerminalQolConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            TerminalQolConfig cfg = new TerminalQolConfig();
            cfg.protectionEnabled = ConfigJson.getBool(obj, "protectionEnabled", false);
            cfg.protectionThresholdMs = clampThreshold(ConfigJson.getInt(obj, "protectionThresholdMs", DEFAULT_PROTECTION_MS));
            cfg.protectionSubtractPing = ConfigJson.getBool(obj, "protectionSubtractPing", true);
            cfg.dropKeyEnabled = ConfigJson.getBool(obj, "dropKeyEnabled", false);
            cfg.dropKeyCode = ConfigJson.getInt(obj, "dropKeyCode", -1);
            cfg.savedDropKeyName = ConfigJson.getString(obj, "savedDropKeyName", "");
            cfg.melodyKeysEnabled = ConfigJson.getBool(obj, "melodyKeysEnabled", false);
            cfg.terminalGuiScale = clampGuiScale(ConfigJson.getInt(obj, "terminalGuiScale", 0));
            cfg.melodyGuiScale = clampGuiScale(ConfigJson.getInt(obj, "melodyGuiScale", 0));
            cfg.hideCompletionTitles = ConfigJson.getBool(obj, "hideCompletionTitles", false);
            cfg.hideCompletionChat = ConfigJson.getBool(obj, "hideCompletionChat", false);
            cfg.hideCompletionOnlyOthers = ConfigJson.getBool(obj, "hideCompletionOnlyOthers", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new TerminalQolConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("protectionEnabled", protectionEnabled);
            obj.addProperty("protectionThresholdMs", protectionThresholdMs);
            obj.addProperty("protectionSubtractPing", protectionSubtractPing);
            obj.addProperty("dropKeyEnabled", dropKeyEnabled);
            obj.addProperty("dropKeyCode", dropKeyCode);
            obj.addProperty("savedDropKeyName", savedDropKeyName);
            obj.addProperty("melodyKeysEnabled", melodyKeysEnabled);
            obj.addProperty("terminalGuiScale", terminalGuiScale);
            obj.addProperty("melodyGuiScale", melodyGuiScale);
            obj.addProperty("hideCompletionTitles", hideCompletionTitles);
            obj.addProperty("hideCompletionChat", hideCompletionChat);
            obj.addProperty("hideCompletionOnlyOthers", hideCompletionOnlyOthers);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clampThreshold(int value) {
        return Math.max(MIN_PROTECTION_MS, Math.min(MAX_PROTECTION_MS, value));
    }

    private static int clampGuiScale(int value) {
        return Math.max(MIN_GUI_SCALE, Math.min(MAX_GUI_SCALE, value));
    }

    // ------------------------------------------------------------------ Terminal Protection

    public boolean isProtectionEnabled() {
        return protectionEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** Raw saved value, ignoring the Skyblock gate - for the settings GUI's own button label. */
    public boolean isProtectionEnabledRaw() {
        return protectionEnabled;
    }

    public void setProtectionEnabled(boolean protectionEnabled) {
        this.protectionEnabled = protectionEnabled;
    }

    public int getProtectionThresholdMs() {
        return protectionThresholdMs;
    }

    public void setProtectionThresholdMs(int protectionThresholdMs) {
        this.protectionThresholdMs = clampThreshold(protectionThresholdMs);
    }

    public boolean isProtectionSubtractPing() {
        return protectionSubtractPing;
    }

    public void setProtectionSubtractPing(boolean protectionSubtractPing) {
        this.protectionSubtractPing = protectionSubtractPing;
    }

    // ------------------------------------------------------------------ Drop Key

    public boolean isDropKeyEnabled() {
        return dropKeyEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isDropKeyEnabledRaw() {
        return dropKeyEnabled;
    }

    public void setDropKeyEnabled(boolean dropKeyEnabled) {
        this.dropKeyEnabled = dropKeyEnabled;
    }

    public int getDropKeyCode() {
        return dropKeyCode;
    }

    public void setDropKeyCode(int dropKeyCode) {
        this.dropKeyCode = dropKeyCode;
    }

    public String getSavedDropKeyName() {
        return savedDropKeyName == null ? "" : savedDropKeyName;
    }

    public void setSavedDropKeyName(String savedDropKeyName) {
        this.savedDropKeyName = savedDropKeyName == null ? "" : savedDropKeyName;
    }

    // ------------------------------------------------------------------ Melody Keys

    public boolean isMelodyKeysEnabled() {
        return melodyKeysEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isMelodyKeysEnabledRaw() {
        return melodyKeysEnabled;
    }

    public void setMelodyKeysEnabled(boolean melodyKeysEnabled) {
        this.melodyKeysEnabled = melodyKeysEnabled;
    }

    // ------------------------------------------------------------------ Custom Terminal Scale

    /** @return 0 when the scale override is off, or when the Skyblock gate says we aren't on Skyblock/p3sim. */
    public int getTerminalGuiScale() {
        return com.killer560.hub.util.SkyblockGate.allows() ? terminalGuiScale : 0;
    }

    public int getTerminalGuiScaleRaw() {
        return terminalGuiScale;
    }

    public void setTerminalGuiScale(int terminalGuiScale) {
        this.terminalGuiScale = clampGuiScale(terminalGuiScale);
    }

    public int getMelodyGuiScale() {
        return com.killer560.hub.util.SkyblockGate.allows() ? melodyGuiScale : 0;
    }

    public int getMelodyGuiScaleRaw() {
        return melodyGuiScale;
    }

    public void setMelodyGuiScale(int melodyGuiScale) {
        this.melodyGuiScale = clampGuiScale(melodyGuiScale);
    }

    // ------------------------------------------------------------------ Hide Completion

    public boolean isHideCompletionTitles() {
        return hideCompletionTitles && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isHideCompletionTitlesRaw() {
        return hideCompletionTitles;
    }

    public void setHideCompletionTitles(boolean hideCompletionTitles) {
        this.hideCompletionTitles = hideCompletionTitles;
    }

    public boolean isHideCompletionChat() {
        return hideCompletionChat && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isHideCompletionChatRaw() {
        return hideCompletionChat;
    }

    public void setHideCompletionChat(boolean hideCompletionChat) {
        this.hideCompletionChat = hideCompletionChat;
    }

    public boolean isHideCompletionOnlyOthers() {
        return hideCompletionOnlyOthers;
    }

    public void setHideCompletionOnlyOthers(boolean hideCompletionOnlyOthers) {
        this.hideCompletionOnlyOthers = hideCompletionOnlyOthers;
    }
}
