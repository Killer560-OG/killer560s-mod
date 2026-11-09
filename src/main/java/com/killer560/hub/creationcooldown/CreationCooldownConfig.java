package com.killer560.hub.creationcooldown;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/**
 * Creation Cooldown settings ({@link CreationCooldownFeature}), the same options as SkyHanni's
 * {@code CreationCooldownConfig}: the HUD (off), Show On All Islands (off), Only In Green Room (on), Chat When Over
 * (off), Block Instance Creation (off).
 */
public final class CreationCooldownConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-creationcooldown.json");

    private static CreationCooldownConfig instance;

    private boolean enabled = false;
    private boolean showOnAllIslands = false;
    private boolean onlyInGreenRoom = true;
    private boolean chatWhenOver = false;
    private boolean blockCreation = false;

    private CreationCooldownConfig() {
    }

    public static CreationCooldownConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        CreationCooldownConfig cfg = new CreationCooldownConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showOnAllIslands = ConfigJson.getBool(obj, "showOnAllIslands", false);
            cfg.onlyInGreenRoom = ConfigJson.getBool(obj, "onlyInGreenRoom", true);
            cfg.chatWhenOver = ConfigJson.getBool(obj, "chatWhenOver", false);
            cfg.blockCreation = ConfigJson.getBool(obj, "blockCreation", false);
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("showOnAllIslands", showOnAllIslands);
        obj.addProperty("onlyInGreenRoom", onlyInGreenRoom);
        obj.addProperty("chatWhenOver", chatWhenOver);
        obj.addProperty("blockCreation", blockCreation);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** The HUD switch, behind the Skyblock gate. */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean isShowOnAllIslands() {
        return showOnAllIslands;
    }

    public void setShowOnAllIslands(boolean value) {
        showOnAllIslands = value;
    }

    public boolean isOnlyInGreenRoom() {
        return onlyInGreenRoom;
    }

    public void setOnlyInGreenRoom(boolean value) {
        onlyInGreenRoom = value;
    }

    public boolean isChatWhenOver() {
        return chatWhenOver;
    }

    public void setChatWhenOver(boolean value) {
        chatWhenOver = value;
    }

    public boolean isBlockCreation() {
        return blockCreation;
    }

    public void setBlockCreation(boolean value) {
        blockCreation = value;
    }
}
