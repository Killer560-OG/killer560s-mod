package com.killer560.hub.mining.events;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.dungeonalerts.SecretSound;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** Mining Events settings ({@code killer560smod-mining-events.json}). Ships OFF; every change saves at once. */
public final class MiningEventsConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-mining-events");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-mining-events.json");

    private static MiningEventsConfig instance;

    private boolean enabled = false;
    private boolean startTitle = true;
    private boolean startSound = true;
    private SecretSound.SoundChoice sound = SecretSound.SoundChoice.PLING;
    private boolean timerHud = true;
    private boolean endingSoon = false;
    /** Events whose alerts are switched off, by {@link MiningEvent#name()}. Every event alerts by default. */
    private final Set<String> muted = new LinkedHashSet<>();

    private MiningEventsConfig() {
    }

    public static MiningEventsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        MiningEventsConfig cfg = new MiningEventsConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.startTitle = ConfigJson.getBool(o, "startTitle", cfg.startTitle);
                cfg.startSound = ConfigJson.getBool(o, "startSound", cfg.startSound);
                SecretSound.SoundChoice s = SecretSound.SoundChoice.byName(ConfigJson.getString(o, "sound", cfg.sound.name()));
                cfg.sound = s == null ? cfg.sound : s;
                cfg.timerHud = ConfigJson.getBool(o, "timerHud", cfg.timerHud);
                cfg.endingSoon = ConfigJson.getBool(o, "endingSoon", cfg.endingSoon);
                JsonArray arr = ConfigJson.getArray(o, "muted");
                if (arr != null) {
                    for (JsonElement e : arr) {
                        cfg.muted.add(e.getAsString());
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[MiningEvents] Couldn't read {} - using defaults this session: {}",
                        CONFIG_PATH.getFileName(), e.toString());
                instance = new MiningEventsConfig();
                return;
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("startTitle", startTitle);
            o.addProperty("startSound", startSound);
            o.addProperty("sound", sound.name());
            o.addProperty("timerHud", timerHud);
            o.addProperty("endingSoon", endingSoon);
            JsonArray arr = new JsonArray();
            for (String m : muted) {
                arr.add(m);
            }
            o.add("muted", arr);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[MiningEvents] Couldn't save {}: {}", CONFIG_PATH.getFileName(), e.toString());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isStartTitle() {
        return startTitle;
    }

    public void setStartTitle(boolean v) {
        startTitle = v;
    }

    public boolean isStartSound() {
        return startSound;
    }

    public void setStartSound(boolean v) {
        startSound = v;
    }

    public SecretSound.SoundChoice getSound() {
        return sound;
    }

    public void setSound(SecretSound.SoundChoice v) {
        sound = v == null ? SecretSound.SoundChoice.PLING : v;
    }

    public boolean isTimerHud() {
        return timerHud;
    }

    public void setTimerHud(boolean v) {
        timerHud = v;
    }

    public boolean isEndingSoon() {
        return endingSoon;
    }

    public void setEndingSoon(boolean v) {
        endingSoon = v;
    }

    public boolean isAlerting(MiningEvent e) {
        return !muted.contains(e.name());
    }

    public void setAlerting(MiningEvent e, boolean on) {
        if (on) {
            muted.remove(e.name());
        } else {
            muted.add(e.name());
        }
    }
}
