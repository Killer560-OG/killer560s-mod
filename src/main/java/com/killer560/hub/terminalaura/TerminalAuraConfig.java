package com.killer560.hub.terminalaura;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Terminal Aura settings - see {@link TerminalAuraFeature}. Cheat build only; ships disabled.
 *  {@link #isEnabled()} is gated on {@link BuildVariant#CHEAT_FEATURES_ENABLED} and
 *  {@link SkyblockGate#allows()}; {@link #isEnabledRaw()} is the un-gated value, for the tab's own label. */
public final class TerminalAuraConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-terminalaura.json");

    /** Hypixel's own interact reach. Anything past this is refused server-side anyway. */
    public static final double MAX_RANGE = 4.0;
    public static final int MAX_DELAY_MS = 2000;

    private static TerminalAuraConfig instance;

    private boolean enabled = false;
    private double range = 4.0;
    private int delayMs = 750;
    private boolean groundOnly = false;
    private boolean leapDelayEnabled = false;
    private double leapDelaySeconds = 0.5;

    private TerminalAuraConfig() {
    }

    public static TerminalAuraConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        TerminalAuraConfig cfg = new TerminalAuraConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(root, "enabled", false);
                cfg.range = ConfigJson.getDouble(root, "range", 4.0);
                cfg.delayMs = ConfigJson.getInt(root, "delayMs", 750);
                cfg.groundOnly = ConfigJson.getBool(root, "groundOnly", false);
                cfg.leapDelayEnabled = ConfigJson.getBool(root, "leapDelayEnabled", false);
                cfg.leapDelaySeconds = ConfigJson.getDouble(root, "leapDelaySeconds", 0.5);
            } catch (Exception ignored) {
                // Unreadable file: the per-key readers keep whatever parsed, the rest stay at defaults.
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("range", range);
            root.addProperty("delayMs", delayMs);
            root.addProperty("groundOnly", groundOnly);
            root.addProperty("leapDelayEnabled", leapDelayEnabled);
            root.addProperty("leapDelaySeconds", leapDelaySeconds);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The gate the feature itself checks - false on the legit jar and outside Skyblock, always. */
    public boolean isEnabled() {
        return enabled && BuildVariant.CHEAT_FEATURES_ENABLED && SkyblockGate.allows();
    }

    /** The raw stored value, for drawing the toggle's own ON/OFF label. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getRange() {
        return Math.min(MAX_RANGE, Math.max(0.0, range));
    }

    public void setRange(double range) {
        this.range = range;
    }

    public int getDelayMs() {
        return Math.min(MAX_DELAY_MS, Math.max(0, delayMs));
    }

    public void setDelayMs(int delayMs) {
        this.delayMs = delayMs;
    }

    public boolean isGroundOnly() {
        return groundOnly;
    }

    public void setGroundOnly(boolean groundOnly) {
        this.groundOnly = groundOnly;
    }

    public boolean isLeapDelayEnabled() {
        return leapDelayEnabled;
    }

    public void setLeapDelayEnabled(boolean leapDelayEnabled) {
        this.leapDelayEnabled = leapDelayEnabled;
    }

    public double getLeapDelaySeconds() {
        return Math.min(5.0, Math.max(0.1, leapDelaySeconds));
    }

    public void setLeapDelaySeconds(double leapDelaySeconds) {
        this.leapDelaySeconds = leapDelaySeconds;
    }
}
