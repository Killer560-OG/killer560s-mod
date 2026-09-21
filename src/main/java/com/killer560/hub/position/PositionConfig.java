package com.killer560.hub.position;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Advanced Position HUD settings ({@code killer560smod-position.json}) - see
 * {@link PositionFeature}. Ships OFF; every GUI change calls {@link #save()} at the call site, same
 * pattern as {@code RealTimeConfig}/{@code LagDisplayConfig}.
 */
public final class PositionConfig {

    /** F3 shows 3 decimal places; beyond 8 a double carrying a world coordinate has no meaningful
     *  precision left, so the slider is capped there. */
    public static final int MIN_DECIMAL_PLACES = 0;
    public static final int MAX_DECIMAL_PLACES = 8;
    public static final int DEFAULT_DECIMAL_PLACES = 5;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-position.json");

    private static PositionConfig instance;

    private boolean enabled = false;
    private int decimalPlaces = DEFAULT_DECIMAL_PLACES;
    private boolean showFacing = true;
    private boolean showBlock = false;
    private boolean showVelocity = false;

    private PositionConfig() {
    }

    public static PositionConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        PositionConfig cfg = new PositionConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.decimalPlaces = clampDecimalPlaces(
                        ConfigJson.getInt(obj, "decimalPlaces", cfg.decimalPlaces));
                cfg.showFacing = ConfigJson.getBool(obj, "showFacing", cfg.showFacing);
                cfg.showBlock = ConfigJson.getBool(obj, "showBlock", cfg.showBlock);
                cfg.showVelocity = ConfigJson.getBool(obj, "showVelocity", cfg.showVelocity);
            } catch (Exception ignored) {
                // Unparseable file: keep defaults for everything.
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("decimalPlaces", decimalPlaces);
            obj.addProperty("showFacing", showFacing);
            obj.addProperty("showBlock", showBlock);
            obj.addProperty("showVelocity", showVelocity);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clampDecimalPlaces(int v) {
        return Math.max(MIN_DECIMAL_PLACES, Math.min(MAX_DECIMAL_PLACES, v));
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDecimalPlaces() {
        return decimalPlaces;
    }

    public void setDecimalPlaces(int decimalPlaces) {
        this.decimalPlaces = clampDecimalPlaces(decimalPlaces);
    }

    public boolean isShowFacing() {
        return showFacing;
    }

    public void setShowFacing(boolean showFacing) {
        this.showFacing = showFacing;
    }

    public boolean isShowBlock() {
        return showBlock;
    }

    public void setShowBlock(boolean showBlock) {
        this.showBlock = showBlock;
    }

    public boolean isShowVelocity() {
        return showVelocity;
    }

    public void setShowVelocity(boolean showVelocity) {
        this.showVelocity = showVelocity;
    }
}
