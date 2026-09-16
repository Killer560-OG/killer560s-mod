package com.killer560.hub.voicetotext;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Voice To Text settings - see {@link VoiceToTextFeature}. Ships disabled by default. */
public final class VoiceToTextConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-voicetotext.json");

    private static VoiceToTextConfig instance;

    private boolean enabled = false;
    private int pushToTalkKeyCode = -1;
    private boolean sendToPartyChat = true;

    private VoiceToTextConfig() {
    }

    public static VoiceToTextConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new VoiceToTextConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            VoiceToTextConfig cfg = new VoiceToTextConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.pushToTalkKeyCode = com.killer560.hub.util.KeyUtil.sanitize(ConfigJson.getInt(obj, "pushToTalkKeyCode", -1));
            cfg.sendToPartyChat = ConfigJson.getBool(obj, "sendToPartyChat", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new VoiceToTextConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("pushToTalkKeyCode", pushToTalkKeyCode);
            obj.addProperty("sendToPartyChat", sendToPartyChat);
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

    public int getPushToTalkKeyCode() {
        return pushToTalkKeyCode;
    }

    public void setPushToTalkKeyCode(int pushToTalkKeyCode) {
        this.pushToTalkKeyCode = pushToTalkKeyCode;
    }

    public boolean isSendToPartyChat() {
        return sendToPartyChat;
    }

    public void setSendToPartyChat(boolean sendToPartyChat) {
        this.sendToPartyChat = sendToPartyChat;
    }
}
