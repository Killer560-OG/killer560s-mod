package com.killer560.hub.gifplayer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
            cfg.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
            cfg.audioEnabled = !obj.has("audioEnabled") || obj.get("audioEnabled").getAsBoolean();
            cfg.speedMultiplier = obj.has("speedMultiplier") ? obj.get("speedMultiplier").getAsFloat() : 1.0f;
            cfg.volume = obj.has("volume") ? obj.get("volume").getAsFloat() : 0.5f;
            readFileMap(obj, "gifFileEnabled", cfg.gifFileEnabled);
            readFileMap(obj, "audioFileEnabled", cfg.audioFileEnabled);
            instance = cfg;
        } catch (Exception e) {
            instance = new GifPlayerConfig();
        }
    }

    private static void readFileMap(JsonObject obj, String key, Map<String, Boolean> target) {
        if (!obj.has(key)) {
            return;
        }
        JsonObject map = obj.getAsJsonObject(key);
        for (String filename : map.keySet()) {
            target.put(filename, map.get(filename).getAsBoolean());
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
        return enabled;
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
