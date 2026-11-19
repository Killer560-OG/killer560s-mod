package com.killer560.hub.terminatorclicker;

import com.google.gson.JsonObject;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.RateMs;

import java.nio.file.Path;

/** Shortbow Auto Clicker settings (cheat build only, off by default). See {@link TerminatorClickerFeature}. */
public final class TerminatorClickerConfig {

    /** The ends of the delay slider in ms: 15 CPS (tested on GrimAC default and experimental, featH-1109) down to 3 CPS. */
    public static final int MIN_DELAY = 67;
    public static final int MAX_DELAY = 333;

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-terminatorclicker.json");

    private static TerminatorClickerConfig instance;

    private boolean enabled = false;
    private int delayMin = 125;
    private int delayMax = 125;

    private TerminatorClickerConfig() {
    }

    public static TerminatorClickerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        TerminatorClickerConfig cfg = new TerminatorClickerConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            // Old keys (sliderunits-1118): cpsMin/cpsMax, and before 2026-11-09 one "cps"; more clicks a second is a shorter gap.
            int single = RateMs.read(obj, "none", "cps", 125);
            cfg.setDelayRange(RateMs.read(obj, "delayMin", "cpsMax", single), RateMs.read(obj, "delayMax", "cpsMin", single));
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("delayMin", delayMin);
        obj.addProperty("delayMax", delayMax);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return BuildVariant.CHEAT_FEATURES_ENABLED && enabled; }

    public void setEnabled(boolean v) { enabled = v; }

    public int getDelayMin() { return delayMin; }

    public int getDelayMax() { return delayMax; }

    public void setDelayRange(int a, int b) {
        int lo = Math.max(MIN_DELAY, Math.min(MAX_DELAY, Math.min(a, b)));
        int hi = Math.max(MIN_DELAY, Math.min(MAX_DELAY, Math.max(a, b)));
        delayMin = lo;
        delayMax = hi;
    }
}
