package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.nio.file.Path;

/** Persisted Weirdos Solver settings - see {@link WeirdosSolverFeature}'s class doc for the real
 *  Odin-ported dialogue-matching logic this is built on. Ships disabled by default, same as every other
 *  new feature in this mod. */
public final class WeirdosSolverConfig extends SolverConfigBase {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-weirdossolver.json");

    private static WeirdosSolverConfig instance;

    private boolean showWrongChests = true;

    private WeirdosSolverConfig() {
    }

    public static WeirdosSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        WeirdosSolverConfig cfg = new WeirdosSolverConfig();
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showWrongChests = ConfigJson.getBool(obj, "showWrongChests", true);
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("showWrongChests", showWrongChests);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isShowWrongChests() { return showWrongChests; }

    public void setShowWrongChests(boolean showWrongChests) { this.showWrongChests = showWrongChests; }
}
