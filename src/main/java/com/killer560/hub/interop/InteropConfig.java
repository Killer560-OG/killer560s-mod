package com.killer560.hub.interop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Party Interop settings - see {@link InteropFeature}. Ships disabled, like every new feature.
 * <p>
 * Each feed is switchable on its own because they are not equally safe: chat parsing reads other mods'
 * announcements (their wording, their next update may change it), the local bridge only does anything on a
 * machine that already has those mods installed, and the relay only carries our own users' data.
 */
public final class InteropConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-interop.json");

    private static InteropConfig instance;

    private boolean enabled = false;
    private boolean selfDerivation = true;
    private boolean chatParsing = true;
    private boolean localBridge = false;
    private boolean relayData = true;
    private boolean logPickups = false;

    private InteropConfig() {
    }

    public static InteropConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new InteropConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            InteropConfig cfg = new InteropConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.selfDerivation = ConfigJson.getBool(obj, "selfDerivation", true);
            cfg.chatParsing = ConfigJson.getBool(obj, "chatParsing", true);
            cfg.localBridge = ConfigJson.getBool(obj, "localBridge", false);
            cfg.relayData = ConfigJson.getBool(obj, "relayData", true);
            cfg.logPickups = ConfigJson.getBool(obj, "logPickups", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new InteropConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("selfDerivation", selfDerivation);
            obj.addProperty("chatParsing", chatParsing);
            obj.addProperty("localBridge", localBridge);
            obj.addProperty("relayData", relayData);
            obj.addProperty("logPickups", logPickups);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated by "Skyblock Only" like every other feature - nothing here runs outside Skyblock/p3sim. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    /** The raw saved value, for the settings screen (which must show what is actually saved). */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Work facts out from what this client can see by itself. The only feed that helps a player whose party
     *  mates run nothing at all, so it is on by default and should stay on. */
    public boolean isSelfDerivation() {
        return selfDerivation;
    }

    public void setSelfDerivation(boolean v) {
        this.selfDerivation = v;
    }

    /** Read other dungeon mods' PARTY CHAT announcements when a party mate is running one. */
    public boolean isChatParsing() {
        return chatParsing;
    }

    public void setChatParsing(boolean v) {
        this.chatParsing = v;
    }

    /** Read other mods' own state in this Minecraft - does nothing unless they are installed HERE. */
    public boolean isLocalBridge() {
        return localBridge;
    }

    public void setLocalBridge(boolean v) {
        this.localBridge = v;
    }

    /** Accept dungeon facts pushed in by our own relay from other killer560s-mod users. */
    public boolean isRelayData() {
        return relayData;
    }

    public void setRelayData(boolean v) {
        this.relayData = v;
    }

    /** Print each accepted fact into chat - for working out why something did or did not register. */
    public boolean isLogPickups() {
        return logPickups;
    }

    public void setLogPickups(boolean v) {
        this.logPickups = v;
    }
}
