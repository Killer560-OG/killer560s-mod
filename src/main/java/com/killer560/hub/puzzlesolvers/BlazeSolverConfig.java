package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.nio.file.Path;

/** Persisted Blaze Solver settings - see {@link BlazeSolverFeature}'s class doc for the real
 *  Odin-ported HP-order logic this is built on. Ships disabled by default, same as every other new
 *  feature in this mod. */
public final class BlazeSolverConfig extends SolverConfigBase {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-blazesolver.json");

    private static BlazeSolverConfig instance;

    private boolean showLines = true;
    private boolean fillBox = false;

    private BlazeSolverConfig() {
    }

    public static BlazeSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        BlazeSolverConfig cfg = new BlazeSolverConfig();
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showLines = ConfigJson.getBool(obj, "showLines", true);
            cfg.fillBox = ConfigJson.getBool(obj, "fillBox", false);
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("showLines", showLines);
        obj.addProperty("fillBox", fillBox);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isShowLines() { return showLines; }

    public void setShowLines(boolean showLines) { this.showLines = showLines; }

    /** killer560, 2026-09-20: "add a fill-box option". OFF (default) keeps the existing outline look. */
    public boolean isFillBox() { return fillBox; }

    public void setFillBox(boolean fillBox) { this.fillBox = fillBox; }
}
