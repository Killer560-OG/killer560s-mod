package com.killer560.hub.chattabs;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Chat Tabs settings ({@link ChatTabsFeature}): the switch (off), Switch Channel (on), Filter Closed Chat (off). */
public final class ChatTabsConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-chattabs.json");

    private static ChatTabsConfig instance;

    private boolean enabled = false;
    private boolean switchChannel = true;
    private boolean filterClosedChat = false;

    private ChatTabsConfig() {
    }

    public static ChatTabsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ChatTabsConfig cfg = new ChatTabsConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.switchChannel = ConfigJson.getBool(obj, "switchChannel", true);
            cfg.filterClosedChat = ConfigJson.getBool(obj, "filterClosedChat", false);
        }
        instance = cfg;
        ChatTabsFeature.refresh();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("switchChannel", switchChannel);
        obj.addProperty("filterClosedChat", filterClosedChat);
        ConfigJson.write(CONFIG_PATH, obj);
        ChatTabsFeature.refresh();
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean isSwitchChannel() {
        return switchChannel;
    }

    public void setSwitchChannel(boolean value) {
        switchChannel = value;
    }

    public boolean isFilterClosedChat() {
        return filterClosedChat;
    }

    public void setFilterClosedChat(boolean value) {
        filterClosedChat = value;
    }
}
