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

/** Persisted Ice Path (silverfish) Solver settings - see {@link IcePathSolverFeature}. Ships disabled. */
public final class IcePathSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-icepathsolver.json");

    private static IcePathSolverConfig instance;

    private boolean enabled = false;
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
        if (!Files.exists(CONFIG_PATH)) {
            instance = new IcePathSolverConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            IcePathSolverConfig cfg = new IcePathSolverConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showNextBox = ConfigJson.getBool(obj, "showNextBox", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new IcePathSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showNextBox", showNextBox);
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

    public boolean isShowNextBox() {
        return showNextBox;
    }

    public void setShowNextBox(boolean showNextBox) {
        this.showNextBox = showNextBox;
    }
}
