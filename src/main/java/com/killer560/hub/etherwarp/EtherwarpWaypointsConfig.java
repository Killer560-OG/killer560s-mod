package com.killer560.hub.etherwarp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Etherwarp Waypoints settings ({@code killer560smod-etherwarp.json}). The waypoints themselves
 *  live in {@link EtherwarpWaypointsStore}'s own file - see that class's doc, and {@link EtherwarpFeature}'s,
 *  for why they are no longer "never written to disk" the way this used to be documented. */
public final class EtherwarpWaypointsConfig {

    /** killer560, 2026-09-27: "Make an option to have it filled, outline, or fill and outline." Same three
     *  choices Secret Waypoints / the Etherwarp Overlay already offer, spelled out the way he asked for them
     *  this time rather than reusing either package's own enum (each feature's colour picker names its own). */
    public enum Style {
        FILLED("Filled"), OUTLINE("Outline"), FILL_AND_OUTLINE("Filled+Outline");

        public final String label;

        Style(String label) {
            this.label = label;
        }

        public Style next() {
            Style[] v = values();
            return v[(ordinal() + 1) % v.length];
        }

        public Style previous() {
            Style[] v = values();
            return v[(v.length + ordinal() - 1) % v.length];
        }
    }

    /** killer560, 2026-09-27: "Make them purple." */
    public static final int DEFAULT_COLOR = 0xFFAA00FF;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-etherwarp.json");

    private static EtherwarpWaypointsConfig instance;

    private boolean enabled = false;
    // killer560, 2026-09-20: "instead it should highlight the block I was looking at". The box is the
    // point of the feature now, so it ships ON while the HUD list stays opt-in.
    private boolean highlightBlocks = true;
    private Style style = Style.FILL_AND_OUTLINE;
    private int color = DEFAULT_COLOR;
    /** killer560, 2026-09-27: "Make an option to have them show through walls." Off by default - unlike Secret
     *  Waypoints' preloaded boxes, these are boxes on a spot you're already standing next to when you place
     *  them, so the old depth-tested look is kept unless asked for. */
    private boolean throughWalls = false;
    private double highlightDistance = 64.0;

    private EtherwarpWaypointsConfig() {
    }

    public static EtherwarpWaypointsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public boolean isHighlightBlocks() {
        return highlightBlocks;
    }

    public void setHighlightBlocks(boolean v) {
        highlightBlocks = v;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style == null ? Style.FILL_AND_OUTLINE : style;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int argb) {
        color = argb;
    }

    public boolean isThroughWalls() {
        return throughWalls;
    }

    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }

    public double getHighlightDistance() {
        return highlightDistance;
    }

    public void setHighlightDistance(double v) {
        highlightDistance = Math.max(8.0, Math.min(128.0, v));
    }

    /** The highlight colour as r/g/b floats for the world renderer (alpha is handled separately per style,
     *  same convention {@code SecretWaypointsRenderer} uses). */
    public float[] highlightRgb() {
        return new float[] {((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f};
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new EtherwarpWaypointsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            EtherwarpWaypointsConfig cfg = new EtherwarpWaypointsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.highlightBlocks = ConfigJson.getBool(obj, "highlightBlocks", cfg.highlightBlocks);
            cfg.style = ConfigJson.getEnum(obj, "style", Style.class, cfg.style);
            cfg.throughWalls = ConfigJson.getBool(obj, "throughWalls", cfg.throughWalls);
            cfg.highlightDistance = Math.max(8.0, Math.min(128.0,
                    ConfigJson.getDouble(obj, "highlightDistance", cfg.highlightDistance)));
            if (obj.has("color")) {
                cfg.color = ConfigJson.getInt(obj, "color", cfg.color);
            } else {
                // 2026-09-27 migration: older files stored the colour as a bare "RRGGBB"/"AARRGGBB" hex string
                // ("highlightColorHex") with no picker behind it. Read it once so nobody's saved cyan gets
                // silently swapped for the new purple default; the picker only ever writes the new "color" key.
                String legacy = ConfigJson.getString(obj, "highlightColorHex", null);
                if (legacy != null) {
                    try {
                        long parsed = Long.parseLong(legacy.replace("#", ""), 16);
                        cfg.color = parsed <= 0xFFFFFFL ? (int) (0xFF000000L | parsed) : (int) parsed;
                    } catch (NumberFormatException ignored) {
                        // fall through with the new default
                    }
                }
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new EtherwarpWaypointsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("highlightBlocks", highlightBlocks);
            obj.addProperty("style", style.name());
            obj.addProperty("color", color);
            obj.addProperty("throughWalls", throughWalls);
            obj.addProperty("highlightDistance", highlightDistance);
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
}
