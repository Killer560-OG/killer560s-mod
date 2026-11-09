package com.killer560.hub.leveraura;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.nio.file.Path;

/** Persisted Lever Aura settings - see {@link LeverAuraFeature}. Cheat build only; ships disabled. Every getter that
 *  can make the aura click is gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} and
 *  {@link com.killer560.hub.util.SkyblockGate#allows()} (the {@code ...Raw()} getters are for tab labels).
 *  <p>
 *  Since 2026-10-15 the Lever Aura is the "In Boss" part of the Secret Aura tab: its on/off switch is Secret Aura's
 *  {@code auraBossLevers} ({@link com.killer560.hub.cheatutils.CheatUtilsConfig#isAuraBossLevers}), no longer a key of
 *  this file. A file that still has {@code "enabled": true} turns In Boss on when it loads, then is rewritten without
 *  the key ({@link #load}).
 *  <p>
 *  Since the same day the Lights device is its own feature ({@code lightsdevice.LightsDeviceConfig}): a file that still
 *  has {@code lightsPreFlick} / {@code lightsFinish} hands them (with the range, CPS, swing and chat settings they ran
 *  with, and whether In Boss was on) to {@code LightsDeviceConfig#migrateFromLeverAura} when it loads, then is rewritten
 *  without them. What is left here is S2's section levers and their range, CPS, swing and chat. */
public final class LeverAuraConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-leveraura.json");

    public static final double MIN_RANGE = 2.1;
    // Capped at the reach measured to be accepted and unflagged - see CheatUtilsConfig.MEASURED_MAX_REACH.
    public static final double MAX_RANGE = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH;
    /** Lever cooldown slider ends in ms (killer560, 2026-11-09, replacing the Lever CPS): 50 is one flick a tick (the old 20 CPS). */
    public static final int MIN_COOLDOWN_MS = 50;
    public static final int MAX_COOLDOWN_MS = 1000;
    /** 250 ms: the old default of 4 CPS. */
    public static final int DEFAULT_COOLDOWN_MS = 250;
    private static final int DEFAULT_CPS = 4;

    private static LeverAuraConfig instance;

    /** S2's two section levers ("Not Activated" stand above) once S2 is open. */
    private boolean sectionLevers = false;
    /** ...and also before S2 is open. */
    private boolean sectionLeversEarly = false;
    private double range = MAX_RANGE;
    private int cooldownMs = DEFAULT_COOLDOWN_MS;
    private boolean swingHand = true;
    private boolean chatFeedback = true;

    private LeverAuraConfig() {
    }

    public static LeverAuraConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LeverAuraConfig cfg = new LeverAuraConfig();
        boolean migrateEnabled = false;
        boolean migrateLights = false;
        JsonObject o = ConfigJson.read(CONFIG_PATH);
        if (o != null) {
            // A file from before the merge into Secret Aura: its switch becomes In Boss (never turns it off).
            migrateEnabled = o.has("enabled");
            if (migrateEnabled && ConfigJson.getBool(o, "enabled", false)) {
                com.killer560.hub.cheatutils.CheatUtilsConfig.getInstance().setAuraBossLevers(true);
                com.killer560.hub.cheatutils.CheatUtilsConfig.getInstance().save();
            }
            cfg.sectionLevers = ConfigJson.getBool(o, "sectionLevers", cfg.sectionLevers);
            cfg.sectionLeversEarly = ConfigJson.getBool(o, "sectionLeversEarly", cfg.sectionLeversEarly);
            cfg.setRange(ConfigJson.getDouble(o, "range", cfg.range));
            if (o.has("cooldownMs")) {
                cfg.setCooldownMs(ConfigJson.getInt(o, "cooldownMs", DEFAULT_COOLDOWN_MS));
            } else if (o.has("cps")) { // the Lever CPS of before 2026-11-09: cooldown = 1000 / cps
                cfg.setCooldownMs((int) Math.round(1000.0 / Math.max(1, ConfigJson.getInt(o, "cps", DEFAULT_CPS))));
            } else if (o.has("minDelayMs") || o.has("maxDelayMs")) {
                // A config or profile from before the CPS slider: the old random delay band's middle, as clicks a second.
                int min = ConfigJson.getInt(o, "minDelayMs", 150);
                int max = Math.max(min, ConfigJson.getInt(o, "maxDelayMs", 300));
                cfg.setCooldownMs((min + max) / 2);
            }
            cfg.swingHand = ConfigJson.getBool(o, "swingHand", cfg.swingHand);
            cfg.chatFeedback = ConfigJson.getBool(o, "chatFeedback", cfg.chatFeedback);
            // A file from before the Lights Device was its own feature: its Lights options, and the In Boss switch
            // they ran under (already migrated above when the file is older still), move there once.
            migrateLights = o.has("lightsPreFlick") || o.has("lightsFinish");
            if (migrateLights) {
                com.killer560.hub.lightsdevice.LightsDeviceConfig.migrateFromLeverAura(
                        ConfigJson.getBool(o, "lightsPreFlick", true), ConfigJson.getBool(o, "lightsFinish", true),
                        com.killer560.hub.cheatutils.CheatUtilsConfig.getInstance().isAuraBossLevers(),
                        cfg.range, (int) Math.round(1000.0 / cfg.cooldownMs), cfg.swingHand, cfg.chatFeedback);
            }
        }
        instance = cfg;
        if (migrateEnabled || migrateLights) {
            cfg.save(); // drops the old "enabled" and Lights keys, so each is migrated once
        }
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("sectionLevers", sectionLevers);
        o.addProperty("sectionLeversEarly", sectionLeversEarly);
        o.addProperty("range", range);
        o.addProperty("cooldownMs", cooldownMs);
        o.addProperty("swingHand", swingHand);
        o.addProperty("chatFeedback", chatFeedback);
        ConfigJson.write(CONFIG_PATH, o);
    }

    /** The legit jar can never report true here, even from a copied cheat-build config file. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && isEnabledRaw() && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** The In Boss switch of the Secret Aura tab. */
    public boolean isEnabledRaw() { return com.killer560.hub.cheatutils.CheatUtilsConfig.getInstance().isAuraBossLevers(); }
    public void setEnabled(boolean v) { com.killer560.hub.cheatutils.CheatUtilsConfig.getInstance().setAuraBossLevers(v); }

    public boolean isSectionLevers() { return isEnabled() && sectionLevers; }
    public boolean isSectionLeversRaw() { return sectionLevers; }
    public void setSectionLevers(boolean v) { sectionLevers = v; }

    public boolean isSectionLeversEarly() { return isSectionLevers() && sectionLeversEarly; }
    public boolean isSectionLeversEarlyRaw() { return sectionLeversEarly; }
    public void setSectionLeversEarly(boolean v) { sectionLeversEarly = v; }

    public double getRange() { return range; }
    public void setRange(double v) { range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, Math.round(v * 10.0) / 10.0)); }

    /** Cooldown between flicks in ms, 50-1000 (50 is one a tick, the most the mod-wide gate allows anyway). */
    public int getCooldownMs() { return cooldownMs; }
    public void setCooldownMs(int v) { cooldownMs = Math.max(MIN_COOLDOWN_MS, Math.min(MAX_COOLDOWN_MS, v)); }

    public boolean isSwingHand() { return swingHand; }
    public void setSwingHand(boolean v) { swingHand = v; }

    public boolean isChatFeedback() { return chatFeedback; }
    public void setChatFeedback(boolean v) { chatFeedback = v; }
}
