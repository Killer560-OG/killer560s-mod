package com.killer560.hub.autokick;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;

/**
 * Persisted Auto Kick settings - see {@link AutoKickFeature} for the logic.
 * <p>
 * killer560's own request: "create auto kick. I really Like Odins. The kick based off of timed comp of a
 * floor and whatnot." Odin removes party members once a run has clearly missed its target time for the
 * current floor, so the party can requeue instead of grinding out a doomed run. This is that feature,
 * built on this mod's own run clock ({@code splittimers.SplitTimersFeature}) and floor detection
 * ({@code secrets.DungeonState}) instead of guessing at either.
 * <p>
 * <b>Safety-first defaults, per killer560's own instructions for this feature</b>: the master switch
 * ({@link #isEnabled()}) and every one of the 14 per-floor target times default OFF/0 (0 = disabled -
 * a floor with no target time never triggers anything), and the action mode defaults to
 * {@link ActionMode#WARN_ONLY} - the destructive modes ({@link ActionMode#KICK_ALL},
 * {@link ActionMode#KICK_SPECIFIC}) are opt-in, exactly like {@code partycommands}' own
 * "Allow Destructive Commands" switch gates its party-mutating commands.
 * <p>
 * No default target times are shipped. A "typical" Catacombs/Master Mode clear time depends heavily on
 * the party's own gear, class levels and secret-route knowledge - there is nothing in this codebase that
 * verifies a real, non-guessed number per floor, and shipping an invented one risks Auto Kick firing (or
 * warning) at exactly the wrong moment on killer560's own real runs. Every floor ships at 0 (disabled)
 * until he sets his own target times for the groups he actually runs with.
 * <p>
 * Per-floor keys are individual JSON entries ("target.F1", "target.M7", ...), same "one key per setting"
 * rule {@code PartyCommandsConfig} documents (2026-09-15 persistence audit) - a hand-edited or
 * type-changed key only resets that one floor's target instead of the whole file.
 */
public final class AutoKickConfig {

    /** F1-F7 and Master Mode M1-M7 - the exact tokens {@code DungeonState#getFloor()} returns from the
     *  real Catacombs sidebar ("The Catacombs (F7)" / "The Catacombs (M7)"). Kuudra is deliberately not
     *  included: nothing in this codebase tracks a Kuudra run's elapsed clock the way
     *  {@code SplitTimersFeature} tracks a Catacombs run (its own class doc scopes Kuudra splits out
     *  entirely), and {@code DungeonState} only ever detects Catacombs floors. Building a second,
     *  unverified elapsed-time source just for Auto Kick would be exactly the kind of guess killer560's
     *  instructions for this feature warn against - Kuudra tiers can be added once a real elapsed-time
     *  source for them exists. */
    public enum Floor {
        F1("F1", "Floor 1"), F2("F2", "Floor 2"), F3("F3", "Floor 3"), F4("F4", "Floor 4"),
        F5("F5", "Floor 5"), F6("F6", "Floor 6"), F7("F7", "Floor 7"),
        M1("M1", "Master 1"), M2("M2", "Master 2"), M3("M3", "Master 3"), M4("M4", "Master 4"),
        M5("M5", "Master 5"), M6("M6", "Master 6"), M7("M7", "Master 7");

        private final String raw;
        private final String label;

        Floor(String raw, String label) {
            this.raw = raw;
            this.label = label;
        }

        public String label() {
            return label;
        }

        String key() {
            return "target." + raw;
        }

        /** @return the {@link Floor} matching {@code DungeonState.getFloor()}'s raw token, or null if it
         *  isn't one Auto Kick knows about (Entrance, an unrecognised value, or not in a dungeon). */
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

    /** What Auto Kick does once a floor's target time is exceeded. Odin only ever kicks; this mod adds a
     *  non-destructive option because killer560's own instructions for this feature required it: "Default
     *  to WARN ONLY - the destructive option must be opt-in." */
    public enum ActionMode {
        /** Prints a chat warning only - no command is ever sent. The safe default. */
        WARN_ONLY("Warn Only"),
        /** Kicks every current teammate (never yourself - see {@link AutoKickFeature}'s class doc for why
         *  that also covers the party leader). */
        KICK_ALL("Kick All"),
        /** Kicks only the members named in {@link #getSpecificMembers()}. */
        KICK_SPECIFIC("Kick Specific");

        private final String label;

        ActionMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Slider ceiling for a per-floor target time - 60 minutes. Not a claim any real floor should take
     *  that long; just generous enough that the slider never clips a legitimately slow Master floor. */
    public static final int MAX_TARGET_SECONDS = 3600;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-autokick.json");

    private static AutoKickConfig instance;

    private boolean enabled = false;
    private ActionMode mode = ActionMode.WARN_ONLY;
    private String specificMembers = "";
    private final EnumMap<Floor, Integer> targetSeconds = new EnumMap<>(Floor.class);

    private AutoKickConfig() {
        for (Floor f : Floor.values()) {
            targetSeconds.put(f, 0);
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
                cfg.specificMembers = ConfigJson.getString(obj, "specificMembers", "");
                for (Floor f : Floor.values()) {
                    cfg.targetSeconds.put(f, clamp(ConfigJson.getInt(obj, f.key(), 0)));
                }
            } catch (Exception ignored) {
                // Unreadable file: keep the per-key defaults above (everything OFF/0) rather than fail load().
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
            obj.addProperty("specificMembers", specificMembers);
            for (Floor f : Floor.values()) {
                obj.addProperty(f.key(), targetSeconds.getOrDefault(f, 0));
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated like every other feature getter - off entirely outside Skyblock/p3sim while "Skyblock Only"
     *  is on, same as {@code PartyCommandsConfig#isEnabled()}. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    /** The real saved value, for the settings screen. */
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

    /** Comma-separated IGNs for {@link ActionMode#KICK_SPECIFIC}, exactly as typed - parsed and validated
     *  at kick time (see {@link AutoKickFeature}), not here, so a half-typed name in the text box doesn't
     *  get silently dropped while the user is still editing it. */
    public String getSpecificMembers() {
        return specificMembers;
    }

    public void setSpecificMembers(String specificMembers) {
        this.specificMembers = specificMembers == null ? "" : specificMembers;
    }

    /** Seconds after the run clock starts before Auto Kick considers this floor's target missed, or 0 if
     *  disabled for this floor. */
    public int getTargetSeconds(Floor floor) {
        return floor == null ? 0 : targetSeconds.getOrDefault(floor, 0);
    }

    public void setTargetSeconds(Floor floor, int seconds) {
        if (floor != null) {
            targetSeconds.put(floor, clamp(seconds));
        }
    }

    private static int clamp(int seconds) {
        return Math.max(0, Math.min(MAX_TARGET_SECONDS, seconds));
    }
}
