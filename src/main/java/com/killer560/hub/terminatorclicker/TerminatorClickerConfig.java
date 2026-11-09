package com.killer560.hub.terminatorclicker;

import com.google.gson.JsonObject;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Terminator Auto Clicker settings (cheat build only, off by default). See {@link TerminatorClickerFeature}. */
public final class TerminatorClickerConfig {

    public static final int MIN_CPS = 3;
    /** The top of the slider, tested on GrimAC default and experimental (featH-1109). */
    public static final int MAX_CPS = 15;

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-terminatorclicker.json");

    private static TerminatorClickerConfig instance;

    private boolean enabled = false;
    private int cps = 8;

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
            cfg.setCps(ConfigJson.getInt(obj, "cps", cfg.cps));
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("cps", cps);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return BuildVariant.CHEAT_FEATURES_ENABLED && enabled; }

    public void setEnabled(boolean v) { enabled = v; }

    public int getCps() { return cps; }

    public void setCps(int v) { cps = Math.max(MIN_CPS, Math.min(MAX_CPS, v)); }
}
