package com.killer560.hub.leveraura;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Lever Aura settings - see {@link LeverAuraFeature}. Cheat build only; ships disabled. Every getter that
 *  can make the aura click is gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} and
 *  {@link com.killer560.hub.util.SkyblockGate#allows()} (the {@code ...Raw()} getters are for tab labels). */
public final class LeverAuraConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-leveraura.json");

    public static final double MIN_RANGE = 2.1;
    // Capped at the reach measured to be accepted and unflagged - see CheatUtilsConfig.MEASURED_MAX_REACH.
    public static final double MAX_RANGE = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH;
    /** CPS slider ends (killer560, 2026-10-12: the Arrow Align Aura's "Aura CPS", for Lever Aura too). 20 is one a tick. */
    public static final int MIN_CPS = 1;
    public static final int MAX_CPS = 20;
    /** 4 CPS: the old default delay band, 150-300 ms, by the migration's own rule (round(1000 / 225)). */
    public static final int DEFAULT_CPS = 4;

    private static LeverAuraConfig instance;

    private boolean enabled = false;
    /** Lights device: flick every unlit lever (4 corners + 2 middle) while S2 isn't open yet. */
    private boolean lightsPreFlick = true;
    /** Lights device: once S1 is done, one finishing/activating flick in S2. */
    private boolean lightsFinish = true;
    /** S2's two section levers ("Not Activated" stand above) once S2 is open. */
    private boolean sectionLevers = false;
    /** ...and also before S2 is open. */
    private boolean sectionLeversEarly = false;
    private double range = MAX_RANGE;
    private int cps = DEFAULT_CPS;
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
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.lightsPreFlick = ConfigJson.getBool(o, "lightsPreFlick", cfg.lightsPreFlick);
                cfg.lightsFinish = ConfigJson.getBool(o, "lightsFinish", cfg.lightsFinish);
                cfg.sectionLevers = ConfigJson.getBool(o, "sectionLevers", cfg.sectionLevers);
                cfg.sectionLeversEarly = ConfigJson.getBool(o, "sectionLeversEarly", cfg.sectionLeversEarly);
                cfg.setRange(ConfigJson.getDouble(o, "range", cfg.range));
                if (o.has("cps")) {
                    cfg.setCps(ConfigJson.getInt(o, "cps", DEFAULT_CPS));
                } else if (o.has("minDelayMs") || o.has("maxDelayMs")) {
                    // A config or profile from before the CPS slider: the old random delay band's middle, as clicks a second.
                    int min = ConfigJson.getInt(o, "minDelayMs", 150);
                    int max = Math.max(min, ConfigJson.getInt(o, "maxDelayMs", 300));
                    cfg.setCps(cpsFromDelayMs((min + max) / 2.0));
                }
                cfg.swingHand = ConfigJson.getBool(o, "swingHand", cfg.swingHand);
                cfg.chatFeedback = ConfigJson.getBool(o, "chatFeedback", cfg.chatFeedback);
            } catch (Exception e) {
                // unreadable file (not just a bad key) - keep defaults
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("lightsPreFlick", lightsPreFlick);
            o.addProperty("lightsFinish", lightsFinish);
            o.addProperty("sectionLevers", sectionLevers);
            o.addProperty("sectionLeversEarly", sectionLeversEarly);
            o.addProperty("range", range);
            o.addProperty("cps", cps);
            o.addProperty("swingHand", swingHand);
            o.addProperty("chatFeedback", chatFeedback);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The legit jar can never report true here, even from a copied cheat-build config file. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }

    public boolean isLightsPreFlick() { return isEnabled() && lightsPreFlick; }
    public boolean isLightsPreFlickRaw() { return lightsPreFlick; }
    public void setLightsPreFlick(boolean v) { lightsPreFlick = v; }

    public boolean isLightsFinish() { return isEnabled() && lightsFinish; }
    public boolean isLightsFinishRaw() { return lightsFinish; }
    public void setLightsFinish(boolean v) { lightsFinish = v; }

    public boolean isSectionLevers() { return isEnabled() && sectionLevers; }
    public boolean isSectionLeversRaw() { return sectionLevers; }
    public void setSectionLevers(boolean v) { sectionLevers = v; }

    public boolean isSectionLeversEarly() { return isSectionLevers() && sectionLeversEarly; }
    public boolean isSectionLeversEarlyRaw() { return sectionLeversEarly; }
    public void setSectionLeversEarly(boolean v) { sectionLeversEarly = v; }

    public double getRange() { return range; }
    public void setRange(double v) { range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, Math.round(v * 10.0) / 10.0)); }

    /** Flicks per second, 1-20 (20 is one a tick, the most the mod-wide gate allows anyway). */
    public int getCps() { return cps; }
    public void setCps(int v) { cps = Math.max(MIN_CPS, Math.min(MAX_CPS, v)); }

    /** The old delay setting as CPS: round(1000 / delay), clamped to 1-20 (a 0 ms delay is 20). */
    static int cpsFromDelayMs(double delayMs) {
        if (delayMs <= 0) {
            return MAX_CPS;
        }
        return (int) Math.max(MIN_CPS, Math.min(MAX_CPS, Math.round(1000.0 / delayMs)));
    }

    public boolean isSwingHand() { return swingHand; }
    public void setSwingHand(boolean v) { swingHand = v; }

    public boolean isChatFeedback() { return chatFeedback; }
    public void setChatFeedback(boolean v) { chatFeedback = v; }
}
