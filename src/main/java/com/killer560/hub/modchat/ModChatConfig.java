package com.killer560.hub.modchat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Mod Chat settings - see {@link ModChatFeature}. Ships disabled by default. */
public final class ModChatConfig {

    public enum Channel {
        PARTY("pc"), GUILD("gc");

        public final String commandPrefix;

        Channel(String commandPrefix) {
            this.commandPrefix = commandPrefix;
        }

        public Channel next() {
            Channel[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-modchat.json");

    private static ModChatConfig instance;

    private boolean enabled = false;
    private Channel channel = Channel.PARTY;

    private ModChatConfig() {
    }

    public static ModChatConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ModChatConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ModChatConfig cfg = new ModChatConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            if (obj.has("channel")) {
                try {
                    cfg.channel = Channel.valueOf(obj.get("channel").getAsString());
                } catch (Exception ignored) {
                }
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new ModChatConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("channel", channel.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }
}
