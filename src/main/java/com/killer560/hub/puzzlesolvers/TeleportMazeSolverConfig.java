package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.nio.file.Path;

/** Persisted Teleport Maze Solver settings - see {@link TeleportMazeSolverFeature}. Ships disabled. */
public final class TeleportMazeSolverConfig extends SolverConfigBase {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-teleportmazesolver.json");

    private static TeleportMazeSolverConfig instance;

    private boolean showTracer = true;
    /** The tracer's color (killer560, 2026-11-01: "an option for the tracer color"); the cyan it always drew. */
    public static final int DEFAULT_TRACER_COLOR = 0xFF55FFFF;
    private int tracerColor = DEFAULT_TRACER_COLOR;
    /** Draw only the pads of the cell he stands in (killer560, 2026-11-01). */
    private boolean currentCellOnly = false;
    /** Draw only the pad he should take next (killer560, 2026-11-01). */
    private boolean bestPadOnly = false;

    private TeleportMazeSolverConfig() {
    }

    public static TeleportMazeSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        TeleportMazeSolverConfig cfg = new TeleportMazeSolverConfig();
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showTracer = ConfigJson.getBool(obj, "showTracer", true);
            cfg.tracerColor = obj.has("tracerColor") ? (int) obj.get("tracerColor").getAsLong() : DEFAULT_TRACER_COLOR;
            cfg.currentCellOnly = ConfigJson.getBool(obj, "currentCellOnly", false);
            cfg.bestPadOnly = ConfigJson.getBool(obj, "bestPadOnly", false);
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("showTracer", showTracer);
        obj.addProperty("tracerColor", tracerColor);
        obj.addProperty("currentCellOnly", currentCellOnly);
        obj.addProperty("bestPadOnly", bestPadOnly);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public int getTracerColor() { return tracerColor; }

    public void setTracerColor(int tracerColor) { this.tracerColor = tracerColor; }

    public boolean isCurrentCellOnly() { return currentCellOnly; }

    public void setCurrentCellOnly(boolean currentCellOnly) { this.currentCellOnly = currentCellOnly; }

    public boolean isBestPadOnly() { return bestPadOnly; }

    public void setBestPadOnly(boolean bestPadOnly) { this.bestPadOnly = bestPadOnly; }

    public boolean isShowTracer() { return showTracer; }

    public void setShowTracer(boolean showTracer) { this.showTracer = showTracer; }
}
