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

/** Persisted Ice Fill Solver settings - see {@link IceFillSolverFeature}'s class doc for the real
 *  Odin-ported path database this is built on. Ships disabled by default, same as every other new
 *  feature in this mod. */
public final class IceFillSolverConfig {

    /** The pre-2026-11-01 presets, kept only so an old file's "lineColor" loads as its color.
     *  killer560, 2026-09-20: "ice fill's line shouldn't be blue, its hard to see - make it configurable".
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
            ModPaths.config("killer560smod-icefillsolver.json");

    private static IceFillSolverConfig instance;

    private boolean enabled = false;
    private boolean optimizedPath = false;
    /** Default line color: the old LIME preset. */
    public static final int DEFAULT_LINE_COLOR = argbOf(LineColor.LIME);
    /** The line color, picked in a color box since 2026-11-01 (killer560: "line color should be a customizable box not a
     *  toggle through"). A file from before holds the old preset name under "lineColor", which load turns into its color. */
    private int lineColorArgb = DEFAULT_LINE_COLOR;
    /** Etherwarp Protection (killer560, 2026-11-01): see {@link IceFillSolverFeature#etherwarpVerdict}. */
    private boolean etherwarpProtection = false;

    private IceFillSolverConfig() {
    }

    public static IceFillSolverConfig getInstance() {
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
            instance = new IceFillSolverConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            IceFillSolverConfig cfg = new IceFillSolverConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.optimizedPath = ConfigJson.getBool(obj, "optimizedPath", false);
            if (obj.has("lineColorArgb")) {
                cfg.lineColorArgb = (int) obj.get("lineColorArgb").getAsLong();
            } else {
                cfg.lineColorArgb = argbOf(ConfigJson.getEnum(obj, "lineColor", LineColor.class, LineColor.LIME));
            }
            cfg.etherwarpProtection = ConfigJson.getBool(obj, "etherwarpProtection", false);
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
            obj.addProperty("lineColorArgb", lineColorArgb);
            obj.addProperty("etherwarpProtection", etherwarpProtection);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() { return enabled && com.killer560.hub.util.SkyblockGate.allows(); }

    /** The stored switch alone (no Skyblock Only gate): FeatureBits' gate. */
    public boolean isEnabledRaw() { return enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        com.killer560.hub.util.FeatureBits.changed();
    }

    /** Odin's "hard" path set - a real, different (usually shorter/riskier) valid route than the default
     *  "easy" set. Both are real, confirmed-valid solve paths - this is a style preference, not a
     *  correctness difference. */
    public boolean isOptimizedPath() { return optimizedPath; }

    public void setOptimizedPath(boolean optimizedPath) { this.optimizedPath = optimizedPath; }

    public int getLineColorArgb() { return lineColorArgb; }

    public void setLineColorArgb(int argb) { this.lineColorArgb = argb; }

    public boolean isEtherwarpProtection() { return etherwarpProtection; }

    public void setEtherwarpProtection(boolean etherwarpProtection) { this.etherwarpProtection = etherwarpProtection; }

    /** A preset as an opaque ARGB color. */
    static int argbOf(LineColor c) {
        return 0xFF000000 | (Math.round(c.r * 255) << 16) | (Math.round(c.g * 255) << 8) | Math.round(c.b * 255);
    }
}
