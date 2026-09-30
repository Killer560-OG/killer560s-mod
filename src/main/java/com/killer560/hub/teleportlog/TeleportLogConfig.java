package com.killer560.hub.teleportlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Teleport Logger settings - {@code killer560smod-teleportlog.json}.
 *
 * <p>killer560 (2026-09-30): "can you make a teleport logger so i can go on the main server and we get the
 * regular teleports right?" So this ships ON: the whole point is that he goes and plays normally and the data
 * is waiting afterwards, and a logger he has to remember to switch on is a logger that records the run he
 * forgot. It writes nothing until he actually teleports.
 *
 * <p>Deliberately NOT gated on Skyblock. The sim's teleport is the thing being checked and the sim is not
 * Skyblock either, so gating it would leave out the side of the comparison that matters.
 */
public final class TeleportLogConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-teleportlog.json");

    /** Hard ceiling on the log file, so a forgotten logger cannot grow without bound. */
    public static final int MAX_LINES = 4000;

    private static TeleportLogConfig instance;

    private boolean enabled = true;
    /** One line in chat per teleport, so he can see it is recording without opening the file. */
    private boolean chatEcho = true;

    private TeleportLogConfig() {
    }

    public static TeleportLogConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        TeleportLogConfig cfg = new TeleportLogConfig();
        JsonObject obj = null;
        if (Files.exists(CONFIG_PATH)) {
            try {
                obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (Exception e) {
                obj = null;
            }
        }
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
            cfg.chatEcho = ConfigJson.getBool(obj, "chatEcho", true);
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("chatEcho", chatEcho);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isChatEcho() {
        return chatEcho;
    }

    public void setChatEcho(boolean v) {
        chatEcho = v;
    }
}
