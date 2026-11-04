package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.nio.file.Path;

/** Persisted Boulder Solver settings - see {@link BoulderSolverFeature}'s class doc for the real
 *  Odin-ported puzzle-solution database this is built on. Ships disabled by default, same as every other
 *  new feature in this mod. */
public final class BoulderSolverConfig extends SolverConfigBase {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-bouldersolver.json");

    private static BoulderSolverConfig instance;

    private boolean showAllClicks = false;

    private BoulderSolverConfig() {
    }

    public static BoulderSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        BoulderSolverConfig cfg = new BoulderSolverConfig();
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showAllClicks = ConfigJson.getBool(obj, "showAllClicks", false);
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("showAllClicks", showAllClicks);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isShowAllClicks() { return showAllClicks; }

    public void setShowAllClicks(boolean showAllClicks) { this.showAllClicks = showAllClicks; }
}
