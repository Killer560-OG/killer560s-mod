package com.killer560.hub.modchat;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.relay.RelayRoom;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Mod Chat settings - see {@link ModChatFeature}. On by default (SharingDefaults v2), and received
 * messages print into chat by default (see {@link #RECEIVE_DEFAULT_KEY}).
 * <p>
 * The old {@code channel} key (Party/Guild) is gone: Mod Chat no longer sends over Hypixel chat at all, so there
 * is no channel to pick. An existing config file that still has that key simply loads without it - every other
 * setting still reads, because {@link ConfigJson} reads per key rather than all-or-nothing.
 * <p>
 * <b>Room, 2026-09-20.</b> killer560: <i>"Poor the global mod chat. Do not have a global option only have a
 * lobby option or a party option."</i> The old {@code partyRoom} boolean (Party vs. the removed Global) is
 * replaced by {@link RelayRoom.Mode}. {@link #load} migrates an old file's boolean once: {@code true} (already
 * the default) stays Party; {@code false} - the removed Global - becomes Lobby, its closest surviving
 * equivalent (everyone on your current instance, not literally everyone on the relay). {@link #save} only
 * ever writes the new {@code roomMode} key from here on.
 */
public final class ModChatConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-modchat.json");

    /** Written by every save since 2026-10-08; its absence marks a file from before Log To Chat defaulted ON. */
    static final String RECEIVE_DEFAULT_KEY = "receiveDefaultV2";

    private static ModChatConfig instance;

    private boolean enabled = true;
    /** Empty means "use whatever {@link RelayEndpoint#DEFAULT_BASE_URL} currently is", so a build that ships a
     *  newly deployed relay picks it up for everyone who never typed their own address. */
    // killer560, 2026-09-20: "they shouldnt have to set some sort of relay url for the mod chat it should
    // already be there and their shouldnt be a way to change it." The address is baked into RelayEndpoint
    // and there is no longer any UI for this. The field only survives so an older config that has one does
    // not fail to parse - it is read, never written, and never used to pick the endpoint.
    private String legacyRelayUrlIgnored = "";
    private RelayRoom.Mode roomMode = RelayRoom.Mode.PARTY;
    /** Received messages printed into the chat window. ON by default since 2026-10-08 (killer560: "everyone by
     *  default should receive the chat messages") - before that they were only a four-second overlay. */
    private boolean logToChat = true;

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
            cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
            cfg.legacyRelayUrlIgnored = ConfigJson.getString(obj, "relayUrl", "");
            // Pre-2026-09-20 configs only ever had "partyRoom"; see the class doc for the migration mapping.
            RelayRoom.Mode legacyMode =
                    ConfigJson.getBool(obj, "partyRoom", true) ? RelayRoom.Mode.PARTY : RelayRoom.Mode.LOBBY;
            cfg.roomMode = ConfigJson.getEnum(obj, "roomMode", RelayRoom.Mode.class, legacyMode);
            // A file written before RECEIVE_DEFAULT_KEY existed saved logToChat=false only because false was the
            // default then, so its value says nothing about what the player wanted: it is switched ON once, the key
            // is written, and from then on whatever the player sets is kept (same one-time rule as SharingDefaults).
            // The presenceAlerts key of older files is simply no longer read (presence notices were removed).
            boolean receiveDefaultApplied = ConfigJson.getBool(obj, RECEIVE_DEFAULT_KEY, false);
            cfg.logToChat = receiveDefaultApplied ? ConfigJson.getBool(obj, "logToChat", true) : true;
            instance = cfg;
            if (!receiveDefaultApplied) {
                cfg.save();
            }
        } catch (Exception e) {
            instance = new ModChatConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("roomMode", roomMode.name());
            obj.addProperty("logToChat", logToChat);
            obj.addProperty(RECEIVE_DEFAULT_KEY, true);
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

    /** The address used to connect. There is deliberately no way to change it (killer560, 2026-09-20). */
    public String getRelayUrl() {
        return RelayEndpoint.DEFAULT_BASE_URL;
    }

    /** Talk only to your party, or only to everyone on your current Hypixel instance - see
     *  {@link RelayRoom.Mode}. There is deliberately no wider "everyone on the relay" choice. */
    public RelayRoom.Mode getRoomMode() {
        return roomMode;
    }

    public void setRoomMode(RelayRoom.Mode roomMode) {
        this.roomMode = roomMode;
    }

    /** Print received messages into the real chat window (default ON), so they can be read and scrolled back to;
     *  OFF shows each one only as a short overlay. */
    public boolean isLogToChat() {
        return logToChat;
    }

    public void setLogToChat(boolean logToChat) {
        this.logToChat = logToChat;
    }

}
