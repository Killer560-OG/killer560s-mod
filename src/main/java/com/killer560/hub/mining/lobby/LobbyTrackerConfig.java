package com.killer560.hub.mining.lobby;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Lobby Tracker settings ({@code killer560smod-mining-lobby.json}). Ships OFF. Its history is a separate file. */
public final class LobbyTrackerConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-mining-lobby");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-mining-lobby.json");

    private static LobbyTrackerConfig instance;

    private boolean enabled = false;
    private boolean chatOnJoin = true;
    private boolean hud = true;
    private boolean showWorldDay = true;

    private LobbyTrackerConfig() {
    }

    public static LobbyTrackerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LobbyTrackerConfig cfg = new LobbyTrackerConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.chatOnJoin = ConfigJson.getBool(o, "chatOnJoin", cfg.chatOnJoin);
                cfg.hud = ConfigJson.getBool(o, "hud", cfg.hud);
                cfg.showWorldDay = ConfigJson.getBool(o, "showWorldDay", cfg.showWorldDay);
            } catch (Exception e) {
                LOGGER.warn("[LobbyTracker] Couldn't read {} - using defaults this session: {}",
                        CONFIG_PATH.getFileName(), e.toString());
                instance = new LobbyTrackerConfig();
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
            o.addProperty("chatOnJoin", chatOnJoin);
            o.addProperty("hud", hud);
            o.addProperty("showWorldDay", showWorldDay);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[LobbyTracker] Couldn't save {}: {}", CONFIG_PATH.getFileName(), e.toString());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isChatOnJoin() {
        return chatOnJoin;
    }

    public void setChatOnJoin(boolean v) {
        chatOnJoin = v;
    }

    public boolean isHud() {
        return hud;
    }

    public void setHud(boolean v) {
        hud = v;
    }

    public boolean isShowWorldDay() {
        return showWorldDay;
    }

    public void setShowWorldDay(boolean v) {
        showWorldDay = v;
    }
}
