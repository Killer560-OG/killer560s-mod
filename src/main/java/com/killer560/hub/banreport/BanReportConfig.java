package com.killer560.hub.banreport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SessionLogTap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto Report Bans' settings (killer560, 2026-11-01, to-do Doc "BAN LOGGER"): the switch, OFF by default and never turned
 * on by anything but the player (a profile someone else made cannot change it: ProfileManager's import-locked fields), and
 * the bookkeeping that makes a ban reported once: the hashed keys of the bans already sent and when the last sends were.
 */
public final class BanReportConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-banreport.json");
    /** At most this many reported ban keys and send times are kept. */
    static final int KEEP = 50;

    private static BanReportConfig instance;

    private boolean enabled;
    private final List<String> reported = new ArrayList<>();
    private final List<Long> sentAt = new ArrayList<>();

    private BanReportConfig() {
    }

    public static BanReportConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        BanReportConfig cfg = new BanReportConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                JsonArray keys = ConfigJson.getArray(obj, "reported");
                if (keys != null) {
                    for (JsonElement e : keys) {
                        if (e.isJsonPrimitive() && e.getAsString().matches("[0-9a-f]{64}")) {
                            cfg.reported.add(e.getAsString());
                        }
                    }
                }
                JsonArray times = ConfigJson.getArray(obj, "sentAt");
                if (times != null) {
                    for (JsonElement e : times) {
                        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
                            cfg.sentAt.add(e.getAsLong());
                        }
                    }
                }
            } catch (Exception e) {
                cfg = new BanReportConfig();
            }
        }
        trim(cfg.reported);
        trim(cfg.sentAt);
        instance = cfg;
        SessionLogTap.setActive(cfg.enabled);
    }

    private static <T> void trim(List<T> list) {
        while (list.size() > KEEP) {
            list.remove(0);
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            JsonArray keys = new JsonArray();
            reported.forEach(keys::add);
            obj.add("reported", keys);
            JsonArray times = new JsonArray();
            sentAt.forEach(times::add);
            obj.add("sentAt", times);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Also starts or stops the session log tap: nothing is kept in memory while this is off. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        SessionLogTap.setActive(enabled);
        if (!enabled) {
            SessionLogTap.clear();
        }
    }

    public boolean wasReported(String banKey) {
        return reported.contains(banKey);
    }

    public void markReported(String banKey) {
        if (!reported.contains(banKey)) {
            reported.add(banKey);
            trim(reported);
        }
    }

    public List<Long> sentAt() {
        return List.copyOf(sentAt);
    }

    public void noteSent(long now) {
        sentAt.add(now);
        trim(sentAt);
    }

    /** Test hook: forget every reported ban and send time (the switch is left as it is). */
    public void clearHistoryForTest() {
        reported.clear();
        sentAt.clear();
    }
}
