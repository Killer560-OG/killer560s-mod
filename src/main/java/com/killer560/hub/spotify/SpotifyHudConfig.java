package com.killer560.hub.spotify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spotify HUD settings ({@link SpotifyHud}). Its own {@code killer560smod-} file, so unlike the Spotify Lyrics file it
 * rides in profiles; the HUD's position and scale live in {@code HudConfig} like every other element's.
 *
 * <p>Off by default, like Spotify Lyrics itself (killer560 asked for that one off, 2026-09-30): it only has anything
 * to show with the Spotify desktop app open, and it would otherwise appear for every friend who runs the mod.
 */
public final class SpotifyHudConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-spotifyhud.json");

    private static volatile SpotifyHudConfig instance;

    /** Volatile: the lyrics poll thread reads it to decide whether to poll at all. */
    private volatile boolean hudEnabled = false;
    private boolean showLyrics = true;
    private boolean wordHighlight = true;
    private boolean hideWhenPaused = false;
    /** Smooth Lyric Scroll (2026-10-26): the lyric rows slide up when a line ends instead of jumping. */
    private boolean smoothScroll = true;

    private SpotifyHudConfig() {
    }

    public static SpotifyHudConfig getInstance() {
        SpotifyHudConfig cfg = instance;
        if (cfg == null) {
            load();
            cfg = instance;
        }
        return cfg;
    }

    public static synchronized void load() {
        SpotifyHudConfig cfg = new SpotifyHudConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.hudEnabled = ConfigJson.getBool(obj, "hudEnabled", cfg.hudEnabled);
                cfg.showLyrics = ConfigJson.getBool(obj, "showLyrics", cfg.showLyrics);
                cfg.wordHighlight = ConfigJson.getBool(obj, "wordHighlight", cfg.wordHighlight);
                cfg.hideWhenPaused = ConfigJson.getBool(obj, "hideWhenPaused", cfg.hideWhenPaused);
                cfg.smoothScroll = ConfigJson.getBool(obj, "smoothScroll", cfg.smoothScroll);
            } catch (Exception e) {
                SpotifyLyricsFeature.LOGGER.warn("Could not read Spotify HUD config: {}", e.getMessage());
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("hudEnabled", hudEnabled);
            obj.addProperty("showLyrics", showLyrics);
            obj.addProperty("wordHighlight", wordHighlight);
            obj.addProperty("hideWhenPaused", hideWhenPaused);
            obj.addProperty("smoothScroll", smoothScroll);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SpotifyLyricsFeature.LOGGER.warn("Could not save Spotify HUD config: {}", e.getMessage());
        }
    }

    public boolean isHudEnabled() {
        return hudEnabled;
    }

    public void setHudEnabled(boolean hudEnabled) {
        this.hudEnabled = hudEnabled;
    }

    public boolean isShowLyrics() {
        return showLyrics;
    }

    public void setShowLyrics(boolean showLyrics) {
        this.showLyrics = showLyrics;
    }

    public boolean isWordHighlight() {
        return wordHighlight;
    }

    public void setWordHighlight(boolean wordHighlight) {
        this.wordHighlight = wordHighlight;
    }

    public boolean isHideWhenPaused() {
        return hideWhenPaused;
    }

    public void setHideWhenPaused(boolean hideWhenPaused) {
        this.hideWhenPaused = hideWhenPaused;
    }

    public boolean isSmoothScroll() {
        return smoothScroll;
    }

    public void setSmoothScroll(boolean smoothScroll) {
        this.smoothScroll = smoothScroll;
    }
}
