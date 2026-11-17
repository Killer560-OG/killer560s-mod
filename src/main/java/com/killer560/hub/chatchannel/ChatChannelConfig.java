package com.killer560.hub.chatchannel;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/** Chat Channel Indicator settings ({@link ChatChannelFeature}): the switch (off), Show Member Count (on), Use Hypixel Colors (on). */
public final class ChatChannelConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-chatchannel.json");

    private static final BoolFields<ChatChannelConfig> BOOLS = new BoolFields<ChatChannelConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("showMemberCount", true, c -> c.showMemberCount, (c, v) -> c.showMemberCount = v)
            .add("hypixelColors", true, c -> c.hypixelColors, (c, v) -> c.hypixelColors = v);

    private static ChatChannelConfig instance;

    private boolean enabled = false;
    private boolean showMemberCount = true;
    private boolean hypixelColors = true;

    private ChatChannelConfig() {
    }

    public static ChatChannelConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ChatChannelConfig cfg = new ChatChannelConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            BOOLS.load(cfg, obj);
        }
        instance = cfg;
        ChatChannelFeature.refresh();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        BOOLS.save(this, obj);
        ConfigJson.write(CONFIG_PATH, obj);
        ChatChannelFeature.refresh();
    }

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean isShowMemberCount() {
        return showMemberCount;
    }

    public void setShowMemberCount(boolean value) {
        showMemberCount = value;
    }

    public boolean isHypixelColors() {
        return hypixelColors;
    }

    public void setHypixelColors(boolean value) {
        hypixelColors = value;
    }
}
