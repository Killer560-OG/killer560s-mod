package com.killer560.hub.boss;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Livid Solver settings - see {@link LividSolverFeature}'s class doc for the real
 *  Odin-ported wool/entity identification this is built on. Ships disabled by default, same as every
 *  other new feature in this mod. */
public final class LividSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-lividsolver.json");

    private static LividSolverConfig instance;

    private boolean enabled = false;
    private boolean showTimer = true;
    /** killer560, 2026-09-20: "add an option to draw a line to the correct livid". */
    private boolean showLine = true;

    private LividSolverConfig() {
    }

    public static LividSolverConfig getInstance() {
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
            instance = new LividSolverConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            LividSolverConfig cfg = new LividSolverConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showTimer = ConfigJson.getBool(obj, "showTimer", true);
            cfg.showLine = ConfigJson.getBool(obj, "showLine", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new LividSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showTimer", showTimer);
            obj.addProperty("showLine", showLine);
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

    public boolean isShowTimer() {
        return showTimer;
    }

    public void setShowTimer(boolean showTimer) {
        this.showTimer = showTimer;
    }

    public boolean isShowLine() {
        return showLine;
    }

    public void setShowLine(boolean showLine) {
        this.showLine = showLine;
    }
}
