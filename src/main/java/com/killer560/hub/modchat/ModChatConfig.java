package com.killer560.hub.modchat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Mod Chat settings - see {@link ModChatFeature}. Ships disabled by default.
 * <p>
 * The old {@code channel} key (Party/Guild) is gone: Mod Chat no longer sends over Hypixel chat at all, so there
 * is no channel to pick. An existing config file that still has that key simply loads without it - every other
 * setting still reads, because {@link ConfigJson} reads per key rather than all-or-nothing.
 */
public final class ModChatConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-modchat.json");

    private static ModChatConfig instance;

    private boolean enabled = false;
    /** Empty means "use whatever {@link RelayEndpoint#DEFAULT_BASE_URL} currently is", so a build that ships a
     *  newly deployed relay picks it up for everyone who never typed their own address. */
    private String relayUrl = "";
    private boolean partyRoom = true;
    private boolean logToChat = false;
    private boolean presenceAlerts = false;

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
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.relayUrl = ConfigJson.getString(obj, "relayUrl", "");
            cfg.partyRoom = ConfigJson.getBool(obj, "partyRoom", true);
            cfg.logToChat = ConfigJson.getBool(obj, "logToChat", false);
            cfg.presenceAlerts = ConfigJson.getBool(obj, "presenceAlerts", false);
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
            obj.addProperty("relayUrl", relayUrl);
            obj.addProperty("partyRoom", partyRoom);
            obj.addProperty("logToChat", logToChat);
            obj.addProperty("presenceAlerts", presenceAlerts);
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

    /** The address actually used to connect: the player's override, else the shipped default. */
    public String getRelayUrl() {
        return relayUrl == null || relayUrl.isBlank() ? RelayEndpoint.DEFAULT_BASE_URL : relayUrl.trim();
    }

    /** Exactly what the player typed (blank = following the shipped default) - for the settings text box. */
    public String getRelayUrlOverride() {
        return relayUrl == null ? "" : relayUrl;
    }

    public void setRelayUrlOverride(String value) {
        this.relayUrl = value == null ? "" : value.trim();
    }

    /** Talk only to your own party (a hashed room name) rather than to everyone on the relay. */
    public boolean isPartyRoom() {
        return partyRoom;
    }

    public void setPartyRoom(boolean partyRoom) {
        this.partyRoom = partyRoom;
    }

    /** Also print received messages into the real chat log, so they can be scrolled back to. */
    public boolean isLogToChat() {
        return logToChat;
    }

    public void setLogToChat(boolean logToChat) {
        this.logToChat = logToChat;
    }

    /** Announce when another mod user joins or leaves your relay room. */
    public boolean isPresenceAlerts() {
        return presenceAlerts;
    }

    public void setPresenceAlerts(boolean presenceAlerts) {
        this.presenceAlerts = presenceAlerts;
    }
}
