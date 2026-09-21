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
     *  light mode", then "add an onyx mode that is our orange theme... instead of onyx call it amber").
     *  SYSTEM is the pre-option behaviour: the browser follows Windows' own app theme. AMBER is this mod's
     *  own theme - dark emulation plus a stylesheet recolouring YouTube's chrome to the menu's accent, so
     *  the Shorts window looks like part of the mod rather than a browser someone parked on top of it. */
    public enum Theme {
        SYSTEM("System"), DARK("Dark"), LIGHT("Light"), AMBER("Amber");

        public final String label;

        Theme(String label) {
            this.label = label;
        }

        public Theme next() {
            Theme[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    /** Which site/app the companion window opens (2026-09-21 expansion, killer560 item 8.8: "YT Shorts
     *  expansion: TikTok, Reels, any site... normal videos, Twitch"). Every preset is just a different
     *  {@code --app=} URL on the SAME persistent profile {@link BrowserLauncher} already uses - Chromium
     *  keeps cookies per-origin within one profile, so being signed into YouTube there doesn't affect (or
     *  get affected by) also being signed into TikTok/Instagram/Twitch in the same window on other days.
     *  {@code signInUrl} is only a real, normal login page for that site - see
     *  {@link ShortsFeature#openSignInWindow}, which never touches credentials itself. CUSTOM has no fixed
     *  URL; it uses {@link #customUrl} instead (killer560's "any site"). */
    public enum Site {
        YOUTUBE_SHORTS("YouTube Shorts", BrowserLauncher.SHORTS_URL,
                "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com/shorts"),
        YOUTUBE("YouTube (normal videos)", "https://www.youtube.com",
                "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com"),
        TIKTOK("TikTok", "https://www.tiktok.com/foryou", "https://www.tiktok.com/login"),
        INSTAGRAM_REELS("Instagram Reels", "https://www.instagram.com/reels/", "https://www.instagram.com/accounts/login/"),
        TWITCH("Twitch", "https://www.twitch.tv/", "https://www.twitch.tv/login"),
        CUSTOM("Custom URL", null, null);

        public final String label;
        /** App-mode launch URL, or null for CUSTOM (see {@link #customUrl}). */
        public final String defaultUrl;
        /** A real login page for this site, or null for CUSTOM (its own URL is used as the "sign in" target). */
        public final String signInUrl;

        Site(String label, String defaultUrl, String signInUrl) {
            this.label = label;
            this.defaultUrl = defaultUrl;
            this.signInUrl = signInUrl;
        }

        public Site next() {
            Site[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    /** How the window is positioned (2026-09-21 expansion). ANCHORED is the original, sole behaviour -
     *  Anchor/Size/Margin below. CUSTOM is a free rect set once by turning Edit Window off (see
     *  {@link ShortsFeature#toggleEditMode}). DVD bounces the window around Minecraft's client area like
     *  the mod's own DVD screensaver feature - see {@link ShortsFeature} for why this reading of "DVD
     *  compatibility" was chosen over the alternative (the DVD boxes treating the Shorts window as a wall). */
    public enum PlacementMode {
        ANCHORED("Anchored"), CUSTOM("Custom"), DVD("DVD Bounce");

        public final String label;

        PlacementMode(String label) {
            this.label = label;
        }

        public PlacementMode next() {
            PlacementMode[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    public static final int MIN_ZOOM_PERCENT = 50;
    public static final int MAX_ZOOM_PERCENT = 200;
    public static final int MIN_CUSTOM_SIZE = 120;

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

    // ---- 2026-09-21 expansion (killer560 item 8.8) - every field below defaults to the pre-expansion
    // behaviour (YouTube Shorts, anchored placement, 100% zoom, guard off) so an existing config keeps
    // working exactly as before until killer560 changes one of these himself. ----
    private Site site = Site.YOUTUBE_SHORTS;
    /** Only used when {@link #site} is {@link Site#CUSTOM}; stored exactly as typed. */
    private String customUrl = "";
    private PlacementMode placementMode = PlacementMode.ANCHORED;
    /** CUSTOM placement rect, screen pixels relative to Minecraft's client-area top-left. Set by
     *  {@link ShortsFeature#toggleEditMode} when Edit Window is turned back off. */
    private int customX = 0;
    private int customY = 0;
    private int customW = 340;
    private int customH = 605;
    /** Chromium {@code --force-device-scale-factor} at launch (100 = unchanged). Applies on next
     *  launch/relaunch, not live - see {@link ShortsFeature} / the Zoom tooltip for why. */
    private int zoomPercent = 100;
    /** When on, Next/Previous skip their ArrowDown/Up key fallback while a YouTube Shorts comments panel
     *  looks open, so a keybind press doesn't yank the feed out from under him mid-scroll-through-comments.
     *  Best-effort/YouTube-only - see {@link ShortsFeature}. */
    private boolean commentsScrollGuard = false;

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
                cfg.site = ConfigJson.getEnum(o, "site", Site.class, Site.YOUTUBE_SHORTS);
                cfg.customUrl = o.has("customUrl") && o.get("customUrl").isJsonPrimitive() ? o.get("customUrl").getAsString() : "";
                cfg.placementMode = ConfigJson.getEnum(o, "placementMode", PlacementMode.class, PlacementMode.ANCHORED);
                cfg.customX = intOr(o, "customX", 0);
                cfg.customY = intOr(o, "customY", 0);
                cfg.customW = Math.max(MIN_CUSTOM_SIZE, intOr(o, "customW", 340));
                cfg.customH = Math.max(MIN_CUSTOM_SIZE, intOr(o, "customH", 605));
                cfg.zoomPercent = clamp(intOr(o, "zoomPercent", 100), MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT);
                cfg.commentsScrollGuard = ConfigJson.getBool(o, "commentsScrollGuard", false);
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
            o.addProperty("site", site.name());
            o.addProperty("customUrl", customUrl);
            o.addProperty("placementMode", placementMode.name());
            o.addProperty("customX", customX);
            o.addProperty("customY", customY);
            o.addProperty("customW", customW);
            o.addProperty("customH", customH);
            o.addProperty("zoomPercent", zoomPercent);
            o.addProperty("commentsScrollGuard", commentsScrollGuard);
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

    public Site getSite() {
        return site;
    }

    public void setSite(Site site) {
        this.site = site == null ? Site.YOUTUBE_SHORTS : site;
    }

    public String getCustomUrl() {
        return customUrl;
    }

    public void setCustomUrl(String customUrl) {
        this.customUrl = customUrl == null ? "" : customUrl.strip();
    }

    public PlacementMode getPlacementMode() {
        return placementMode;
    }

    public void setPlacementMode(PlacementMode placementMode) {
        this.placementMode = placementMode == null ? PlacementMode.ANCHORED : placementMode;
    }

    public int getCustomX() {
        return customX;
    }

    public void setCustomX(int customX) {
        this.customX = customX;
    }

    public int getCustomY() {
        return customY;
    }

    public void setCustomY(int customY) {
        this.customY = customY;
    }

    public int getCustomW() {
        return customW;
    }

    public void setCustomW(int customW) {
        this.customW = Math.max(MIN_CUSTOM_SIZE, customW);
    }

    public int getCustomH() {
        return customH;
    }

    public void setCustomH(int customH) {
        this.customH = Math.max(MIN_CUSTOM_SIZE, customH);
    }

    public int getZoomPercent() {
        return zoomPercent;
    }

    public void setZoomPercent(int zoomPercent) {
        this.zoomPercent = clamp(zoomPercent, MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT);
    }

    public boolean isCommentsScrollGuard() {
        return commentsScrollGuard;
    }

    public void setCommentsScrollGuard(boolean commentsScrollGuard) {
        this.commentsScrollGuard = commentsScrollGuard;
    }
}
