package com.killer560.hub.trail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted settings for the movement trail - killer560's item 8.4, verbatim: "Trail: one square per
 * tick, 1-100, orange while grounded, blue while airborne, nothing while standing still." Purely
 * cosmetic and client-side (no gameplay effect), so it ships in both the legit and cheat builds; per
 * the "new features default OFF and live in the New tab" rule, {@link #enabled} starts false.
 * <p>
 * {@code length}/{@code squareSize}/{@code opacity}/{@code fadeOut} are the small set of controls the
 * spec obviously needs to be usable (on/off, how long the trail is, how big each square is, how visible
 * it is, and whether it tapers toward the tail) - nothing beyond that was added.
 */
public final class TrailConfig {

    public static final int MIN_LENGTH = 1;
    public static final int MAX_LENGTH = 100;
    public static final float MIN_SQUARE_SIZE = 0.1f;
    public static final float MAX_SQUARE_SIZE = 1.0f;
    public static final int MIN_OPACITY_PERCENT = 5;
    public static final int MAX_OPACITY_PERCENT = 100;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-trail.json");

    private static TrailConfig instance;

    private boolean enabled = false;
    private int length = 40;
    private float squareSize = 0.3f;
    private int opacityPercent = 60;
    private boolean fadeOut = true;

    private TrailConfig() {
    }

    public static TrailConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Same "Skyblock Only" gating every other feature's master toggle goes through - see
     *  {@link SkyblockGate}. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    /** The raw saved toggle, ignoring {@link SkyblockGate} - lets the tab's checkbox reflect what's
     *  actually saved instead of flipping off just because Skyblock Only is hiding it right now (same
     *  pattern as {@code AbilityCooldownConfig#isEnabledRaw}). */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int v) {
        length = Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, v));
    }

    public float getSquareSize() {
        return squareSize;
    }

    public void setSquareSize(float v) {
        squareSize = Math.max(MIN_SQUARE_SIZE, Math.min(MAX_SQUARE_SIZE, v));
    }

    public int getOpacityPercent() {
        return opacityPercent;
    }

    public void setOpacityPercent(int v) {
        opacityPercent = Math.max(MIN_OPACITY_PERCENT, Math.min(MAX_OPACITY_PERCENT, v));
    }

    public boolean isFadeOut() {
        return fadeOut;
    }

    public void setFadeOut(boolean v) {
        fadeOut = v;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TrailConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            TrailConfig cfg = new TrailConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.length = Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, ConfigJson.getInt(obj, "length", cfg.length)));
            cfg.squareSize = Math.max(MIN_SQUARE_SIZE, Math.min(MAX_SQUARE_SIZE,
                    ConfigJson.getFloat(obj, "squareSize", cfg.squareSize)));
            cfg.opacityPercent = Math.max(MIN_OPACITY_PERCENT, Math.min(MAX_OPACITY_PERCENT,
                    ConfigJson.getInt(obj, "opacityPercent", cfg.opacityPercent)));
            cfg.fadeOut = ConfigJson.getBool(obj, "fadeOut", cfg.fadeOut);
            instance = cfg;
        } catch (Exception e) {
            instance = new TrailConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("length", length);
            obj.addProperty("squareSize", squareSize);
            obj.addProperty("opacityPercent", opacityPercent);
            obj.addProperty("fadeOut", fadeOut);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
