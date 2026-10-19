package com.killer560.hub.streamer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streamer mode (killer560, 2026-10-19): on the cheat build, {@code /streamer} (or {@code /streamermode}) turns it on, and the
 * option picked in Home's Streamer Mode setting says what it does. Running the command again turns it off and restores
 * everything. Only the cheat build has it: every answer here is {@code false} on the legit build, and the command is not
 * registered there.
 * <ul>
 *   <li>{@link Option#DISABLE_CHEATS}: every automated action is refused (the action gate) and the cheat parts of the
 *       GUI are hidden.</li>
 *   <li>{@link Option#HIDE_CHEAT_UI}: the cheat parts of the GUI are hidden; the cheats keep running.</li>
 * </ul>
 * Either option hides the cheat tabs and the "cheat" word from the GUI, so nothing on screen says which build this is.
 */
public final class StreamerMode {

    public enum Option {
        DISABLE_CHEATS("Disable cheats"),
        HIDE_CHEAT_UI("Hide cheat UI, keep cheats running");

        public final String label;

        Option(String label) {
            this.label = label;
        }

        public Option next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-streamer.json");

    private static boolean active;
    private static Option option = Option.HIDE_CHEAT_UI;
    private static boolean loaded;

    private StreamerMode() {
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!BuildVariant.CHEAT_FEATURES_ENABLED || !Files.exists(CONFIG_PATH)) {
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            active = ConfigJson.getBool(obj, "active", false);
            option = ConfigJson.getEnum(obj, "option", Option.class, Option.HIDE_CHEAT_UI);
        } catch (Exception e) {
            active = false;
        }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("active", active);
            obj.addProperty("option", option.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Streamer mode is on (cheat build only). */
    public static boolean active() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return false;
        }
        ensureLoaded();
        return active;
    }

    /** Automated actions are refused: on, with the Disable cheats option. */
    public static boolean disablesCheats() {
        return active() && option == Option.DISABLE_CHEATS;
    }

    /** The cheat parts of the GUI are hidden: on, with either option. */
    public static boolean hidesCheatUi() {
        return active();
    }

    public static Option option() {
        ensureLoaded();
        return option;
    }

    public static void setOption(Option value) {
        ensureLoaded();
        option = value == null ? Option.HIDE_CHEAT_UI : value;
        save();
    }

    /** Flips streamer mode; returns the new state. */
    public static boolean toggle() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return false;
        }
        ensureLoaded();
        active = !active;
        save();
        return active;
    }
}
