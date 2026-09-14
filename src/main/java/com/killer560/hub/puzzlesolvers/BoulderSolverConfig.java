package com.killer560.hub.puzzlesolvers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Boulder Solver settings - see {@link BoulderSolverFeature}'s class doc for the real
 *  Odin-ported puzzle-solution database this is built on. Ships disabled by default, same as every other
 *  new feature in this mod. */
public final class BoulderSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-bouldersolver.json");

    private static BoulderSolverConfig instance;

    private boolean enabled = false;
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
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BoulderSolverConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            BoulderSolverConfig cfg = new BoulderSolverConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.showAllClicks = obj.has("showAllClicks") && obj.get("showAllClicks").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new BoulderSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showAllClicks", showAllClicks);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowAllClicks() {
        return showAllClicks;
    }

    public void setShowAllClicks(boolean showAllClicks) {
        this.showAllClicks = showAllClicks;
    }
}
