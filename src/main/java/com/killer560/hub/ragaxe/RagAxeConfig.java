package com.killer560.hub.ragaxe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Rag Axe settings ({@code killer560smod-ragaxe.json}). The master and every prompt ship OFF; the
 * three alert/message options that already existed in the Dungeon Alerts "Ragnarock" section keep the defaults
 * they had there, and are migrated once out of {@code killer560smod-dungeonalerts.json} the first time this
 * file is missing (see {@link #migrateFromDungeonAlerts}), so nobody loses their old Ragnarock settings.
 * <p>
 * Reads go through {@link ConfigJson} per key, so one malformed value can never wipe the rest of the file.
 * Every GUI change calls {@link #save()} at the call site (killer560's settings-persistence rule).
 */
public final class RagAxeConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ragaxe");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-ragaxe.json");
    private static final Path OLD_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-dungeonalerts.json");

    /** Lead slider range (ms). 0 = prompt exactly on the expected moment, 10000 = a full 10 s early. */
    public static final int MAX_LEAD_MS = 10_000;
    /** Cooldown slider range (s) - base ability cooldown is 20 s, see {@link RagAxeState}. */
    public static final float MIN_COOLDOWN_S = 5f;
    public static final float MAX_COOLDOWN_S = 30f;

    private static RagAxeConfig instance;

    // --- master ---
    private boolean enabled = false;

    // --- cast / cancel / strength (migrated from the old Dungeon Alerts "Ragnarock" section) ---
    private boolean castAlert = true;
    private boolean cancelAlert = true;
    private boolean strengthMessage = true;
    private boolean announceStrength = false;

    // --- timers ---
    private boolean channelTimer = false;
    private boolean buffTimer = true;
    private boolean cooldownTimer = false;
    private boolean endAlert = true;
    private boolean readyAlert = false;
    private float cooldownSeconds = 20f;
    /** false (default): the detected cast sound is the START of the 3 s channel. See {@link RagAxeState}. */
    private boolean soundIsBuffStart = false;

    // --- prompts ---
    private String promptText = "rag";
    private boolean promptTitle = false;
    private boolean skipTankHealer = true;
    private final boolean[] promptEnabled = new boolean[RagAxePrompt.values().length];
    private final int[] promptLeadMs = new int[RagAxePrompt.values().length];
    private final boolean[] promptSound = new boolean[RagAxePrompt.values().length];

    private RagAxeConfig() {
        for (int i = 0; i < promptLeadMs.length; i++) {
            promptLeadMs[i] = (int) RagAxeState.CHANNEL_MS;
        }
    }

    public static RagAxeConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        RagAxeConfig cfg = new RagAxeConfig();
        if (Files.exists(CONFIG_PATH)) {
            JsonObject o = read(CONFIG_PATH);
            if (o != null) {
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.castAlert = ConfigJson.getBool(o, "castAlert", cfg.castAlert);
                cfg.cancelAlert = ConfigJson.getBool(o, "cancelAlert", cfg.cancelAlert);
                cfg.strengthMessage = ConfigJson.getBool(o, "strengthMessage", cfg.strengthMessage);
                cfg.announceStrength = ConfigJson.getBool(o, "announceStrength", cfg.announceStrength);
                cfg.channelTimer = ConfigJson.getBool(o, "channelTimer", cfg.channelTimer);
                cfg.buffTimer = ConfigJson.getBool(o, "buffTimer", cfg.buffTimer);
                cfg.cooldownTimer = ConfigJson.getBool(o, "cooldownTimer", cfg.cooldownTimer);
                cfg.endAlert = ConfigJson.getBool(o, "endAlert", cfg.endAlert);
                cfg.readyAlert = ConfigJson.getBool(o, "readyAlert", cfg.readyAlert);
                cfg.cooldownSeconds = clamp(ConfigJson.getFloat(o, "cooldownSeconds", cfg.cooldownSeconds),
                        MIN_COOLDOWN_S, MAX_COOLDOWN_S);
                cfg.soundIsBuffStart = ConfigJson.getBool(o, "soundIsBuffStart", cfg.soundIsBuffStart);
                cfg.promptText = ConfigJson.getString(o, "promptText", cfg.promptText);
                cfg.promptTitle = ConfigJson.getBool(o, "promptTitle", cfg.promptTitle);
                cfg.skipTankHealer = ConfigJson.getBool(o, "skipTankHealer", cfg.skipTankHealer);
                for (RagAxePrompt p : RagAxePrompt.values()) {
                    int i = p.ordinal();
                    cfg.promptEnabled[i] = ConfigJson.getBool(o, "prompt_" + p.key + "_enabled", cfg.promptEnabled[i]);
                    cfg.promptLeadMs[i] = clampLead(ConfigJson.getInt(o, "prompt_" + p.key + "_leadMs", cfg.promptLeadMs[i]));
                    cfg.promptSound[i] = ConfigJson.getBool(o, "prompt_" + p.key + "_sound", cfg.promptSound[i]);
                }
            }
            instance = cfg;
        } else {
            boolean migrated = cfg.migrateFromDungeonAlerts();
            instance = cfg;
            if (migrated) {
                // Write straight away, so this really is a ONE-off. Otherwise the migration would re-run on every
                // launch until the user happens to touch the Rag Axe tab - and, worse, DungeonAlertsConfig.save()
                // no longer writes the rag* keys, so the first time any Dungeon Alerts setting is changed the
                // migration source is gone and the old Ragnarock settings would be silently lost.
                cfg.save();
            }
        }
    }

    /** One-off: pick up the old {@code rag*} keys from the Dungeon Alerts config file (that section moved here
     *  in this batch). The old file is left untouched - its own loader simply stops reading those keys.
     *  @return true if the old file existed and was read (the caller then persists the result immediately). */
    private boolean migrateFromDungeonAlerts() {
        JsonObject o = read(OLD_PATH);
        if (o == null) {
            return false;
        }
        enabled = ConfigJson.getBool(o, "ragEnabled", enabled);
        castAlert = ConfigJson.getBool(o, "ragCastAlert", castAlert);
        cancelAlert = ConfigJson.getBool(o, "ragCancelAlert", cancelAlert);
        strengthMessage = ConfigJson.getBool(o, "ragStrengthMessage", strengthMessage);
        announceStrength = ConfigJson.getBool(o, "ragAnnounceStrength", announceStrength);
        buffTimer = ConfigJson.getBool(o, "ragBuffTimer", buffTimer);
        endAlert = ConfigJson.getBool(o, "ragEndAlert", endAlert);
        promptEnabled[RagAxePrompt.M7_DRAGONS.ordinal()] = ConfigJson.getBool(o, "ragM7Alert", false);
        LOGGER.info("[RagAxe] Migrated Ragnarock settings from the Dungeon Alerts config (enabled={})", enabled);
        return true;
    }

    private static JsonObject read(Path path) {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("[RagAxe] Failed to read {}, using defaults", path.getFileName(), e);
            return null;
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("castAlert", castAlert);
            o.addProperty("cancelAlert", cancelAlert);
            o.addProperty("strengthMessage", strengthMessage);
            o.addProperty("announceStrength", announceStrength);
            o.addProperty("channelTimer", channelTimer);
            o.addProperty("buffTimer", buffTimer);
            o.addProperty("cooldownTimer", cooldownTimer);
            o.addProperty("endAlert", endAlert);
            o.addProperty("readyAlert", readyAlert);
            o.addProperty("cooldownSeconds", cooldownSeconds);
            o.addProperty("soundIsBuffStart", soundIsBuffStart);
            o.addProperty("promptText", promptText);
            o.addProperty("promptTitle", promptTitle);
            o.addProperty("skipTankHealer", skipTankHealer);
            for (RagAxePrompt p : RagAxePrompt.values()) {
                int i = p.ordinal();
                o.addProperty("prompt_" + p.key + "_enabled", promptEnabled[i]);
                o.addProperty("prompt_" + p.key + "_leadMs", promptLeadMs[i]);
                o.addProperty("prompt_" + p.key + "_sound", promptSound[i]);
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[RagAxe] Failed to save config", e);
        }
    }

    // ------------------------------------------------------------------ accessors

    /** Master. Skyblock Only gate lives here, so every sub-setting inherits it. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isCastAlert() {
        return castAlert;
    }

    public void setCastAlert(boolean v) {
        castAlert = v;
    }

    public boolean isCancelAlert() {
        return cancelAlert;
    }

    public void setCancelAlert(boolean v) {
        cancelAlert = v;
    }

    public boolean isStrengthMessage() {
        return strengthMessage;
    }

    public void setStrengthMessage(boolean v) {
        strengthMessage = v;
    }

    public boolean isAnnounceStrength() {
        return announceStrength;
    }

    public void setAnnounceStrength(boolean v) {
        announceStrength = v;
    }

    public boolean isChannelTimer() {
        return channelTimer;
    }

    public void setChannelTimer(boolean v) {
        channelTimer = v;
    }

    public boolean isBuffTimer() {
        return buffTimer;
    }

    public void setBuffTimer(boolean v) {
        buffTimer = v;
    }

    public boolean isCooldownTimer() {
        return cooldownTimer;
    }

    public void setCooldownTimer(boolean v) {
        cooldownTimer = v;
    }

    public boolean isEndAlert() {
        return endAlert;
    }

    public void setEndAlert(boolean v) {
        endAlert = v;
    }

    public boolean isReadyAlert() {
        return readyAlert;
    }

    public void setReadyAlert(boolean v) {
        readyAlert = v;
    }

    public float getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(float v) {
        cooldownSeconds = clamp(v, MIN_COOLDOWN_S, MAX_COOLDOWN_S);
    }

    public boolean isSoundIsBuffStart() {
        return soundIsBuffStart;
    }

    public void setSoundIsBuffStart(boolean v) {
        soundIsBuffStart = v;
    }

    public String getPromptText() {
        return promptText == null || promptText.isEmpty() ? "rag" : promptText;
    }

    public void setPromptText(String v) {
        promptText = v == null ? "" : v;
    }

    public boolean isPromptTitle() {
        return promptTitle;
    }

    public void setPromptTitle(boolean v) {
        promptTitle = v;
    }

    public boolean isSkipTankHealer() {
        return skipTankHealer;
    }

    public void setSkipTankHealer(boolean v) {
        skipTankHealer = v;
    }

    public boolean isPromptEnabled(RagAxePrompt p) {
        return promptEnabled[p.ordinal()];
    }

    public void setPromptEnabled(RagAxePrompt p, boolean v) {
        promptEnabled[p.ordinal()] = v;
    }

    public int getPromptLeadMs(RagAxePrompt p) {
        return promptLeadMs[p.ordinal()];
    }

    public void setPromptLeadMs(RagAxePrompt p, int v) {
        promptLeadMs[p.ordinal()] = clampLead(v);
    }

    public boolean isPromptSound(RagAxePrompt p) {
        return promptSound[p.ordinal()];
    }

    public void setPromptSound(RagAxePrompt p, boolean v) {
        promptSound[p.ordinal()] = v;
    }

    private static int clampLead(int v) {
        return Math.max(0, Math.min(MAX_LEAD_MS, v));
    }

    private static float clamp(float v, float min, float max) {
        return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : min;
    }
}
