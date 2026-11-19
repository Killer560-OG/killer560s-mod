package com.killer560.hub.autoanvil;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Persisted Auto Anvil settings ({@code killer560smod-autoanvil.json}). Off by default; cheat build only. */
public final class AutoAnvilConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-autoanvil.json");

    public static final int MIN_DELAY_MS = 50;
    public static final int MAX_DELAY_MS = 1000;

    private static AutoAnvilConfig instance;

    private boolean enabled = false;
    /** Random wait after the server has answered one click and before the next, low end. */
    private int minDelayMs = 150;
    /** ... and high end. */
    private int maxDelayMs = 300;
    /** Feed books this session made back in (two new IV into a V). */
    private boolean cascade = true;
    /** Close the anvil once nothing is left to combine, if anything was combined. */
    private boolean closeWhenDone = false;

    /** How a run begins in an open anvil (killer560, 2026-11-18): a Start button over the menu, or by itself. */
    public enum StartMode {
        START_BUTTON("Start Button"), AUTO_START("Auto Start");

        public final String label;

        StartMode(String label) {
            this.label = label;
        }
    }

    private StartMode startMode = StartMode.START_BUTTON;

    private AutoAnvilConfig() {
    }

    public static AutoAnvilConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AutoAnvilConfig cfg = new AutoAnvilConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.setMinDelayMs(ConfigJson.getInt(obj, "minDelayMs", cfg.minDelayMs));
            cfg.setMaxDelayMs(ConfigJson.getInt(obj, "maxDelayMs", cfg.maxDelayMs));
            cfg.cascade = ConfigJson.getBool(obj, "cascade", cfg.cascade);
            cfg.closeWhenDone = ConfigJson.getBool(obj, "closeWhenDone", cfg.closeWhenDone);
            // A file from before the setting existed started by itself on menu open: keep that for it.
            cfg.startMode = ConfigJson.getBool(obj, "autoStart", true) ? StartMode.AUTO_START : StartMode.START_BUTTON;
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("minDelayMs", minDelayMs);
        obj.addProperty("maxDelayMs", maxDelayMs);
        obj.addProperty("cascade", cascade);
        obj.addProperty("closeWhenDone", closeWhenDone);
        obj.addProperty("autoStart", startMode == StartMode.AUTO_START);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** Cheat build, the toggle, and the Skyblock Only gate. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMinDelayMs() {
        return minDelayMs;
    }

    public void setMinDelayMs(int v) {
        this.minDelayMs = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, v));
    }

    public int getMaxDelayMs() {
        return maxDelayMs;
    }

    public void setMaxDelayMs(int v) {
        this.maxDelayMs = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, v));
    }

    public boolean isCascade() {
        return cascade;
    }

    public void setCascade(boolean cascade) {
        this.cascade = cascade;
    }

    public StartMode getStartMode() {
        return startMode;
    }

    public void setStartMode(StartMode startMode) {
        this.startMode = startMode;
    }

    public boolean isCloseWhenDone() {
        return closeWhenDone;
    }

    public void setCloseWhenDone(boolean closeWhenDone) {
        this.closeWhenDone = closeWhenDone;
    }
}
