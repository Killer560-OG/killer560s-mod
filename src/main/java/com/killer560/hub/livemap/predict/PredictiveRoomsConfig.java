package com.killer560.hub.livemap.predict;

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
 * Predictive Rooms settings, both builds (killer560, 2026-10-15: "FOr the predictive room stuff that should be on legit
 * and cheat"). Two switches: Predictive Rooms (name a room from what he has seen of it, {@link RoomPredictor}) and
 * Special Room Guess (an unopened room the map shows is one tile across is split-coloured with the special rooms it can
 * still be, {@code livemap.SpecialRoomGuess}). Both ship ON: they only ever show what he saw or what the map already
 * shows.
 */
public final class PredictiveRoomsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-livemap-predict.json");

    private static PredictiveRoomsConfig instance;

    private boolean enabled = true;
    private boolean specialGuess = true;

    private PredictiveRoomsConfig() {
    }

    public static PredictiveRoomsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        PredictiveRoomsConfig cfg = new PredictiveRoomsConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.specialGuess = ConfigJson.getBool(obj, "specialGuess", cfg.specialGuess);
            } catch (Exception e) {
                cfg = new PredictiveRoomsConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("specialGuess", specialGuess);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSpecialGuess() {
        return specialGuess && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isSpecialGuessRaw() {
        return specialGuess;
    }

    public void setSpecialGuess(boolean specialGuess) {
        this.specialGuess = specialGuess;
    }

    /** Whether either half wants the held map read. */
    public boolean isAnyOn() {
        return isEnabled() || isSpecialGuess();
    }
}
