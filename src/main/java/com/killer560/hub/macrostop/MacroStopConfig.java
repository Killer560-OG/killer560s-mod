package com.killer560.hub.macrostop;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/**
 * The mod-wide Emergency Cancel Key (Home tab, killer560 2026-11-01: a macro stops "if I press escape or the emergency
 * cancel key"). One key for every macro, stored in the same code space as every other bind ({@link KeyUtil}: a GLFW key,
 * or a mouse button as {@link KeyUtil#codeForMouseButton}). Unset ({@link KeyUtil#NONE}) by default.
 */
public final class MacroStopConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-macrostop.json");

    private static volatile MacroStopConfig instance;

    private volatile int emergencyCancelKeyCode = KeyUtil.NONE;

    private MacroStopConfig() {
    }

    public static MacroStopConfig getInstance() {
        MacroStopConfig cfg = instance;
        if (cfg == null) {
            synchronized (MacroStopConfig.class) {
                if (instance == null) {
                    load();
                }
                cfg = instance;
            }
        }
        return cfg;
    }

    public static void load() {
        MacroStopConfig cfg = new MacroStopConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.emergencyCancelKeyCode = KeyUtil.sanitizeBind(
                    ConfigJson.getInt(obj, "emergencyCancelKeyCode", KeyUtil.NONE));
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("emergencyCancelKeyCode", emergencyCancelKeyCode);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** The bind, or {@link KeyUtil#NONE} when unset. */
    public int getEmergencyCancelKeyCode() { return emergencyCancelKeyCode; }

    public void setEmergencyCancelKeyCode(int code) { emergencyCancelKeyCode = KeyUtil.sanitizeBind(code); }
}
