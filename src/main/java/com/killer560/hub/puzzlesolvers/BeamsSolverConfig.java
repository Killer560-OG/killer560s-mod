package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.nio.file.Path;

/** Persisted Creeper Beams Solver settings - see {@link BeamsSolverFeature}'s class doc for the real
 *  Odin-ported lantern-pair database this is built on. Ships disabled by default, same as every other
 *  new feature in this mod. */
public final class BeamsSolverConfig extends SolverConfigBase {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-beamssolver.json");

    private static BeamsSolverConfig instance;

    private boolean showTracer = true;

    /** Which lit pairs are drawn (killer560, 2026-11-01): every one, one at a time, or the current one and the next. */
    public enum ShowMode {
        ALL("All"), ONE("One At A Time"), NEXT("Next");

        public final String label;

        ShowMode(String label) {
            this.label = label;
        }
    }

    private ShowMode showMode = ShowMode.ALL;

    private BeamsSolverConfig() {
    }

    public static BeamsSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        BeamsSolverConfig cfg = new BeamsSolverConfig();
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showTracer = ConfigJson.getBool(obj, "showTracer", true);
            cfg.showMode = ConfigJson.getEnum(obj, "showMode", ShowMode.class, ShowMode.ALL);
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("showTracer", showTracer);
        obj.addProperty("showMode", showMode.name());
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public ShowMode getShowMode() { return showMode; }

    public void setShowMode(ShowMode showMode) { this.showMode = showMode == null ? ShowMode.ALL : showMode; }

    public boolean isShowTracer() { return showTracer; }

    public void setShowTracer(boolean showTracer) { this.showTracer = showTracer; }
}
