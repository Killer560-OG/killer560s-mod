package com.killer560.hub.crashreport;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto Crash Reports' settings (killer560, 2026-11-04): the switch (OFF by default, only the player turns it on: a profile
 * never changes it, ProfileManager's import-locked fields), whether his name and UUID may go on a report, when he turned it
 * on (a crash from before that is never sent), and the hashed keys of the crashes already sent and when.
 */
public final class CrashReportConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-crashreport.json");
    /** At most this many sent crash keys and send times are kept. */
    static final int KEEP = 50;

    private static CrashReportConfig instance;

    private boolean enabled;
    private boolean shareName;
    private long enabledAt;
    private final List<String> reported = new ArrayList<>();
    private final List<Long> sentAt = new ArrayList<>();

    private CrashReportConfig() {
    }

    public static synchronized CrashReportConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        CrashReportConfig cfg = new CrashReportConfig();
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.shareName = ConfigJson.getBool(obj, "shareName", false);
                cfg.enabledAt = obj.has("enabledAt") && obj.get("enabledAt").isJsonPrimitive() ? obj.get("enabledAt").getAsLong() : 0;
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
            }
        } catch (Exception e) {
            cfg = new CrashReportConfig();
        }
        trim(cfg.reported);
        trim(cfg.sentAt);
        instance = cfg;
    }

    private static <T> void trim(List<T> list) {
        while (list.size() > KEEP) {
            list.remove(0);
        }
    }

    /** Synchronized: a live crash saves from the crashing thread while the client thread may save too. */
    public synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("shareName", shareName);
            obj.addProperty("enabledAt", enabledAt);
            JsonArray keys = new JsonArray();
            reported.forEach(keys::add);
            obj.add("reported", keys);
            JsonArray times = new JsonArray();
            sentAt.forEach(times::add);
            obj.add("sentAt", times);
            Files.writeString(CONFIG_PATH, new GsonBuilder().setPrettyPrinting().create().toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Turning it on stamps the time: only a crash after it is ever sent. */
    public synchronized void setEnabled(boolean enabled) {
        if (enabled && !this.enabled) {
            enabledAt = System.currentTimeMillis();
        }
        this.enabled = enabled;
    }

    public boolean isShareName() {
        return shareName;
    }

    public void setShareName(boolean shareName) {
        this.shareName = shareName;
    }

    public long enabledAt() {
        return enabledAt;
    }

    public synchronized boolean wasReported(String key) {
        return reported.contains(key);
    }

    public synchronized void markReported(String key) {
        if (!reported.contains(key)) {
            reported.add(key);
            trim(reported);
        }
    }

    public synchronized List<Long> sentAt() {
        return List.copyOf(sentAt);
    }

    public synchronized void noteSent(long now) {
        sentAt.add(now);
        trim(sentAt);
    }

    /** Test hook: forget every sent crash and send time and set when the switch went on (the switch itself stays). */
    public synchronized void clearHistoryForTest(long enabledAt) {
        reported.clear();
        sentAt.clear();
        this.enabledAt = enabledAt;
    }
}
