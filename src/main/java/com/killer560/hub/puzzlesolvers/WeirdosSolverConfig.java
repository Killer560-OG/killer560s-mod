package com.killer560.hub.puzzlesolvers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Weirdos Solver settings - see {@link WeirdosSolverFeature}'s class doc for the real
 *  Odin-ported dialogue-matching logic this is built on. Ships disabled by default, same as every other
 *  new feature in this mod. */
public final class WeirdosSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-weirdossolver.json");

    private static WeirdosSolverConfig instance;

    private boolean enabled = false;
    private boolean showWrongChests = true;

    private WeirdosSolverConfig() {
    }

    public static WeirdosSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new WeirdosSolverConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            WeirdosSolverConfig cfg = new WeirdosSolverConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.showWrongChests = !obj.has("showWrongChests") || obj.get("showWrongChests").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new WeirdosSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showWrongChests", showWrongChests);
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

    public boolean isShowWrongChests() {
        return showWrongChests;
    }

    public void setShowWrongChests(boolean showWrongChests) {
        this.showWrongChests = showWrongChests;
    }
}
