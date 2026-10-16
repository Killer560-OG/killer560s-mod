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

/** Persisted Ice Path (silverfish) Solver settings - see {@link IcePathSolverFeature}. Ships disabled. */
public final class IcePathSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-icepathsolver.json");

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

    /** Loads, then tells FeatureBits a gated setting may have changed (profile apply reloads every config). */
    public static void load() {
        load0();
        com.killer560.hub.util.FeatureBits.changed();
    }

    private static void load0() {
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

    /** The stored switch alone (no Skyblock Only gate): FeatureBits' gate. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public boolean isShowNextBox() {
        return showNextBox;
    }

    public void setShowNextBox(boolean showNextBox) {
        this.showNextBox = showNextBox;
    }
}
