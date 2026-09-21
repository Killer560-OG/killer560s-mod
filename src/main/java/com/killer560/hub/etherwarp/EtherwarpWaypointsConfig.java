package com.killer560.hub.etherwarp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Only the "show the HUD list" toggle is persisted here - the waypoints themselves live entirely in
 *  {@link EtherwarpFeature}'s in-memory list, never written to disk. See that class's doc for why. */
public final class EtherwarpWaypointsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-etherwarp.json");

    private static EtherwarpWaypointsConfig instance;

    private boolean enabled = false;
    // killer560, 2026-09-20: "instead it should highlight the block I was looking at". The box is the
    // point of the feature now, so it ships ON while the HUD list stays opt-in.
    private boolean highlightBlocks = true;
    private String highlightColorHex = "FF55FFFF";
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

    public String getHighlightColorHex() {
        return highlightColorHex;
    }

    public void setHighlightColorHex(String v) {
        highlightColorHex = v == null || v.isBlank() ? "FF55FFFF" : v.trim();
    }

    public double getHighlightDistance() {
        return highlightDistance;
    }

    public void setHighlightDistance(double v) {
        highlightDistance = Math.max(8.0, Math.min(128.0, v));
    }

    /** The highlight colour as r/g/b floats for the world renderer. */
    public float[] highlightRgb() {
        int rgb;
        try {
            rgb = (int) Long.parseLong(highlightColorHex.replace("#", ""), 16);
        } catch (NumberFormatException e) {
            rgb = 0xFF55FFFF;
        }
        return new float[] {((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
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
            cfg.enabled = com.killer560.hub.util.ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.highlightBlocks = com.killer560.hub.util.ConfigJson.getBool(obj, "highlightBlocks", cfg.highlightBlocks);
            cfg.highlightColorHex = com.killer560.hub.util.ConfigJson.getString(obj, "highlightColorHex", cfg.highlightColorHex);
            cfg.highlightDistance = Math.max(8.0, Math.min(128.0,
                    com.killer560.hub.util.ConfigJson.getDouble(obj, "highlightDistance", cfg.highlightDistance)));
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
            obj.addProperty("highlightColorHex", highlightColorHex);
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
