package com.killer560.hub.autokick;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;

/**
 * Persisted Auto Kick settings - see {@link AutoKickFeature} for the logic.
 * <p>
 * killer560, 2026-10-09: "Auto kick is only something to be used during the party finder stage while you are queuing
 * for a run ... Each floor should have a bar that goes from 30s to 4m; for F7 make it go to 6 and for M7 make it go to
 * 7." So each floor's number is the slowest timed completion (S+ personal best) a Party Finder joiner may have on
 * that floor: 30 s to 4 min, F7 to 6 min, M7 to 7 min. There is no "disabled" value any more; a floor ships at the top
 * of its range, the most lenient requirement, and the master switch and Warn Only stay the safe defaults.
 * <p>
 * Per-floor keys are individual JSON entries ("target.F1", "target.M7", ...): a hand-edited key only resets that one
 * floor. An old file's 0 ("disabled") reads as the top of the range.
 */
public final class AutoKickConfig {

    /** Every floor's slider starts here. */
    public static final int MIN_TARGET_SECONDS = 30;

    /** F1-F7 and Master Mode M1-M7 - the tokens {@code DungeonState#getFloor()} returns ("F7" / "M7"). */
    public enum Floor {
        F1("F1", "Floor 1", 240), F2("F2", "Floor 2", 240), F3("F3", "Floor 3", 240), F4("F4", "Floor 4", 240),
        F5("F5", "Floor 5", 240), F6("F6", "Floor 6", 240), F7("F7", "Floor 7", 360),
        M1("M1", "Master 1", 240), M2("M2", "Master 2", 240), M3("M3", "Master 3", 240), M4("M4", "Master 4", 240),
        M5("M5", "Master 5", 240), M6("M6", "Master 6", 240), M7("M7", "Master 7", 420);

        private final String raw;
        private final String label;
        private final int maxSeconds;

        Floor(String raw, String label, int maxSeconds) {
            this.raw = raw;
            this.label = label;
            this.maxSeconds = maxSeconds;
        }

        public String label() {
            return label;
        }

        /** The top of this floor's slider: 4 min, F7 6 min, M7 7 min. */
        public int maxSeconds() {
            return maxSeconds;
        }

        public boolean master() {
            return raw.charAt(0) == 'M';
        }

        /** 1-7. */
        public int number() {
            return raw.charAt(1) - '0';
        }

        String key() {
            return "target." + raw;
        }

        /** @return the {@link Floor} for {@code DungeonState.getFloor()}'s token, or null (Entrance, unknown). */
        public static Floor fromRaw(String raw) {
            if (raw == null) {
                return null;
            }
            for (Floor f : values()) {
                if (f.raw.equalsIgnoreCase(raw)) {
                    return f;
                }
            }
            return null;
        }
    }

    /** What Auto Kick does to a joiner who misses the floor's time. */
    public enum ActionMode {
        /** Prints a chat warning only - no command is ever sent. The safe default. */
        WARN_ONLY("Warn Only"),
        /** Kicks the joiner ({@code /p kick <name>}). An old "Kick Specific" file reads as Warn Only. */
        KICK_ALL("Kick");

        private final String label;

        ActionMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-autokick.json");

    private static AutoKickConfig instance;

    private boolean enabled = false;
    private ActionMode mode = ActionMode.WARN_ONLY;
    /** The floor being queued: null = the floor of the last run you were in (see {@link AutoKickFeature#queueFloor}). */
    private Floor queueFloor = null;
    private final EnumMap<Floor, Integer> targetSeconds = new EnumMap<>(Floor.class);

    private AutoKickConfig() {
        for (Floor f : Floor.values()) {
            targetSeconds.put(f, f.maxSeconds());
        }
    }

    public static AutoKickConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AutoKickConfig cfg = new AutoKickConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.mode = ConfigJson.getEnum(obj, "mode", ActionMode.class, ActionMode.WARN_ONLY);
                cfg.queueFloor = Floor.fromRaw(ConfigJson.getString(obj, "queueFloor", ""));
                for (Floor f : Floor.values()) {
                    cfg.setTargetSeconds(f, ConfigJson.getInt(obj, f.key(), 0));
                }
            } catch (Exception ignored) {
                // Unreadable file: keep the defaults above (off, Warn Only, every floor at the top of its range).
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("mode", mode.name());
            obj.addProperty("queueFloor", queueFloor == null ? "" : queueFloor.name());
            for (Floor f : Floor.values()) {
                obj.addProperty(f.key(), getTargetSeconds(f));
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated like every other feature getter - off outside Skyblock while "Skyblock Only" is on. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ActionMode getMode() {
        return mode;
    }

    public void setMode(ActionMode mode) {
        this.mode = mode == null ? ActionMode.WARN_ONLY : mode;
    }

    /** The floor chosen on the tab, or null for "the last run's floor". */
    public Floor getQueueFloor() {
        return queueFloor;
    }

    public void setQueueFloor(Floor floor) {
        this.queueFloor = floor;
    }

    /** The slowest S+ personal best a joiner may have on {@code floor}, in seconds. */
    public int getTargetSeconds(Floor floor) {
        return floor == null ? 0 : targetSeconds.getOrDefault(floor, floor.maxSeconds());
    }

    /** Clamped into the floor's range; 0 (an old file's "disabled") is the top of it. */
    public void setTargetSeconds(Floor floor, int seconds) {
        if (floor != null) {
            int v = seconds <= 0 ? floor.maxSeconds() : seconds;
            targetSeconds.put(floor, Math.max(MIN_TARGET_SECONDS, Math.min(floor.maxSeconds(), v)));
        }
    }
}
