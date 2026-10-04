package com.killer560.hub.voicetotext;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.spotify.ChatDestination;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Voice To Text settings - see {@link VoiceToTextFeature}. Ships disabled by default. */
public final class VoiceToTextConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-voicetotext.json");

    /** How the mic is listened to (killer560, 2026-09-27: "add an option for open mic or push to talk").
     *  PUSH_TO_TALK is the default - it's what this feature already did before this option existed, so
     *  nobody's setup changes silently just from updating. */
    public enum Mode {
        OPEN_MIC("Open Mic"), PUSH_TO_TALK("Push To Talk");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public Mode next() {
            Mode[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    private static VoiceToTextConfig instance;

    private boolean enabled = false;
    private Mode mode = Mode.PUSH_TO_TALK;
    private int pushToTalkKeyCode = -1;
    /** Where a transcription is sent. Was a plain {@code sendToPartyChat} boolean (Party or Guild) until
     *  2026-09-30, when killer560 asked for "push to talk have an option to go to allchat as well" - rather
     *  than bolt a third state onto a boolean, this reuses {@link ChatDestination}, the enum the Spotify
     *  lyrics feature already uses for exactly this job, so both features cycle the same destinations in
     *  the same order with the same display names. Its own {@code chatDestination} JSON key, with the old
     *  boolean migrated on read - see {@link #load()}. */
    private ChatDestination chatDestination = ChatDestination.PARTY;
    /** Java Sound mixer name of the microphone to record from; empty = the system default (2026-09-21). */
    private String microphone = "";

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
            cfg.mode = ConfigJson.getEnum(obj, "mode", Mode.class, Mode.PUSH_TO_TALK);
            cfg.pushToTalkKeyCode = com.killer560.hub.util.KeyUtil.sanitize(ConfigJson.getInt(obj, "pushToTalkKeyCode", -1));
            // 2026-09-30 migration, explicit so nobody's destination silently reverts: a file written
            // before All Chat existed has only the old boolean, which meant Party when true and Guild when
            // false. Read it whenever the new key is absent; once anything is saved the new key is there
            // and the old one is never consulted again.
            ChatDestination legacy = ConfigJson.getBool(obj, "sendToPartyChat", true)
                    ? ChatDestination.PARTY : ChatDestination.GUILD;
            cfg.chatDestination = ConfigJson.getEnum(obj, "chatDestination", ChatDestination.class, legacy);
            cfg.microphone = obj.has("microphone") && obj.get("microphone").isJsonPrimitive() ? obj.get("microphone").getAsString() : "";
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
            obj.addProperty("mode", mode.name());
            obj.addProperty("pushToTalkKeyCode", pushToTalkKeyCode);
            obj.addProperty("chatDestination", chatDestination.name());
            // Still written so downgrading to a jar from before 2026-09-30 keeps Party/Guild rather than
            // resetting to Party. All Chat and Co-op have no boolean equivalent, so they read back as Guild
            // on such a jar - the closest "not Party" it has.
            obj.addProperty("sendToPartyChat", chatDestination == ChatDestination.PARTY);
            obj.addProperty("microphone", microphone);
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

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.PUSH_TO_TALK : mode;
    }

    public int getPushToTalkKeyCode() {
        return pushToTalkKeyCode;
    }

    public void setPushToTalkKeyCode(int pushToTalkKeyCode) {
        this.pushToTalkKeyCode = pushToTalkKeyCode;
    }

    public String getMicrophone() {
        return microphone;
    }

    public void setMicrophone(String microphone) {
        this.microphone = microphone == null ? "" : microphone;
    }

    public ChatDestination getChatDestination() {
        return chatDestination;
    }

    public void setChatDestination(ChatDestination chatDestination) {
        this.chatDestination = chatDestination == null ? ChatDestination.PARTY : chatDestination;
    }
}
