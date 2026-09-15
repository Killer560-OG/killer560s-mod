package com.killer560.hub.puzzlesolvers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Tic Tac Toe Solver settings - see {@link TicTacToeSolverFeature}. Ships disabled. */
public final class TicTacToeSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-tictactoesolver.json");

    private static TicTacToeSolverConfig instance;

    private boolean enabled = false;
    private boolean showPrediction = false;

    private TicTacToeSolverConfig() {
    }

    public static TicTacToeSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TicTacToeSolverConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            TicTacToeSolverConfig cfg = new TicTacToeSolverConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showPrediction = ConfigJson.getBool(obj, "showPrediction", false);
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
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowPrediction() {
        return showPrediction;
    }

    public void setShowPrediction(boolean showPrediction) {
        this.showPrediction = showPrediction;
    }
}
