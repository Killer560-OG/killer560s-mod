package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.nio.file.Path;

/** Persisted Tic Tac Toe Solver settings - see {@link TicTacToeSolverFeature}. Ships disabled. */
public final class TicTacToeSolverConfig extends SolverConfigBase {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-tictactoesolver.json");

    private static TicTacToeSolverConfig instance;

    private boolean showPrediction = false;
    /** Highlight style (killer560, 2026-09-21: "make an option again for fill or outline"). */
    private boolean fill = false;
    /** Blocks his click on any board square but the highlighted one (killer560, 2026-10-09). Sends nothing. */
    private boolean preventMisclicks = false;
    /** Sneaking lets a blocked click through (killer560, 2026-11-01: "this needs an option for sneak to override"). */
    private boolean sneakOverride = true;

    private TicTacToeSolverConfig() {
    }

    public static TicTacToeSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        TicTacToeSolverConfig cfg = new TicTacToeSolverConfig();
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showPrediction = ConfigJson.getBool(obj, "showPrediction", false);
            cfg.fill = ConfigJson.getBool(obj, "fill", false);
            cfg.preventMisclicks = ConfigJson.getBool(obj, "preventMisclicks", false);
            cfg.sneakOverride = ConfigJson.getBool(obj, "sneakOverride", true);
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("showPrediction", showPrediction);
        obj.addProperty("fill", fill);
        obj.addProperty("preventMisclicks", preventMisclicks);
        obj.addProperty("sneakOverride", sneakOverride);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isFill() { return fill; }

    public void setFill(boolean fill) { this.fill = fill; }

    public boolean isPreventMisclicks() { return preventMisclicks; }

    public void setPreventMisclicks(boolean preventMisclicks) { this.preventMisclicks = preventMisclicks; }

    public boolean isSneakOverride() { return sneakOverride; }

    public void setSneakOverride(boolean sneakOverride) { this.sneakOverride = sneakOverride; }

    public boolean isShowPrediction() { return showPrediction; }

    public void setShowPrediction(boolean showPrediction) { this.showPrediction = showPrediction; }
}
