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
}
