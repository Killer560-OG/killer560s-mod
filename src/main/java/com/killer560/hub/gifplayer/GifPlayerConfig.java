package com.killer560.hub.gifplayer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Persisted GIF Player settings: master on/off, the playback speed and audio volume (both apply
 *  uniformly to whatever files are currently enabled), and per-file on/off preferences so several
 *  gifs/audio files can be toggled independently and shown/played at the same time. */
public final class GifPlayerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-gifplayer.json");

    /** Same range as GifPlayerTab's speed slider/field. */
    private static final float MIN_SPEED = 0.25f;
    private static final float MAX_SPEED = 4.0f;

    private static GifPlayerConfig instance;

    private boolean enabled = true;
    /** Separate master switch for the audio section - lets killer560 mute all audio while leaving the
     *  gif(s) showing, or vice versa, without touching the per-file toggles. */
    private boolean audioEnabled = true;
    private float speedMultiplier = 1.0f;
    /** 0.0 (silent) - 1.0 (full volume), applied to every currently-enabled audio file. */
    private float volume = 0.5f;
    /** Filename -> whether the user wants it shown/played. Absent = defaults to enabled, so a
     *  newly-dropped file works immediately without needing to be opted into first. */
    private final Map<String, Boolean> gifFileEnabled = new HashMap<>();
    private final Map<String, Boolean> audioFileEnabled = new HashMap<>();

    private GifPlayerConfig() {
    }

    public static GifPlayerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new GifPlayerConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            GifPlayerConfig cfg = new GifPlayerConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.audioEnabled = ConfigJson.getBool(obj, "audioEnabled", cfg.audioEnabled);
            // Same ranges GifPlayerTab's sliders / Set buttons clamp to.
            cfg.speedMultiplier = Math.max(MIN_SPEED, Math.min(MAX_SPEED, ConfigJson.getFloat(obj, "speedMultiplier", cfg.speedMultiplier)));
            cfg.volume = Math.max(0f, Math.min(1f, ConfigJson.getFloat(obj, "volume", cfg.volume)));
            readFileMap(obj, "gifFileEnabled", cfg.gifFileEnabled);
            readFileMap(obj, "audioFileEnabled", cfg.audioFileEnabled);
            instance = cfg;
        } catch (Exception e) {
            instance = new GifPlayerConfig();
        }
    }

    /** One bad per-file value is skipped on its own instead of failing the whole file. */
    private static void readFileMap(JsonObject obj, String key, Map<String, Boolean> target) {
        JsonObject map = ConfigJson.getObject(obj, key);
        if (map == null) {
            return;
        }
        for (String filename : map.keySet()) {
            try {
                JsonElement el = map.get(filename);
                if (el != null && el.isJsonPrimitive()) {
                    target.put(filename, el.getAsBoolean());
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("audioEnabled", audioEnabled);
            obj.addProperty("speedMultiplier", speedMultiplier);
            obj.addProperty("volume", volume);
            obj.add("gifFileEnabled", toJson(gifFileEnabled));
            obj.add("audioFileEnabled", toJson(audioFileEnabled));
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static JsonObject toJson(Map<String, Boolean> map) {
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, Boolean> entry : map.entrySet()) {
            obj.addProperty(entry.getKey(), entry.getValue());
        }
        return obj;
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAudioEnabled() {
        return audioEnabled;
    }

    public void setAudioEnabled(boolean audioEnabled) {
        this.audioEnabled = audioEnabled;
    }

    public float getSpeedMultiplier() {
        return speedMultiplier;
    }

    public void setSpeedMultiplier(float speedMultiplier) {
        this.speedMultiplier = speedMultiplier;
    }

    public float getVolume() {
        return volume;
    }

    public void setVolume(float volume) {
        this.volume = volume;
    }

    public boolean isGifFileEnabled(String filename) {
        return gifFileEnabled.getOrDefault(filename, true);
    }

    public void setGifFileEnabled(String filename, boolean enabled) {
        gifFileEnabled.put(filename, enabled);
    }

    public boolean isAudioFileEnabled(String filename) {
        return audioFileEnabled.getOrDefault(filename, true);
    }

    public void setAudioFileEnabled(String filename, boolean enabled) {
        audioFileEnabled.put(filename, enabled);
    }
}
