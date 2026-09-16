package com.killer560.hub.shorts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "YT Shorts" settings - see {@link ShortsFeature}. Ships disabled, every keybind unset. */
public final class ShortsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-shorts.json");

    public static final int MIN_SIZE_PERCENT = 20;
    public static final int MAX_SIZE_PERCENT = 100;
    public static final int MAX_MARGIN = 200;
    public static final int MIN_OPACITY = 20;

    /** Where the 9:16 window sits over Minecraft's client area. */
    public enum Anchor {
        RIGHT_CENTER("Right Center"),
        RIGHT_TOP("Right Top"),
        RIGHT_BOTTOM("Right Bottom"),
        LEFT_CENTER("Left Center"),
        LEFT_TOP("Left Top"),
        LEFT_BOTTOM("Left Bottom");

        public final String label;

        Anchor(String label) {
            this.label = label;
        }

        public Anchor next() {
            Anchor[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    /** Page colour scheme the Shorts window is asked to use (2026-09-16, killer560: "make an option for dark or
     *  light mode"). SYSTEM is the pre-option behaviour: the browser follows Windows' own app theme. */
    public enum Theme {
        SYSTEM("System"), DARK("Dark"), LIGHT("Light");

        public final String label;

        Theme(String label) {
            this.label = label;
        }

        public Theme next() {
            Theme[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    private static ShortsConfig instance;

    private boolean enabled = false;
    private Anchor anchor = Anchor.RIGHT_CENTER;
    private Theme theme = Theme.SYSTEM;
    private int sizePercent = 60;
    private int margin = 10;
    private int opacity = 100;
    private boolean hideWhenUnfocused = false;
    /** The show/hide toggle state (so a hidden overlay stays hidden across restarts). */
    private boolean hidden = false;
    private boolean pauseWhenHidden = false;
    /** -1 = never touch YouTube's own volume. */
    private int volume = -1;
    private int toggleKey = -1;
    private int nextKey = -1;
    private int previousKey = -1;
    private int playPauseKey = -1;
    private int muteKey = -1;

    private ShortsConfig() {
    }

    public static ShortsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ShortsConfig cfg = new ShortsConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", false);
                cfg.anchor = ConfigJson.getEnum(o, "anchor", Anchor.class, Anchor.RIGHT_CENTER);
                cfg.theme = ConfigJson.getEnum(o, "theme", Theme.class, Theme.SYSTEM);
                cfg.sizePercent = clamp(intOr(o, "sizePercent", 60), MIN_SIZE_PERCENT, MAX_SIZE_PERCENT);
                cfg.margin = clamp(intOr(o, "margin", 10), 0, MAX_MARGIN);
                cfg.opacity = clamp(intOr(o, "opacity", 100), MIN_OPACITY, 100);
                cfg.hideWhenUnfocused = ConfigJson.getBool(o, "hideWhenUnfocused", false);
                cfg.hidden = ConfigJson.getBool(o, "hidden", false);
                cfg.pauseWhenHidden = ConfigJson.getBool(o, "pauseWhenHidden", false);
                cfg.volume = clamp(intOr(o, "volume", -1), -1, 100);
                cfg.toggleKey = com.killer560.hub.util.KeyUtil.sanitize(intOr(o, "toggleKey", -1));
                cfg.nextKey = com.killer560.hub.util.KeyUtil.sanitize(intOr(o, "nextKey", -1));
                cfg.previousKey = com.killer560.hub.util.KeyUtil.sanitize(intOr(o, "previousKey", -1));
                cfg.playPauseKey = com.killer560.hub.util.KeyUtil.sanitize(intOr(o, "playPauseKey", -1));
                cfg.muteKey = com.killer560.hub.util.KeyUtil.sanitize(intOr(o, "muteKey", -1));
            } catch (Exception e) {
                cfg = new ShortsConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("anchor", anchor.name());
            o.addProperty("theme", theme.name());
            o.addProperty("sizePercent", sizePercent);
            o.addProperty("margin", margin);
            o.addProperty("opacity", opacity);
            o.addProperty("hideWhenUnfocused", hideWhenUnfocused);
            o.addProperty("hidden", hidden);
            o.addProperty("pauseWhenHidden", pauseWhenHidden);
            o.addProperty("volume", volume);
            o.addProperty("toggleKey", toggleKey);
            o.addProperty("nextKey", nextKey);
            o.addProperty("previousKey", previousKey);
            o.addProperty("playPauseKey", playPauseKey);
            o.addProperty("muteKey", muteKey);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int intOr(JsonObject o, String key, int def) {
        return ConfigJson.getInt(o, key, def);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Anchor getAnchor() {
        return anchor;
    }

    public void setAnchor(Anchor anchor) {
        this.anchor = anchor == null ? Anchor.RIGHT_CENTER : anchor;
    }

    public Theme getTheme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = theme == null ? Theme.SYSTEM : theme;
    }

    public int getSizePercent() {
        return sizePercent;
    }

    public void setSizePercent(int sizePercent) {
        this.sizePercent = clamp(sizePercent, MIN_SIZE_PERCENT, MAX_SIZE_PERCENT);
    }

    public int getMargin() {
        return margin;
    }

    public void setMargin(int margin) {
        this.margin = clamp(margin, 0, MAX_MARGIN);
    }

    public int getOpacity() {
        return opacity;
    }

    public void setOpacity(int opacity) {
        this.opacity = clamp(opacity, MIN_OPACITY, 100);
    }

    public boolean isHideWhenUnfocused() {
        return hideWhenUnfocused;
    }

    public void setHideWhenUnfocused(boolean hideWhenUnfocused) {
        this.hideWhenUnfocused = hideWhenUnfocused;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public boolean isPauseWhenHidden() {
        return pauseWhenHidden;
    }

    public void setPauseWhenHidden(boolean pauseWhenHidden) {
        this.pauseWhenHidden = pauseWhenHidden;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = clamp(volume, -1, 100);
    }

    public int getToggleKey() {
        return toggleKey;
    }

    public void setToggleKey(int toggleKey) {
        this.toggleKey = toggleKey;
    }

    public int getNextKey() {
        return nextKey;
    }

    public void setNextKey(int nextKey) {
        this.nextKey = nextKey;
    }

    public int getPreviousKey() {
        return previousKey;
    }

    public void setPreviousKey(int previousKey) {
        this.previousKey = previousKey;
    }

    public int getPlayPauseKey() {
        return playPauseKey;
    }

    public void setPlayPauseKey(int playPauseKey) {
        this.playPauseKey = playPauseKey;
    }

    public int getMuteKey() {
        return muteKey;
    }

    public void setMuteKey(int muteKey) {
        this.muteKey = muteKey;
    }
}
