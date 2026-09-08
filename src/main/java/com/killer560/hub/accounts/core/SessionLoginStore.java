package com.killer560.hub.accounts.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists the raw token typed into {@link com.killer560.hub.accounts.gui.DirectSessionLoginScreen}
 * so it survives closing and reopening the game, per killer560's request - but deliberately kept in
 * its own file, separate from Prism's real accounts.json and never shown in the main account list,
 * since it's meant to stay a quick/temporary login rather than look like a permanent saved account.
 */
public final class SessionLoginStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORE_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-session-login.json");

    private static SessionLoginStore instance;

    private String token = "";
    /** The IGN last resolved for {@link #token}, purely for display - cleared whenever the token
     *  itself changes, since a new/different token means this name is no longer known to be right. */
    private String lastKnownName = "";

    private SessionLoginStore() {
    }

    public static SessionLoginStore getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(STORE_PATH)) {
            instance = new SessionLoginStore();
            return;
        }
        try {
            String json = Files.readString(STORE_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            SessionLoginStore store = new SessionLoginStore();
            store.token = obj.has("token") ? obj.get("token").getAsString() : "";
            store.lastKnownName = obj.has("lastKnownName") ? obj.get("lastKnownName").getAsString() : "";
            instance = store;
        } catch (Exception e) {
            instance = new SessionLoginStore();
        }
    }

    public void save() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("token", token);
            obj.addProperty("lastKnownName", lastKnownName);
            Files.writeString(STORE_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = (token == null) ? "" : token;
    }

    public String getLastKnownName() {
        return lastKnownName;
    }

    public void setLastKnownName(String lastKnownName) {
        this.lastKnownName = (lastKnownName == null) ? "" : lastKnownName;
    }
}
