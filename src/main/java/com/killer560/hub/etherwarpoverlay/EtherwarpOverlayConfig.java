package com.killer560.hub.etherwarpoverlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Etherwarp Overlay settings - see {@link EtherwarpOverlayFeature}'s class doc for the real
 *  Odin-ported landing-prediction this is built on. Ships disabled by default, same as every other new
 *  feature in this mod. */
public final class EtherwarpOverlayConfig {

    /** How the landing box is drawn - same three choices Simon Says / Secret Waypoints offer, so the option
     *  reads the same everywhere (2026-09-16, killer560's testing feedback asked for a filled option). */
    public enum Style {
        OUTLINE("Outline"), FILLED("Filled"), FILLED_OUTLINE("Filled+Outline");

        public final String label;

        Style(String label) {
            this.label = label;
        }

        public Style next() {
            Style[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    // The exact colours the overlay always drew before it had a colour option (r/g/b 0.2/1.0/0.2 and
    // 1.0/0.2/0.2, i.e. 0x33 = 51 = 0.2 * 255), exposed so the picker's "Set Default" restores them exactly.
    public static final int DEFAULT_SAFE_COLOR = 0xFF33FF33;
    public static final int DEFAULT_FAILED_COLOR = 0xFFFF3333;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-etherwarpoverlay.json");

    private static EtherwarpOverlayConfig instance;

    private boolean enabled = false;
    private boolean showWhenFailed = true;
    private boolean fullBlock = false;
    // Defaults reproduce the pre-option look exactly (outline only, green/red) so nobody's setup changes.
    private Style style = Style.OUTLINE;
    private int safeColor = DEFAULT_SAFE_COLOR;
    private int failedColor = DEFAULT_FAILED_COLOR;

    private EtherwarpOverlayConfig() {
    }

    public static EtherwarpOverlayConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new EtherwarpOverlayConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            EtherwarpOverlayConfig cfg = new EtherwarpOverlayConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.showWhenFailed = ConfigJson.getBool(obj, "showWhenFailed", cfg.showWhenFailed);
            cfg.fullBlock = ConfigJson.getBool(obj, "fullBlock", cfg.fullBlock);
            cfg.style = ConfigJson.getEnum(obj, "style", Style.class, cfg.style);
            cfg.safeColor = ConfigJson.getInt(obj, "safeColor", cfg.safeColor);
            cfg.failedColor = ConfigJson.getInt(obj, "failedColor", cfg.failedColor);
            instance = cfg;
        } catch (Exception e) {
            instance = new EtherwarpOverlayConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showWhenFailed", showWhenFailed);
            obj.addProperty("fullBlock", fullBlock);
            obj.addProperty("style", style.name());
            obj.addProperty("safeColor", safeColor);
            obj.addProperty("failedColor", failedColor);
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

    public boolean isShowWhenFailed() {
        return showWhenFailed;
    }

    public void setShowWhenFailed(boolean showWhenFailed) {
        this.showWhenFailed = showWhenFailed;
    }

    public boolean isFullBlock() {
        return fullBlock;
    }

    public void setFullBlock(boolean fullBlock) {
        this.fullBlock = fullBlock;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style == null ? Style.OUTLINE : style;
    }

    public int getSafeColor() {
        return safeColor;
    }

    public void setSafeColor(int argb) {
        this.safeColor = argb;
    }

    public int getFailedColor() {
        return failedColor;
    }

    public void setFailedColor(int argb) {
        this.failedColor = argb;
    }
}
