package com.killer560.hub.leveraura;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Lever Aura settings - see {@link LeverAuraFeature}. Cheat build only; ships disabled. Every getter that
 *  can make the aura click is gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} and
 *  {@link com.killer560.hub.util.SkyblockGate#allows()} (the {@code ...Raw()} getters are for tab labels). */
public final class LeverAuraConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-leveraura.json");

    public static final double MIN_RANGE = 2.1;
    public static final double MAX_RANGE = 6.5;
    public static final int MIN_DELAY_MS = 50;
    public static final int MAX_DELAY_MS = 2000;

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
    private double range = 5.0;
    private int minDelayMs = 150;
    private int maxDelayMs = 300;
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
                cfg.setMinDelayMs(ConfigJson.getInt(o, "minDelayMs", cfg.minDelayMs));
                cfg.setMaxDelayMs(ConfigJson.getInt(o, "maxDelayMs", cfg.maxDelayMs));
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
            o.addProperty("minDelayMs", minDelayMs);
            o.addProperty("maxDelayMs", maxDelayMs);
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

    public int getMinDelayMs() { return minDelayMs; }
    public void setMinDelayMs(int v) {
        minDelayMs = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, v));
        if (maxDelayMs < minDelayMs) {
            maxDelayMs = minDelayMs;
        }
    }

    public int getMaxDelayMs() { return maxDelayMs; }
    public void setMaxDelayMs(int v) {
        maxDelayMs = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, v));
        if (minDelayMs > maxDelayMs) {
            minDelayMs = maxDelayMs;
        }
    }

    public boolean isSwingHand() { return swingHand; }
    public void setSwingHand(boolean v) { swingHand = v; }

    public boolean isChatFeedback() { return chatFeedback; }
    public void setChatFeedback(boolean v) { chatFeedback = v; }
}
