package com.killer560.hub.freelook;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Persisted Freelook settings - see {@link FreelookFeature}. Off and the pan key unbound by default, like every new feature. */
public final class FreelookConfig {

    /** How F5 drives the rear view. */
    public enum Mode {
        /** The rear view lasts while F5 is held. */
        HOLD("Hold"),
        /** Each F5 press switches the rear view on or off. */
        TOGGLE("Toggle");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-freelook.json");

    private static FreelookConfig instance;

    private boolean enabled = false;
    private Mode mode = Mode.TOGGLE;
    /** A GLFW key or a {@link KeyUtil} mouse code; {@link KeyUtil#NONE} when unbound. */
    private int panKeyCode = KeyUtil.NONE;

    private FreelookConfig() {
    }

    public static FreelookConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        FreelookConfig cfg = new FreelookConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.mode = ConfigJson.getEnum(obj, "mode", Mode.class, cfg.mode);
            cfg.panKeyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "panKeyCode", cfg.panKeyCode));
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("mode", mode.name());
        obj.addProperty("panKeyCode", panKeyCode);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.TOGGLE : mode;
    }

    public int getPanKeyCode() {
        return panKeyCode;
    }

    public void setPanKeyCode(int panKeyCode) {
        this.panKeyCode = KeyUtil.sanitizeBind(panKeyCode);
    }
}
