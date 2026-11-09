package com.killer560.hub.kickrejoin;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/**
 * Kick Rejoin Timer settings ({@link KickRejoinFeature}): off by default like every new feature; the title and sound
 * when the wait is over are on; the party message is off. The wait itself is not a setting: it is always Hypixel's 60 s
 * (killer560, 2026-11-03: "have it be 60 seconds no matter what that's what's hypixels rejoin policy").
 */
public final class KickRejoinConfig {

    public static final String DEFAULT_PARTY_TEXT = "Kicked from SkyBlock, back in 1 minute!";
    public static final int MAX_PARTY_TEXT = 100;

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-kickrejoin.json");

    private static KickRejoinConfig instance;

    private boolean enabled = false;
    private boolean readyTitle = true;
    private boolean readySound = true;
    private boolean partyMessage = false;
    private String partyText = DEFAULT_PARTY_TEXT;

    private KickRejoinConfig() {
    }

    public static KickRejoinConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        KickRejoinConfig cfg = new KickRejoinConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.readyTitle = ConfigJson.getBool(obj, "readyTitle", true);
            cfg.readySound = ConfigJson.getBool(obj, "readySound", true);
            cfg.partyMessage = ConfigJson.getBool(obj, "partyMessage", false);
            cfg.setPartyText(ConfigJson.getString(obj, "partyText", DEFAULT_PARTY_TEXT));
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("readyTitle", readyTitle);
        obj.addProperty("readySound", readySound);
        obj.addProperty("partyMessage", partyMessage);
        obj.addProperty("partyText", partyText);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean isReadyTitle() {
        return readyTitle;
    }

    public void setReadyTitle(boolean value) {
        readyTitle = value;
    }

    public boolean isReadySound() {
        return readySound;
    }

    public void setReadySound(boolean value) {
        readySound = value;
    }

    public boolean isPartyMessage() {
        return partyMessage;
    }

    public void setPartyMessage(boolean value) {
        partyMessage = value;
    }

    public String getPartyText() {
        return partyText;
    }

    /** Kept chat-safe: no color codes (the sign and its letter), newlines or control characters, trimmed, at most {@link #MAX_PARTY_TEXT}
     *  characters; blank puts the default back. */
    public void setPartyText(String value) {
        String v = chatSafe(value);
        partyText = v.isEmpty() ? DEFAULT_PARTY_TEXT : v;
    }

    static String chatSafe(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') {
                i++; // a color code is the sign AND its letter: "§cText" is "Text", not "cText"
                continue;
            }
            if (c >= ' ' && c != 127) {
                b.append(c);
            }
        }
        String out = b.toString().trim();
        return out.length() > MAX_PARTY_TEXT ? out.substring(0, MAX_PARTY_TEXT).trim() : out;
    }
}
