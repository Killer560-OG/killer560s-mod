package com.killer560.hub.peekchat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Peek Chat settings - see {@link PeekChatFeature}. Off and unbound by default, like every new feature. */
public final class PeekChatConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-peekchat.json");

    private static PeekChatConfig instance;

    private boolean enabled = false;
    /** A GLFW key or a {@link KeyUtil} mouse code; {@link KeyUtil#NONE} when unbound. */
    private int keyCode = KeyUtil.NONE;
    /** Hold to Peek (killer560, 2026-10-18): the read-only chat is open while the key is held and closes on release.
     *  Off = Toggle Peek, the original press-to-open, press-again-to-close. */
    private boolean holdToPeek = false;

    private PeekChatConfig() {
    }

    public static PeekChatConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        PeekChatConfig cfg = new PeekChatConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.keyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "keyCode", cfg.keyCode));
                cfg.holdToPeek = ConfigJson.getBool(obj, "holdToPeek", cfg.holdToPeek);
            } catch (Exception e) {
                cfg = new PeekChatConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("keyCode", keyCode);
            obj.addProperty("holdToPeek", holdToPeek);
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

    public boolean isHoldToPeek() {
        return holdToPeek;
    }

    public void setHoldToPeek(boolean holdToPeek) {
        this.holdToPeek = holdToPeek;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = KeyUtil.sanitizeBind(keyCode);
    }
}
