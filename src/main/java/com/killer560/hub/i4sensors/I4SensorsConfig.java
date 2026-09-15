package com.killer560.hub.i4sensors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Sharp Shooter (i4)" settings - the i4 sensors ({@link I4SensorsFeature}) and Auto i4
 *  ({@link AutoI4Feature}). Everything ships disabled by default. Kept the original file name/"enabled" key
 *  so an existing config carries over - "enabled" now only means the extra verbose block diff, since the
 *  focused i4 sensors are always on near the device (2026-09-14). */
public final class I4SensorsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-i4sensors.json");

    public static final int MAX_ROTATION_TIME_MS = 250;

    private static I4SensorsConfig instance;

    private boolean enabled = false;
    private boolean autoI4Enabled = false;
    // Same Rotate / No Rotate split as Simon Says (killer560's own request, 2026-09-14): Rotate turns the
    // real camera to each target before shooting; No Rotate aims server-side only for the shot.
    private boolean autoI4Rotate = true;
    // Defaults ported from NoammAddons' AutoI4.kt ("Rotation Time" 170ms, "Predictions" on).
    private int autoI4RotationTimeMs = 170;
    private boolean autoI4Predictions = true;

    private I4SensorsConfig() {
    }

    public static I4SensorsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new I4SensorsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            I4SensorsConfig cfg = new I4SensorsConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.autoI4Enabled = obj.has("autoI4Enabled") && obj.get("autoI4Enabled").getAsBoolean();
            cfg.autoI4Rotate = !obj.has("autoI4Rotate") || obj.get("autoI4Rotate").getAsBoolean();
            cfg.setAutoI4RotationTimeMs(obj.has("autoI4RotationTimeMs") ? obj.get("autoI4RotationTimeMs").getAsInt() : 170);
            cfg.autoI4Predictions = !obj.has("autoI4Predictions") || obj.get("autoI4Predictions").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new I4SensorsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("autoI4Enabled", autoI4Enabled);
            obj.addProperty("autoI4Rotate", autoI4Rotate);
            obj.addProperty("autoI4RotationTimeMs", autoI4RotationTimeMs);
            obj.addProperty("autoI4Predictions", autoI4Predictions);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Verbose wide-area block diff only - the focused i4 sensors don't need this. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - automatic aiming/shooting,
     *  a real macro, same pattern as Auto Solve/Auto Terminals. */
    public boolean isAutoI4Enabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoI4Enabled;
    }

    public void setAutoI4Enabled(boolean autoI4Enabled) {
        this.autoI4Enabled = autoI4Enabled;
    }

    public boolean isAutoI4Rotate() {
        return autoI4Rotate;
    }

    public void setAutoI4Rotate(boolean autoI4Rotate) {
        this.autoI4Rotate = autoI4Rotate;
    }

    public int getAutoI4RotationTimeMs() {
        return autoI4RotationTimeMs;
    }

    public void setAutoI4RotationTimeMs(int ms) {
        this.autoI4RotationTimeMs = Math.max(0, Math.min(MAX_ROTATION_TIME_MS, ms));
    }

    public boolean isAutoI4Predictions() {
        return autoI4Predictions;
    }

    public void setAutoI4Predictions(boolean autoI4Predictions) {
        this.autoI4Predictions = autoI4Predictions;
    }
}
