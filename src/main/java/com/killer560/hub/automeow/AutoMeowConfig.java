package com.killer560.hub.automeow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Auto Meow settings: just an on/off - the trigger words and response lines are a fixed
 *  built-in set (see {@link AutoMeowFeature}/{@link AutoMeowLines}), not user-editable in v1. */
public final class AutoMeowConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-automeow.json");

    private static AutoMeowConfig instance;

    private boolean enabled = false;
    /** Per killer560's request: also play a real Minecraft cat sound effect locally when Auto Meow
     *  triggers, alongside the text reply. */
    private boolean playCatNoises = true;
    /** 0.0-2.0 (0%-200%), passed straight to {@link net.minecraft.client.resources.sounds.SimpleSoundInstance#forUI}. */
    private float catVolume = 1.0f;

    private AutoMeowConfig() {
    }

    public static AutoMeowConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new AutoMeowConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AutoMeowConfig cfg = new AutoMeowConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.playCatNoises = !obj.has("playCatNoises") || obj.get("playCatNoises").getAsBoolean();
            cfg.catVolume = obj.has("catVolume")
                    ? Math.max(0f, Math.min(2f, obj.get("catVolume").getAsFloat())) : 1.0f;
            instance = cfg;
        } catch (Exception e) {
            instance = new AutoMeowConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("playCatNoises", playCatNoises);
            obj.addProperty("catVolume", catVolume);
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

    public boolean isPlayCatNoises() {
        return playCatNoises;
    }

    public void setPlayCatNoises(boolean playCatNoises) {
        this.playCatNoises = playCatNoises;
    }

    public float getCatVolume() {
        return catVolume;
    }

    public void setCatVolume(float catVolume) {
        this.catVolume = Math.max(0f, Math.min(2f, catVolume));
    }
}
