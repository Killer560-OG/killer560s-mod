package com.killer560.hub.ghostblock;

import com.google.gson.JsonObject;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Ghost Block settings (cheat build only, off by default, key unbound). See {@link GhostBlockFeature}. */
public final class GhostBlockConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-ghostblock.json");

    private static GhostBlockConfig instance;

    private boolean enabled = false;
    /** A GLFW key or a {@link KeyUtil} mouse code; {@link KeyUtil#NONE} when unbound. */
    private int keyCode = KeyUtil.NONE;

    private GhostBlockConfig() {
    }

    public static GhostBlockConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        GhostBlockConfig cfg = new GhostBlockConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.keyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "keyCode", cfg.keyCode));
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("keyCode", keyCode);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return BuildVariant.CHEAT_FEATURES_ENABLED && enabled; }

    public void setEnabled(boolean v) { enabled = v; }

    public int getKeyCode() { return keyCode; }

    public void setKeyCode(int v) { keyCode = KeyUtil.sanitizeBind(v); }
}
