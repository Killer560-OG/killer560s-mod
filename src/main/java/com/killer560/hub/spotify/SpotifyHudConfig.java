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
    private boolean showLyrics = false;
    private boolean wordHighlight = true;
    private boolean hideWhenPaused = false;
    /** Smooth Lyric Scroll (2026-10-26): the lyric rows slide up when a line ends instead of jumping. */
    private boolean smoothScroll = true;
    /**
     * Word-Timed Lyrics (2026-10-27): ask Kugou's public lyrics service for lyrics with a time per word (the artist, title
     * and length only, as lrclib gets). On by default: it is what makes the lit word exact. Volatile: the lyrics thread
     * reads it.
     */
    private volatile boolean wordTimedLyrics = true;
    /**
     * Always Show Controls (killer560, 2026-11-01: "add an option on the hud to always see the skip buttons and such"):
     * the control row under the HUD is drawn all the time, not only with chat open and the mouse over it. Clicks still
     * need chat open, where the mouse is free.
     */
    private boolean alwaysShowControls = false;
    /**
     * Display Censor (killer560, 2026-11-01: "all is all minimal is only racial slur level of stuff and none is none of
     * it"): what the HUD, the F5 lines and other players' shared lines star out. Chat lyrics keep their own Profanity
     * Filter. Volatile: the relay thread reads it.
     */
    private volatile ProfanityLevel displayCensor = ProfanityLevel.MINIMAL;
    /**
     * Music Source (killer560, 2026-11-01: "make it work for web browser spotify as well ... other music apps like apple
     * music"). Volatile: the poll thread reads it.
     */
    private volatile MusicSource musicSource = MusicSource.MUSIC_PLAYERS;

    /** Which player the feature follows, through Windows' media controls. Spotify's desktop app always comes first. */
    public enum MusicSource {
        /** The Spotify desktop app only. No longer offered (killer560, 2026-11-02); a saved value loads as MUSIC_PLAYERS. */
        SPOTIFY("Spotify App Only"),
        /**
         * Also any other player that tells Windows what it plays (Apple Music, iTunes, Tidal, Deezer...), and a browser
         * tab that names an album, which a music site does (Spotify's web player, YouTube Music, Apple Music on the web)
         * and a plain video does not.
         */
        MUSIC_PLAYERS("Music Players"),
        /** Anything playing that tells Windows about it, plain browser videos included. */
        ANYTHING("Anything Playing");

        public final String label;

        MusicSource(String label) {
            this.label = label;
        }

        /** The sources the menu offers. */
        public static MusicSource[] offered() {
            return new MusicSource[]{MUSIC_PLAYERS, ANYTHING};
        }
    }

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
                cfg.wordTimedLyrics = ConfigJson.getBool(obj, "wordTimedLyrics", cfg.wordTimedLyrics);
                cfg.alwaysShowControls = ConfigJson.getBool(obj, "alwaysShowControls", cfg.alwaysShowControls);
                cfg.displayCensor = ConfigJson.getEnum(obj, "displayCensor", ProfanityLevel.class, cfg.displayCensor);
                cfg.setMusicSource(ConfigJson.getEnum(obj, "musicSource", MusicSource.class, cfg.musicSource));
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
            obj.addProperty("wordTimedLyrics", wordTimedLyrics);
            obj.addProperty("alwaysShowControls", alwaysShowControls);
            obj.addProperty("displayCensor", displayCensor.name());
            obj.addProperty("musicSource", musicSource.name());
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

    public boolean isWordTimedLyrics() {
        return wordTimedLyrics;
    }

    public void setWordTimedLyrics(boolean wordTimedLyrics) {
        this.wordTimedLyrics = wordTimedLyrics;
    }

    public boolean isAlwaysShowControls() {
        return alwaysShowControls;
    }

    public void setAlwaysShowControls(boolean alwaysShowControls) {
        this.alwaysShowControls = alwaysShowControls;
    }

    public ProfanityLevel getDisplayCensor() {
        return displayCensor;
    }

    public void setDisplayCensor(ProfanityLevel level) {
        this.displayCensor = level == null ? ProfanityLevel.MINIMAL : level;
    }

    public MusicSource getMusicSource() {
        return musicSource;
    }

    public void setMusicSource(MusicSource source) {
        this.musicSource = source == null || source == MusicSource.SPOTIFY ? MusicSource.MUSIC_PLAYERS : source;
    }
}
