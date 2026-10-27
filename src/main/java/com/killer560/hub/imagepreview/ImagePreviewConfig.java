package com.killer560.hub.imagepreview;

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
 * Image Preview settings (see {@link ImagePreviewFeature}). On by default: it only draws a picture while the chat is
 * open and the mouse is on a link, and with Trusted Hosts Only (also the default) it only ever fetches from imgur and
 * Discord, so hovering a link never reaches a server the player did not already trust by clicking.
 */
public final class ImagePreviewConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-imagepreview.json");

    public static final int MIN_SIZE = 64;
    public static final int MAX_SIZE = 400;
    public static final int MIN_CACHE_MB = 10;
    public static final int MAX_CACHE_MB = 1000;

    private static ImagePreviewConfig instance;

    private boolean enabled = true;
    /** Longest side of the floating preview, in GUI pixels. */
    private int maxPreviewSize = 160;
    /** False (the default): only imgur and Discord links are fetched. True: any http(s) host (never a private address). */
    private boolean anyHost = false;
    private boolean playGifs = true;
    /** Disk cache cap in MB; the oldest-used pictures are deleted past it. */
    private int cacheSizeMb = 100;

    private ImagePreviewConfig() {
    }

    public static ImagePreviewConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ImagePreviewConfig cfg = new ImagePreviewConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
                cfg.maxPreviewSize = clamp(ConfigJson.getInt(obj, "maxPreviewSize", 160), MIN_SIZE, MAX_SIZE);
                cfg.anyHost = ConfigJson.getBool(obj, "anyHost", false);
                cfg.playGifs = ConfigJson.getBool(obj, "playGifs", true);
                cfg.cacheSizeMb = clamp(ConfigJson.getInt(obj, "cacheSizeMb", 100), MIN_CACHE_MB, MAX_CACHE_MB);
            } catch (Exception e) {
                cfg = new ImagePreviewConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("maxPreviewSize", maxPreviewSize);
            obj.addProperty("anyHost", anyHost);
            obj.addProperty("playGifs", playGifs);
            obj.addProperty("cacheSizeMb", cacheSizeMb);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxPreviewSize() {
        return maxPreviewSize;
    }

    public void setMaxPreviewSize(int maxPreviewSize) {
        this.maxPreviewSize = clamp(maxPreviewSize, MIN_SIZE, MAX_SIZE);
    }

    public boolean isAnyHost() {
        return anyHost;
    }

    public void setAnyHost(boolean anyHost) {
        this.anyHost = anyHost;
    }

    public boolean isPlayGifs() {
        return playGifs;
    }

    public void setPlayGifs(boolean playGifs) {
        this.playGifs = playGifs;
    }

    public int getCacheSizeMb() {
        return cacheSizeMb;
    }

    public void setCacheSizeMb(int cacheSizeMb) {
        this.cacheSizeMb = clamp(cacheSizeMb, MIN_CACHE_MB, MAX_CACHE_MB);
    }
}
