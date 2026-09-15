package com.killer560.hub.motionblur;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Motion Blur settings: enabled (default off), strength 0-100 %, and whether the HUD/GUI is
 *  blurred too (default off so the HUD stays sharp). */
public final class MotionBlurConfig {

    public static final int MIN_STRENGTH = 0;
    public static final int MAX_STRENGTH = 100;
    public static final int DEFAULT_STRENGTH = 50;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-motionblur.json");

    private static MotionBlurConfig instance;

    private boolean enabled = false;
    private int strength = DEFAULT_STRENGTH;
    private boolean blurGui = false;

    private MotionBlurConfig() {
    }

    public static MotionBlurConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        MotionBlurConfig cfg = new MotionBlurConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.strength = clampStrength(ConfigJson.getInt(obj, "strength", cfg.strength));
                cfg.blurGui = ConfigJson.getBool(obj, "blurGui", cfg.blurGui);
            } catch (Exception ignored) {
                // unreadable file: keep defaults for this session
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("strength", strength);
            obj.addProperty("blurGui", blurGui);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clampStrength(int v) {
        return Math.max(MIN_STRENGTH, Math.min(MAX_STRENGTH, v));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getStrength() {
        return strength;
    }

    public void setStrength(int strength) {
        this.strength = clampStrength(strength);
    }

    public boolean isBlurGui() {
        return blurGui;
    }

    public void setBlurGui(boolean blurGui) {
        this.blurGui = blurGui;
    }
}
