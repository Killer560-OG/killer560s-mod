package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.nio.file.Path;

/** Persisted Water Board Solver settings - see {@link WaterSolverFeature}'s class doc for the real
 *  Odin-ported lever-timing database this is built on. Ships disabled by default, same as every other
 *  new feature in this mod. */
public final class WaterSolverConfig extends SolverConfigBase {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-watersolver.json");

    private static WaterSolverConfig instance;

    private boolean optimizedPath = false;
    private boolean showTracer = true;

    private WaterSolverConfig() {
    }

    public static WaterSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        WaterSolverConfig cfg = new WaterSolverConfig();
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.optimizedPath = ConfigJson.getBool(obj, "optimizedPath", false);
            cfg.showTracer = ConfigJson.getBool(obj, "showTracer", true);
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("optimizedPath", optimizedPath);
        obj.addProperty("showTracer", showTracer);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** Odin's real "optimized" lever-timing set - a different, real, equally-valid solve than the
     *  default set, same style-not-correctness distinction as Ice Fill's "hard" path. */
    public boolean isOptimizedPath() { return optimizedPath; }

    public void setOptimizedPath(boolean optimizedPath) { this.optimizedPath = optimizedPath; }

    public boolean isShowTracer() { return showTracer; }

    public void setShowTracer(boolean showTracer) { this.showTracer = showTracer; }
}
