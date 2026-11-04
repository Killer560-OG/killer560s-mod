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
 * Song Tag settings (killer560, 2026-10-24). Both OFF by default: sharing because it sends what you listen to to other
 * people (the same reason Send Lyrics To Chat is off), showing because Mod User Tag's "See Other Mod Users", the feature
 * this one rides on, is off by default too.
 */
public final class SongShareConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-spotify-songtag.json");

    private static volatile SongShareConfig instance;

    private volatile boolean shareSong = false;
    private volatile boolean showOthers = false;
    /**
     * My Lyrics In F5 (2026-10-26): the lyric line being sung, over your own head in third person. Local only, never
     * sent. ON by default since 2026-11-03 (killer560: "spotify lyrics arent showing in f5"); a saved file without the
     * {@code lyricsOverHeadV2} marker that still said false was only the old default, so it loads as ON once.
     */
    private volatile boolean lyricsOverHead = true;
    /**
     * Highlight Word In F5 (killer560, 2026-10-26: "have an option to highlight the word just like the hud as well"): the
     * F5 lyric line lights the word being sung exactly as the HUD does. On by default, as the HUD's Word Highlight is.
     */
    private volatile boolean highlightWordInF5 = true;

    /**
     * What Share My Song sends (killer560, 2026-11-01: "have the option to show what word you are on or not as a sharing
     * portion from each player. Same with sharing the name of the song or just lyrics or just the name"). Each sender
     * picks; a receiver draws what arrives. Song Name by default: what it always sent.
     */
    public enum ShareWhat {
        SONG("Song Name"),
        LYRICS("Lyrics"),
        BOTH("Song Name + Lyrics");

        public final String label;

        ShareWhat(String label) {
            this.label = label;
        }

        public boolean song() {
            return this != LYRICS;
        }

        public boolean lyrics() {
            return this != SONG;
        }
    }

    private volatile ShareWhat shareWhat = ShareWhat.SONG;
    /** Share Current Word: a shared lyric line carries each word's time, so receivers light the word being sung. */
    private volatile boolean shareWord = true;
    /**
     * Song In F5 (killer560, 2026-11-01: "add an option for in f5 to also show song title and artist"): your own song
     * line over your head in third person, local only, with or without Share My Song.
     */
    private volatile boolean songInF5 = false;
    /** F5 colors (2026-11-01): off follows the menu theme like the HUD; on uses the two colors below. */
    private volatile boolean customF5Colors = false;
    private volatile int f5TextColor = DEFAULT_F5_TEXT;
    private volatile int f5HighlightColor = DEFAULT_F5_HIGHLIGHT;

    public static final int DEFAULT_F5_TEXT = 0xFFFFFFFF;
    public static final int DEFAULT_F5_HIGHLIGHT = 0xFFFFAA00;

    private SongShareConfig() {
    }

    public static SongShareConfig getInstance() {
        SongShareConfig cfg = instance;
        if (cfg == null) {
            load();
            cfg = instance;
        }
        return cfg;
    }

    public static synchronized void load() {
        SongShareConfig cfg = new SongShareConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.shareSong = ConfigJson.getBool(obj, "shareSong", false);
                cfg.showOthers = ConfigJson.getBool(obj, "showOthers", false);
                cfg.lyricsOverHead = obj.has("lyricsOverHeadV2") ? ConfigJson.getBool(obj, "lyricsOverHead", true) : true;
                cfg.highlightWordInF5 = ConfigJson.getBool(obj, "highlightWordInF5", true);
                cfg.shareWhat = ConfigJson.getEnum(obj, "shareWhat", ShareWhat.class, ShareWhat.SONG);
                cfg.shareWord = ConfigJson.getBool(obj, "shareWord", true);
                cfg.songInF5 = ConfigJson.getBool(obj, "songInF5", false);
                cfg.customF5Colors = ConfigJson.getBool(obj, "customF5Colors", false);
                cfg.f5TextColor = ConfigJson.getInt(obj, "f5TextColor", DEFAULT_F5_TEXT) | 0xFF000000;
                cfg.f5HighlightColor = ConfigJson.getInt(obj, "f5HighlightColor", DEFAULT_F5_HIGHLIGHT) | 0xFF000000;
            } catch (Exception ignored) {
                // a broken file loads as the defaults
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("shareSong", shareSong);
            obj.addProperty("showOthers", showOthers);
            obj.addProperty("lyricsOverHead", lyricsOverHead);
            obj.addProperty("lyricsOverHeadV2", true);
            obj.addProperty("highlightWordInF5", highlightWordInF5);
            obj.addProperty("shareWhat", shareWhat.name());
            obj.addProperty("shareWord", shareWord);
            obj.addProperty("songInF5", songInF5);
            obj.addProperty("customF5Colors", customF5Colors);
            obj.addProperty("f5TextColor", f5TextColor);
            obj.addProperty("f5HighlightColor", f5HighlightColor);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isShareSong() {
        return shareSong;
    }

    public void setShareSong(boolean shareSong) {
        this.shareSong = shareSong;
    }

    public boolean isShowOthers() {
        return showOthers;
    }

    public void setShowOthers(boolean showOthers) {
        this.showOthers = showOthers;
    }

    public boolean isLyricsOverHead() {
        return lyricsOverHead;
    }

    public void setLyricsOverHead(boolean lyricsOverHead) {
        this.lyricsOverHead = lyricsOverHead;
    }

    public boolean isHighlightWordInF5() {
        return highlightWordInF5;
    }

    public void setHighlightWordInF5(boolean highlightWordInF5) {
        this.highlightWordInF5 = highlightWordInF5;
    }

    public ShareWhat getShareWhat() {
        return shareWhat;
    }

    public void setShareWhat(ShareWhat shareWhat) {
        this.shareWhat = shareWhat == null ? ShareWhat.SONG : shareWhat;
    }

    public boolean isShareWord() {
        return shareWord;
    }

    public void setShareWord(boolean shareWord) {
        this.shareWord = shareWord;
    }

    public boolean isSongInF5() {
        return songInF5;
    }

    public void setSongInF5(boolean songInF5) {
        this.songInF5 = songInF5;
    }

    public boolean isCustomF5Colors() {
        return customF5Colors;
    }

    public void setCustomF5Colors(boolean customF5Colors) {
        this.customF5Colors = customF5Colors;
    }

    public int getF5TextColor() {
        return f5TextColor;
    }

    public void setF5TextColor(int argb) {
        this.f5TextColor = argb | 0xFF000000;
    }

    public int getF5HighlightColor() {
        return f5HighlightColor;
    }

    public void setF5HighlightColor(int argb) {
        this.f5HighlightColor = argb | 0xFF000000;
    }
}
