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

/** Persisted Tic Tac Toe Solver settings - see {@link TicTacToeSolverFeature}. Ships disabled. */
public final class TicTacToeSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-tictactoesolver.json");

    private static TicTacToeSolverConfig instance;

    private boolean enabled = false;
    private boolean showPrediction = false;
    /** Highlight style (killer560, 2026-09-21: "make an option again for fill or outline"). */
    private boolean fill = false;
    /** Blocks his click on any board square but the highlighted one (killer560, 2026-10-09). Sends nothing. */
    private boolean preventMisclicks = false;

    private TicTacToeSolverConfig() {
    }

    public static TicTacToeSolverConfig getInstance() {
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
            instance = new TicTacToeSolverConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            TicTacToeSolverConfig cfg = new TicTacToeSolverConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showPrediction = ConfigJson.getBool(obj, "showPrediction", false);
            cfg.fill = ConfigJson.getBool(obj, "fill", false);
            cfg.preventMisclicks = ConfigJson.getBool(obj, "preventMisclicks", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new TicTacToeSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showPrediction", showPrediction);
            obj.addProperty("fill", fill);
            obj.addProperty("preventMisclicks", preventMisclicks);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** The stored switch alone (no Skyblock Only gate): FeatureBits' gate. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public boolean isFill() {
        return fill;
    }

    public void setFill(boolean fill) {
        this.fill = fill;
    }

    public boolean isPreventMisclicks() {
        return preventMisclicks;
    }

    public void setPreventMisclicks(boolean preventMisclicks) {
        this.preventMisclicks = preventMisclicks;
    }

    public boolean isShowPrediction() {
        return showPrediction;
    }

    public void setShowPrediction(boolean showPrediction) {
        this.showPrediction = showPrediction;
    }
}
