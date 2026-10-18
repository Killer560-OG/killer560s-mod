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

    private boolean unlimited = false;

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
                cfg.unlimited = ConfigJson.getBool(obj, "unlimited", cfg.unlimited);
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
            obj.addProperty("unlimited", unlimited);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isUnlimited() {
        return unlimited;
    }

    public void setUnlimited(boolean unlimited) {
        this.unlimited = unlimited;
    }

    /** How many messages the chat window keeps: vanilla's cap, or no cap at all. Read on the chat's add path. */
    public static int limit() {
        return getInstance().unlimited ? Integer.MAX_VALUE : VANILLA_LIMIT;
    }
}
