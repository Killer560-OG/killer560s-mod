package com.killer560.hub.spotify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spotify Controls settings ({@link SpotifyControls}, killer560 2026-10-26): one key per control, all unbound by default,
 * how far Rewind and Skip Ahead move, how much a volume key changes, and the last volume set from the menu. Its own
 * {@code killer560smod-} file, so it rides in profiles; {@code ProfileManager.reloadAllConfigs} reloads it.
 */
public final class SpotifyControlsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-spotify-controls.json");

    /** The seek steps Rewind and Skip Ahead cycle through, seconds. */
    static final int[] SEEK_STEPS = {5, 10, 15, 30};
    /** The volume steps a volume key cycles through, percent. */
    static final int[] VOLUME_STEPS = {2, 5, 10};

    private static volatile SpotifyControlsConfig instance;

    /** Key code per {@link SpotifyControls.Action}, in its order; {@link KeyUtil#NONE} when unbound. */
    private final int[] keys = new int[SpotifyControls.Action.values().length];
    private int seekSeconds = 10;
    private int volumeStep = 5;
    /** The volume last set from the menu or a key, 0-100, or -1 when never set. Shown while Spotify's own is unknown. */
    private int volume = -1;

    private SpotifyControlsConfig() {
        java.util.Arrays.fill(keys, KeyUtil.NONE);
    }

    public static SpotifyControlsConfig getInstance() {
        SpotifyControlsConfig cfg = instance;
        if (cfg == null) {
            load();
            cfg = instance;
        }
        return cfg;
    }

    public static synchronized void load() {
        SpotifyControlsConfig cfg = new SpotifyControlsConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (SpotifyControls.Action a : SpotifyControls.Action.values()) {
                    cfg.keys[a.ordinal()] = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, a.key, KeyUtil.NONE));
                }
                cfg.seekSeconds = pick(SEEK_STEPS, ConfigJson.getInt(obj, "seekSeconds", 10), 10);
                cfg.volumeStep = pick(VOLUME_STEPS, ConfigJson.getInt(obj, "volumeStep", 5), 5);
                cfg.volume = Math.max(-1, Math.min(100, ConfigJson.getInt(obj, "volume", -1)));
            } catch (Exception e) {
                SpotifyLyricsFeature.LOGGER.warn("Could not read Spotify Controls config: {}", e.getMessage());
            }
        }
        instance = cfg;
    }

    private static int pick(int[] allowed, int value, int fallback) {
        for (int a : allowed) {
            if (a == value) {
                return value;
            }
        }
        return fallback;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            for (SpotifyControls.Action a : SpotifyControls.Action.values()) {
                obj.addProperty(a.key, keys[a.ordinal()]);
            }
            obj.addProperty("seekSeconds", seekSeconds);
            obj.addProperty("volumeStep", volumeStep);
            obj.addProperty("volume", volume);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SpotifyLyricsFeature.LOGGER.warn("Could not save Spotify Controls config: {}", e.getMessage());
        }
    }

    public int getKey(SpotifyControls.Action action) {
        return keys[action.ordinal()];
    }

    public void setKey(SpotifyControls.Action action, int code) {
        keys[action.ordinal()] = KeyUtil.sanitizeBind(code);
    }

    /** Whether any control has a key, so the Windows reader is kept running for an instant first press. */
    public boolean anyKeyBound() {
        for (int k : keys) {
            if (k != KeyUtil.NONE) {
                return true;
            }
        }
        return false;
    }

    public int getSeekSeconds() {
        return seekSeconds;
    }

    public void setSeekSeconds(int seconds) {
        this.seekSeconds = pick(SEEK_STEPS, seconds, 10);
    }

    public int getVolumeStep() {
        return volumeStep;
    }

    public void setVolumeStep(int step) {
        this.volumeStep = pick(VOLUME_STEPS, step, 5);
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = Math.max(-1, Math.min(100, volume));
    }
}
