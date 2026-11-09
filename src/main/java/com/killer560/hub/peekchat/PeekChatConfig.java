package com.killer560.hub.peekchat;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Persisted Peek Chat settings - see {@link PeekChatFeature}. Off and unbound by default, like every new feature. */
public final class PeekChatConfig {

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
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.keyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "keyCode", cfg.keyCode));
            cfg.holdToPeek = ConfigJson.getBool(obj, "holdToPeek", cfg.holdToPeek);
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("keyCode", keyCode);
        obj.addProperty("holdToPeek", holdToPeek);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isHoldToPeek() { return holdToPeek; }

    public void setHoldToPeek(boolean holdToPeek) { this.holdToPeek = holdToPeek; }

    public int getKeyCode() { return keyCode; }

    public void setKeyCode(int keyCode) { this.keyCode = KeyUtil.sanitizeBind(keyCode); }
}
