package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.nio.file.Path;

/** Persisted Ice Path (silverfish) Solver settings - see {@link IcePathSolverFeature}. Ships disabled. */
public final class IcePathSolverConfig extends SolverConfigBase {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-icepathsolver.json");

    private static IcePathSolverConfig instance;

    private boolean showNextBox = true;

    private IcePathSolverConfig() {
    }

    public static IcePathSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        IcePathSolverConfig cfg = new IcePathSolverConfig();
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showNextBox = ConfigJson.getBool(obj, "showNextBox", true);
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("showNextBox", showNextBox);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isShowNextBox() { return showNextBox; }

    public void setShowNextBox(boolean showNextBox) { this.showNextBox = showNextBox; }
}
