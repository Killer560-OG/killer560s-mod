package com.killer560.hub.splittimers;

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
 * Persisted Blood Rush Splits settings ({@link BloodRushSplits}). Its own file beside Split Timers' so the two can change
 * independently. Off by default, like every new block of the splits.
 */
public final class BloodRushSplitsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-splittimers-bloodrush.json");

    /** How much each rush room shows. */
    public enum Style {
        /** One row a room: the room's time, its key time and (when slow) its door time. */
        COMPACT("Compact"),
        /** Each room's five times on their own lines: door down, last mob, pickup, opened, room total. */
        DETAILED("Detailed");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static BloodRushSplitsConfig instance;

    private boolean enabled = false;
    private Style style = Style.COMPACT;
    /** The rush, room by room, as a Mod Chat line when the Blood door opens. */
    private boolean announceInChat = false;

    private BloodRushSplitsConfig() {
    }

    public static BloodRushSplitsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        BloodRushSplitsConfig cfg = new BloodRushSplitsConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.style = ConfigJson.getEnum(obj, "style", Style.class, Style.COMPACT);
                cfg.announceInChat = ConfigJson.getBool(obj, "announceInChat", false);
            } catch (Exception e) {
                cfg = new BloodRushSplitsConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("style", style.name());
            obj.addProperty("announceInChat", announceInChat);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** On here AND Split Timers on: the rush rows belong to the splits. */
    public boolean isActive() {
        return enabled && SplitTimersConfig.getInstance().isEnabled();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style == null ? Style.COMPACT : style;
    }

    public boolean isAnnounceInChat() {
        return announceInChat;
    }

    public void setAnnounceInChat(boolean announceInChat) {
        this.announceInChat = announceInChat;
    }
}
