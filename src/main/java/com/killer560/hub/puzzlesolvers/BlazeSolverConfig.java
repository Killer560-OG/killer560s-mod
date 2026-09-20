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

/** Persisted Blaze Solver settings - see {@link BlazeSolverFeature}'s class doc for the real
 *  Odin-ported HP-order logic this is built on. Ships disabled by default, same as every other new
 *  feature in this mod. */
public final class BlazeSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-blazesolver.json");

    private static BlazeSolverConfig instance;

    private boolean enabled = false;
    private boolean showLines = true;
    private boolean fillBox = false;

    private BlazeSolverConfig() {
    }

    public static BlazeSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BlazeSolverConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            BlazeSolverConfig cfg = new BlazeSolverConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showLines = ConfigJson.getBool(obj, "showLines", true);
            cfg.fillBox = ConfigJson.getBool(obj, "fillBox", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new BlazeSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showLines", showLines);
            obj.addProperty("fillBox", fillBox);
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

    public boolean isShowLines() {
        return showLines;
    }

    public void setShowLines(boolean showLines) {
        this.showLines = showLines;
    }

    /** killer560, 2026-09-20: "add a fill-box option". OFF (default) keeps the existing outline look. */
    public boolean isFillBox() {
        return fillBox;
    }

    public void setFillBox(boolean fillBox) {
        this.fillBox = fillBox;
    }
}
