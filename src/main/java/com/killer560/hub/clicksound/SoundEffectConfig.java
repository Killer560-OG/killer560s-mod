package com.killer560.hub.clicksound;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Sound Effects tab's settings (killer560, 2026-10-18): for each {@link SoundEffect}, whether it is on and which sound
 * replaces the game's. Off by default, and Default plays the game's own sound, so nothing changes until he turns one on.
 * Stored as {@code killer560smod-sound-effects.json}: {@code {"arrow_hit": {"on": true, "choice": "soft"}, ...}}.
 */
public final class SoundEffectConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-soundeffects");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-sound-effects.json");

    private static SoundEffectConfig instance;

    /** Indexed by {@link SoundEffect#ordinal()}: the game's sounds are checked on every play, so no boxing or iterators. */
    private final boolean[] on = new boolean[SoundEffect.values().length];
    private final String[] choice = new String[on.length];
    /** True while at least one effect is on; kept up to date by {@link #setOn}. */
    private boolean anyOn;

    private SoundEffectConfig() {
        for (int i = 0; i < choice.length; i++) {
            choice[i] = ClickSounds.DEFAULT_ID;
        }
    }

    public static SoundEffectConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        SoundEffectConfig cfg = new SoundEffectConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (SoundEffect e : SoundEffect.values()) {
                    if (obj.has(e.key) && obj.get(e.key).isJsonObject()) {
                        JsonObject row = obj.getAsJsonObject(e.key);
                        cfg.setOn(e, row.has("on") && row.get("on").getAsBoolean());
                        cfg.setChoice(e, row.has("choice") ? row.get("choice").getAsString() : null);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[SoundEffects] could not read {}, using the defaults", CONFIG_PATH, e);
                cfg = new SoundEffectConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            for (SoundEffect e : SoundEffect.values()) {
                JsonObject row = new JsonObject();
                row.addProperty("on", on[e.ordinal()]);
                row.addProperty("choice", choice[e.ordinal()]);
                obj.add(e.key, row);
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[SoundEffects] could not save {}", CONFIG_PATH, e);
        }
    }

    private static String clean(String id) {
        return id == null || id.isBlank() ? ClickSounds.DEFAULT_ID : id.trim();
    }

    /** True while at least one effect is on: the game's sounds are checked only then. */
    public boolean anyOn() {
        return anyOn;
    }

    public boolean isOn(SoundEffect e) {
        return on[e.ordinal()];
    }

    public void setOn(SoundEffect e, boolean value) {
        on[e.ordinal()] = value;
        boolean any = false;
        for (boolean b : on) {
            any |= b;
        }
        anyOn = any;
    }

    /** {@link ClickSounds#DEFAULT_ID}, a preset id or {@code "import:<file>"}, as for the click. */
    public String getChoice(SoundEffect e) {
        return choice[e.ordinal()];
    }

    public void setChoice(SoundEffect e, String id) {
        choice[e.ordinal()] = clean(id);
    }
}
