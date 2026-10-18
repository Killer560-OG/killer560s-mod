package com.killer560.hub.clicksound;

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
 * The menu click sound (killer560, 2026-10-15): which sound a button click makes and how loud. It plays on every screen
 * (the "Vanilla Screens" switch was removed 2026-10-18: "the click sounds if on should work for all menus regardless"). {@code choice} is {@code "default"} (vanilla's click), a preset id ({@code "soft"}, {@code "tick"}...)
 * or {@code "import:<file name>"} for an .ogg in {@link ClickSounds#importDir()}. See {@link ClickSounds}.
 */
public final class ClickSoundConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-menu-clicksound.json");

    private static ClickSoundConfig instance;

    private String choice = ClickSounds.DEFAULT_ID;
    private float volume = 1.0f;
    /** The loudest a click can be set: five times (500%), made by playing it in layers (see {@link ClickSounds}). */
    public static final float MAX_VOLUME = 5.0f;

    private ClickSoundConfig() {
    }

    public static ClickSoundConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ClickSoundConfig cfg = new ClickSoundConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.setChoice(obj.has("choice") ? obj.get("choice").getAsString() : ClickSounds.DEFAULT_ID);
                cfg.setVolume(ConfigJson.getFloat(obj, "volume", 1.0f));
            } catch (Exception ignored) {
                cfg = new ClickSoundConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("choice", choice);
            obj.addProperty("volume", volume);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public String getChoice() {
        return choice;
    }

    public void setChoice(String id) {
        choice = id == null || id.isBlank() ? ClickSounds.DEFAULT_ID : id.trim();
    }

    /** 0..5, 1 being 100%: the click's loudness as a multiple of its own. */
    public float getVolume() {
        return volume;
    }

    public void setVolume(float v) {
        volume = Float.isNaN(v) ? 1.0f : Math.max(0f, Math.min(MAX_VOLUME, v));
    }
}
