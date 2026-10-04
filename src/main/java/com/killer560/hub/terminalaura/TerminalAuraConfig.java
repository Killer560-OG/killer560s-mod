package com.killer560.hub.terminalaura;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Terminal Aura settings - see {@link TerminalAuraFeature}. Cheat build only; ships disabled.
 *  {@link #isEnabled()} is gated on {@link BuildVariant#CHEAT_FEATURES_ENABLED} and
 *  {@link SkyblockGate#allows()}; {@link #isEnabledRaw()} is the un-gated value, for the tab's own label. */
public final class TerminalAuraConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-terminalaura.json");

    /**
     * The slider's own bounds. killer560 (2026-10-01): "make the range a slider and have it go from half a
     * block up to 4.5 blocks."
     *
     * <p><b>The top of this slider is past what was measured to be safe, and that is worth knowing before
     * using it.</b> {@link #SAFE_RANGE} is 3.0, measured on his own harness against a live GrimAC on
     * 2026-09-28: interacting with an armour stand at 3.26 blocks and beyond drew a {@code Reach} violation
     * naming the distance, 2.18 and closer drew nothing. A terminal IS an armour stand, so that is the limit
     * that applies here - the 4.5 figure is the BLOCK interaction limit, which is a different and looser one.
     * The cap used to be 3.0 for exactly this reason. It is 4.5 now because he asked for it; the tab colours
     * anything past 3.0 so the choice is visible where it is made rather than only in this comment.
     */
    public static final double MIN_RANGE = 0.5;
    public static final double MAX_RANGE = 4.5;

    /** The largest range measured not to draw a Reach violation. See {@link #MAX_RANGE}. */
    public static final double SAFE_RANGE =
            com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_ENTITY_REACH;

    public static final int MAX_DELAY_MS = 2000;

    private static TerminalAuraConfig instance;

    private boolean enabled = false;
    /** Defaults to the measured-safe 3.0, not to the top of the slider - see {@link #MAX_RANGE}. */
    private double range = SAFE_RANGE;
    private int delayMs = 750;
    private boolean pauseOnMovementKeys = false;
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
                // 3.0, matching the field default. It read 4.0 here and SAFE_RANGE on the field, so the
                // two disagreed and getRange() silently clamped the difference away.
                cfg.range = ConfigJson.getDouble(root, "range", SAFE_RANGE);
                cfg.delayMs = ConfigJson.getInt(root, "delayMs", 750);
                cfg.groundOnly = ConfigJson.getBool(root, "groundOnly", false);
                cfg.leapDelayEnabled = ConfigJson.getBool(root, "leapDelayEnabled", false);
                cfg.leapDelaySeconds = ConfigJson.getDouble(root, "leapDelaySeconds", 0.5);
                cfg.pauseOnMovementKeys = ConfigJson.getBool(root, "pauseOnMovementKeys", false);
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
            root.addProperty("pauseOnMovementKeys", pauseOnMovementKeys);
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
        return Math.min(MAX_RANGE, Math.max(MIN_RANGE, range));
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

    /**
     * Whether holding a movement key stops the aura.
     *
     * <p>killer560 (2026-10-01): "add an option to not have term aura work whenever you are moving by moving,
     * I mean, holding any movement key at all whatsoever, not necessarily having velocity." So the test is the
     * KEY, not the velocity - see {@code TerminalAuraFeature.movementKeyHeld}. Ships off, so turning it on is
     * his choice rather than a behaviour change he did not ask for on the next build.
     */
    public boolean isPauseOnMovementKeys() {
        return pauseOnMovementKeys;
    }

    public void setPauseOnMovementKeys(boolean pauseOnMovementKeys) {
        this.pauseOnMovementKeys = pauseOnMovementKeys;
    }

    public double getLeapDelaySeconds() {
        return Math.min(5.0, Math.max(0.1, leapDelaySeconds));
    }

    public void setLeapDelaySeconds(double leapDelaySeconds) {
        this.leapDelaySeconds = leapDelaySeconds;
    }
}
