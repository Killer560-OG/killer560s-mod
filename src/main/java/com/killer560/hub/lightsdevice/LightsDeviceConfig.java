package com.killer560.hub.lightsdevice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Lights Device settings - see {@link LightsDeviceFeature}. Cheat build only; ships with both halves off.
 * Every getter that can make it click is gated on {@link BuildVariant#CHEAT_FEATURES_ENABLED} and
 * {@link SkyblockGate#allows()} (the {@code ...Raw()} getters are for the tab's labels).
 * <p>
 * Two independent switches, no switch over the whole (killer560, 2026-10-15: "Make lights have its own aura trigger and
 * own triggerbot"): the <b>Aura</b> (flicks the device's levers in range, with its own range, CPS, swing and chat, and
 * the Pre-Flick / Finish options) and the <b>Triggerbot</b> (flicks the lever under the crosshair, with its own delay,
 * cooldown and swing). Nothing here is shared with Secret Aura or In Boss.
 * <p>
 * Migration: until 2026-10-15 the Lights options were In Boss's (the old Lever Aura's, {@code killer560smod-leveraura.json}
 * keys {@code lightsPreFlick} / {@code lightsFinish}). {@code LeverAuraConfig.load} hands them over once through
 * {@link #migrateFromLeverAura}: the Aura is ON when In Boss was on with either Lights option on, and it takes the range,
 * CPS, swing and chat settings those flicks ran with.
 */
public final class LightsDeviceConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-lightsdevice.json");

    public static final double MIN_RANGE = 2.1;
    /** Capped at the reach measured to be accepted and unflagged - see CheatUtilsConfig.MEASURED_MAX_REACH. */
    public static final double MAX_RANGE = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH;
    public static final int MIN_CPS = 1;
    /** One flick a tick, the most the mod-wide gate allows anyway. */
    public static final int MAX_CPS = 20;
    public static final int DEFAULT_CPS = 4;
    public static final int MAX_TRIGGER_DELAY_MS = 500;
    public static final int MAX_TRIGGER_COOLDOWN_MS = 2000;

    private static LightsDeviceConfig instance;

    // ---- Aura ----
    private boolean auraEnabled = false;
    /** Flick every unlit lever (4 corners + 2 middle) while S2 isn't open yet. */
    private boolean preFlick = true;
    /** Once S1 is done, finish (or, all lit, activate) the device with one flick in S2. */
    private boolean finish = true;
    private double range = MAX_RANGE;
    private int cps = DEFAULT_CPS;
    private boolean auraSwingHand = true;
    private boolean chatFeedback = true;

    // ---- Triggerbot ----
    private boolean triggerEnabled = false;
    private int triggerDelayMs = 0;
    private int triggerCooldownMs = 250;
    private boolean triggerSwingHand = true;

    private LightsDeviceConfig() {
    }

    public static LightsDeviceConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LightsDeviceConfig cfg = new LightsDeviceConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.auraEnabled = ConfigJson.getBool(o, "auraEnabled", cfg.auraEnabled);
                cfg.preFlick = ConfigJson.getBool(o, "preFlick", cfg.preFlick);
                cfg.finish = ConfigJson.getBool(o, "finish", cfg.finish);
                cfg.setRange(ConfigJson.getDouble(o, "range", cfg.range));
                cfg.setCps(ConfigJson.getInt(o, "cps", cfg.cps));
                cfg.auraSwingHand = ConfigJson.getBool(o, "auraSwingHand", cfg.auraSwingHand);
                cfg.chatFeedback = ConfigJson.getBool(o, "chatFeedback", cfg.chatFeedback);
                cfg.triggerEnabled = ConfigJson.getBool(o, "triggerEnabled", cfg.triggerEnabled);
                cfg.setTriggerDelayMs(ConfigJson.getInt(o, "triggerDelayMs", cfg.triggerDelayMs));
                cfg.setTriggerCooldownMs(ConfigJson.getInt(o, "triggerCooldownMs", cfg.triggerCooldownMs));
                cfg.triggerSwingHand = ConfigJson.getBool(o, "triggerSwingHand", cfg.triggerSwingHand);
            } catch (Exception e) {
                // unreadable file (not just a bad key) - keep defaults
                cfg = new LightsDeviceConfig();
            }
        }
        instance = cfg;
    }

    /**
     * The Lights options of an In Boss (Lever Aura) file from before 2026-10-15, moved here once. The Aura is switched ON
     * only when In Boss was on and at least one Lights option was - exactly when the old code flicked the device - and
     * never switched off by a migration. Saved at once.
     */
    public static void migrateFromLeverAura(boolean preFlick, boolean finish, boolean inBossOn, double range, int cps,
                                            boolean swingHand, boolean chatFeedback) {
        LightsDeviceConfig cfg = getInstance();
        cfg.preFlick = preFlick;
        cfg.finish = finish;
        if (inBossOn && (preFlick || finish)) {
            cfg.auraEnabled = true;
        }
        cfg.setRange(range);
        cfg.setCps(cps);
        cfg.auraSwingHand = swingHand;
        cfg.chatFeedback = chatFeedback;
        cfg.save();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("auraEnabled", auraEnabled);
            o.addProperty("preFlick", preFlick);
            o.addProperty("finish", finish);
            o.addProperty("range", range);
            o.addProperty("cps", cps);
            o.addProperty("auraSwingHand", auraSwingHand);
            o.addProperty("chatFeedback", chatFeedback);
            o.addProperty("triggerEnabled", triggerEnabled);
            o.addProperty("triggerDelayMs", triggerDelayMs);
            o.addProperty("triggerCooldownMs", triggerCooldownMs);
            o.addProperty("triggerSwingHand", triggerSwingHand);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static boolean allowed() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && SkyblockGate.allows();
    }

    // ---- Aura ----

    /** The legit jar can never report true here, even from a copied cheat-build config file. */
    public boolean isAuraEnabled() { return auraEnabled && allowed(); }
    public boolean isAuraEnabledRaw() { return auraEnabled; }
    public void setAuraEnabled(boolean v) { auraEnabled = v; }

    public boolean isPreFlick() { return preFlick; }
    public void setPreFlick(boolean v) { preFlick = v; }

    public boolean isFinish() { return finish; }
    public void setFinish(boolean v) { finish = v; }

    public double getRange() { return range; }
    public void setRange(double v) { range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, Math.round(v * 10.0) / 10.0)); }

    /** Flicks per second, 1-20. */
    public int getCps() { return cps; }
    public void setCps(int v) { cps = Math.max(MIN_CPS, Math.min(MAX_CPS, v)); }

    public boolean isAuraSwingHand() { return auraSwingHand; }
    public void setAuraSwingHand(boolean v) { auraSwingHand = v; }

    public boolean isChatFeedback() { return chatFeedback; }
    public void setChatFeedback(boolean v) { chatFeedback = v; }

    // ---- Triggerbot ----

    public boolean isTriggerEnabled() { return triggerEnabled && allowed(); }
    public boolean isTriggerEnabledRaw() { return triggerEnabled; }
    public void setTriggerEnabled(boolean v) { triggerEnabled = v; }

    /** How long the crosshair must stay on a lever before the flick, 0-500 ms (rounded up to whole ticks). */
    public int getTriggerDelayMs() { return triggerDelayMs; }
    public void setTriggerDelayMs(int ms) { triggerDelayMs = Math.max(0, Math.min(MAX_TRIGGER_DELAY_MS, ms)); }

    /** The least time between two triggerbot flicks, 0-2000 ms. */
    public int getTriggerCooldownMs() { return triggerCooldownMs; }
    public void setTriggerCooldownMs(int ms) { triggerCooldownMs = Math.max(0, Math.min(MAX_TRIGGER_COOLDOWN_MS, ms)); }

    public boolean isTriggerSwingHand() { return triggerSwingHand; }
    public void setTriggerSwingHand(boolean v) { triggerSwingHand = v; }
}
