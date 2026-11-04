package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Teleport Maze Solver settings - see {@link TeleportMazeSolverFeature}. Ships disabled. */
public final class TeleportMazeSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-teleportmazesolver.json");

    private static TeleportMazeSolverConfig instance;

    private boolean enabled = false;
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

    /** Loads, then tells FeatureBits a gated setting may have changed (profile apply reloads every config). */
    public static void load() {
        load0();
        com.killer560.hub.util.FeatureBits.changed();
    }

    private static void load0() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TeleportMazeSolverConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            TeleportMazeSolverConfig cfg = new TeleportMazeSolverConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showTracer = ConfigJson.getBool(obj, "showTracer", true);
            cfg.tracerColor = obj.has("tracerColor") ? (int) obj.get("tracerColor").getAsLong() : DEFAULT_TRACER_COLOR;
            cfg.currentCellOnly = ConfigJson.getBool(obj, "currentCellOnly", false);
            cfg.bestPadOnly = ConfigJson.getBool(obj, "bestPadOnly", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new TeleportMazeSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showTracer", showTracer);
            obj.addProperty("tracerColor", tracerColor);
            obj.addProperty("currentCellOnly", currentCellOnly);
            obj.addProperty("bestPadOnly", bestPadOnly);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() { return enabled && com.killer560.hub.util.SkyblockGate.allows(); }

    /** The stored switch alone (no Skyblock Only gate): FeatureBits' gate. */
    public boolean isEnabledRaw() { return enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        com.killer560.hub.util.FeatureBits.changed();
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
