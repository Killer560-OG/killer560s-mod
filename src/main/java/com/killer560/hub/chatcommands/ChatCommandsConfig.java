package com.killer560.hub.chatcommands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Chat Commands settings - see {@link ChatCommandsFeature}'s class doc for the real
 *  Odin-ported "!command" reply system this is built on (scoped down to informational replies only).
 *  Ships disabled by default, same as every other new feature in this mod. */
public final class ChatCommandsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-chatcommands.json");

    private static ChatCommandsConfig instance;

    private boolean enabled = false;
    private boolean partyEnabled = true;
    private boolean guildEnabled = false;
    private boolean privateEnabled = true;
    private boolean coopEnabled = true;

    private ChatCommandsConfig() {
    }

    public static ChatCommandsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ChatCommandsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ChatCommandsConfig cfg = new ChatCommandsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.partyEnabled = ConfigJson.getBool(obj, "partyEnabled", true);
            cfg.guildEnabled = ConfigJson.getBool(obj, "guildEnabled", false);
            cfg.privateEnabled = ConfigJson.getBool(obj, "privateEnabled", true);
            cfg.coopEnabled = ConfigJson.getBool(obj, "coopEnabled", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new ChatCommandsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("partyEnabled", partyEnabled);
            obj.addProperty("guildEnabled", guildEnabled);
            obj.addProperty("privateEnabled", privateEnabled);
            obj.addProperty("coopEnabled", coopEnabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPartyEnabled() {
        return partyEnabled;
    }

    public void setPartyEnabled(boolean partyEnabled) {
        this.partyEnabled = partyEnabled;
    }

    public boolean isGuildEnabled() {
        return guildEnabled;
    }

    public void setGuildEnabled(boolean guildEnabled) {
        this.guildEnabled = guildEnabled;
    }

    public boolean isPrivateEnabled() {
        return privateEnabled;
    }

    public void setPrivateEnabled(boolean privateEnabled) {
        this.privateEnabled = privateEnabled;
    }

    public boolean isCoopEnabled() {
        return coopEnabled;
    }

    public void setCoopEnabled(boolean coopEnabled) {
        this.coopEnabled = coopEnabled;
    }
}
