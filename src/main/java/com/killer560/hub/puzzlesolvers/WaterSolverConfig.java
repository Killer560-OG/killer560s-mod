package com.killer560.hub.puzzlesolvers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Water Board Solver settings - see {@link WaterSolverFeature}'s class doc for the real
 *  Odin-ported lever-timing database this is built on. Ships disabled by default, same as every other
 *  new feature in this mod. */
public final class WaterSolverConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-watersolver.json");

    private static WaterSolverConfig instance;

    private boolean enabled = false;
    private boolean optimizedPath = false;
    private boolean showTracer = true;

    private WaterSolverConfig() {
    }

    public static WaterSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new WaterSolverConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            WaterSolverConfig cfg = new WaterSolverConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.optimizedPath = obj.has("optimizedPath") && obj.get("optimizedPath").getAsBoolean();
            cfg.showTracer = !obj.has("showTracer") || obj.get("showTracer").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new WaterSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("optimizedPath", optimizedPath);
            obj.addProperty("showTracer", showTracer);
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

    /** Odin's real "optimized" lever-timing set - a different, real, equally-valid solve than the
     *  default set, same style-not-correctness distinction as Ice Fill's "hard" path. */
    public boolean isOptimizedPath() {
        return optimizedPath;
    }

    public void setOptimizedPath(boolean optimizedPath) {
        this.optimizedPath = optimizedPath;
    }

    public boolean isShowTracer() {
        return showTracer;
    }

    public void setShowTracer(boolean showTracer) {
        this.showTracer = showTracer;
    }
}
