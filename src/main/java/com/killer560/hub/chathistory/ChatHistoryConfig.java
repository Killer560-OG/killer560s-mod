package com.killer560.hub.chathistory;

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
 * Persisted Chat History settings (killer560, 2026-10-18: "make an unlimited chat limit setting"). Vanilla keeps the last
 * {@value #VANILLA_LIMIT} chat messages (and the same number of wrapped lines) and forgets everything older; with
 * Unlimited Chat History on, {@code chathistory/mixin/ChatHistoryMixin} lifts that cap so the whole session stays
 * scrollable. Off by default: an hour of Hypixel chat is thousands of lines held in memory.
 */
public final class ChatHistoryConfig {

    /** Vanilla's {@code ChatComponent.MAX_CHAT_HISTORY}. */
    public static final int VANILLA_LIMIT = 100;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-chathistory.json");

    private static volatile ChatHistoryConfig instance;

    /**
     * The effective chat history (killer560, 2026-11-01): vanilla's 100, a chosen limit, or no limit. Since 2026-11-02
     * it is derived from the {@link #isEnabled() switch} and the {@link Length}: Off is the switch off, whatever length
     * is kept for the next time it goes on.
     */
    public enum Mode {
        OFF("Off"), LIMITED("Limited"), UNLIMITED("Unlimited");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** How long the history is while the switch is ON (killer560, 2026-11-02: "this needs an actual toggle"). */
    public enum Length {
        LIMITED("Limited"), UNLIMITED("Unlimited");

        public final String label;

        Length(String label) {
            this.label = label;
        }
    }

    public static final int LIMIT_MIN = 200;
    public static final int LIMIT_MAX = 10000;
    public static final int LIMIT_STEP = 200;

    /** Off by default: vanilla's 100 lines. */
    private boolean enabled = false;
    /** Kept while the switch is off, so OFF then ON comes back to the same length. */
    private Length length = Length.LIMITED;
    private int customLimit = 1000;

    private ChatHistoryConfig() {
    }

    public static ChatHistoryConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        ChatHistoryConfig cfg = new ChatHistoryConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                if (obj.has("enabled")) {
                    cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                    if (obj.has("length")) {
                        try {
                            cfg.length = Length.valueOf(obj.get("length").getAsString());
                        } catch (Exception ignored) {
                            cfg.length = Length.LIMITED;
                        }
                    }
                } else if (obj.has("mode")) {
                    // Files from 2026-11-01 to 11-02 (one Off/Limited/Unlimited selector): Off is the switch off with
                    // the default length, any other mode the switch on at that length.
                    Mode m;
                    try {
                        m = Mode.valueOf(obj.get("mode").getAsString());
                    } catch (Exception ignored) {
                        m = Mode.OFF;
                    }
                    cfg.setMode(m);
                } else if (ConfigJson.getBool(obj, "unlimited", false)) {
                    cfg.setMode(Mode.UNLIMITED); // files from before the Limited mode (2026-11-01)
                }
                if (obj.has("limit")) {
                    try {
                        cfg.setCustomLimit(obj.get("limit").getAsInt());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                cfg = new ChatHistoryConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("length", length.name());
            obj.addProperty("limit", customLimit);
            // No "mode" or "unlimited" key any more: load() reads them only from a file with no "enabled" (before
            // 2026-11-02), so a saved copy would be a key load() ignores, which hand edits and profile imports silently
            // lost (testkit 310).
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isUnlimited() {
        return getMode() == Mode.UNLIMITED;
    }

    public void setUnlimited(boolean unlimited) {
        setMode(unlimited ? Mode.UNLIMITED : Mode.OFF);
    }

    /** The Chat History switch: OFF is vanilla's 100 lines. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The length used while the switch is ON (kept while it is off). */
    public Length getLength() {
        return length;
    }

    public void setLength(Length length) {
        this.length = length == null ? Length.LIMITED : length;
    }

    /** The effective history: Off while the switch is off, else the chosen length. */
    public Mode getMode() {
        if (!enabled) {
            return Mode.OFF;
        }
        return length == Length.UNLIMITED ? Mode.UNLIMITED : Mode.LIMITED;
    }

    /** Off turns the switch off and keeps the length; Limited or Unlimited turns it on at that length. */
    public void setMode(Mode mode) {
        if (mode == null || mode == Mode.OFF) {
            enabled = false;
            return;
        }
        enabled = true;
        length = mode == Mode.UNLIMITED ? Length.UNLIMITED : Length.LIMITED;
    }

    public int getCustomLimit() {
        return customLimit;
    }

    /** Clamped to 200..10000 and snapped to a multiple of 200. */
    public void setCustomLimit(int limit) {
        int snapped = Math.round(limit / (float) LIMIT_STEP) * LIMIT_STEP;
        this.customLimit = Math.max(LIMIT_MIN, Math.min(LIMIT_MAX, snapped));
    }

    /**
     * The cap the chat window applies, given the cap already in place ({@code current}: vanilla's 100, or another
     * chat-history mod's). Off passes it through; Limited raises it to the chosen limit (never lowers another mod's
     * larger one); Unlimited lifts it.
     */
    public static int apply(int current) {
        ChatHistoryConfig cfg = getInstance();
        return switch (cfg.getMode()) {
            case OFF -> current;
            case LIMITED -> Math.max(current, cfg.customLimit);
            case UNLIMITED -> Integer.MAX_VALUE;
        };
    }

    /** How many messages the chat window keeps with no other chat-history mod present. */
    public static int limit() {
        return apply(VANILLA_LIMIT);
    }
}
