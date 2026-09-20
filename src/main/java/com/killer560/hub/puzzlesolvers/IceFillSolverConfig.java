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

/** Persisted Ice Fill Solver settings - see {@link IceFillSolverFeature}'s class doc for the real
 *  Odin-ported path database this is built on. Ships disabled by default, same as every other new
 *  feature in this mod. */
public final class IceFillSolverConfig {

    /** killer560, 2026-09-20: "ice fill's line shouldn't be blue, its hard to see - make it configurable".
     *  Light blue read poorly against the puzzle's own ice/snow floor, so the default is a lime that reads
     *  against ice, snow AND stone alike; the rest give a real choice instead of guessing what he'd prefer. */
    public enum LineColor {
        LIME(0.3f, 1.0f, 0.3f),
        MAGENTA(1.0f, 0.3f, 1.0f),
        ORANGE(1.0f, 0.6f, 0.1f),
        WHITE(1.0f, 1.0f, 1.0f),
        RED(1.0f, 0.3f, 0.3f);

        public final float r;
        public final float g;
        public final float b;

        LineColor(float r, float g, float b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-icefillsolver.json");

    private static IceFillSolverConfig instance;

    private boolean enabled = false;
    private boolean optimizedPath = false;
    private LineColor lineColor = LineColor.LIME;

    private IceFillSolverConfig() {
    }

    public static IceFillSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new IceFillSolverConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            IceFillSolverConfig cfg = new IceFillSolverConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.optimizedPath = ConfigJson.getBool(obj, "optimizedPath", false);
            cfg.lineColor = ConfigJson.getEnum(obj, "lineColor", LineColor.class, LineColor.LIME);
            instance = cfg;
        } catch (Exception e) {
            instance = new IceFillSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("optimizedPath", optimizedPath);
            obj.addProperty("lineColor", lineColor.name());
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

    /** Odin's "hard" path set - a real, different (usually shorter/riskier) valid route than the default
     *  "easy" set. Both are real, confirmed-valid solve paths - this is a style preference, not a
     *  correctness difference. */
    public boolean isOptimizedPath() {
        return optimizedPath;
    }

    public void setOptimizedPath(boolean optimizedPath) {
        this.optimizedPath = optimizedPath;
    }

    public LineColor getLineColor() {
        return lineColor;
    }

    /** Cycles to the next color in {@link LineColor#values()}, wrapping around. */
    public void cycleLineColor() {
        LineColor[] values = LineColor.values();
        lineColor = values[(lineColor.ordinal() + 1) % values.length];
    }
}
