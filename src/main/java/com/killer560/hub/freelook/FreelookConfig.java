package com.killer560.hub.freelook;

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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
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
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.mode = ConfigJson.getEnum(obj, "mode", Mode.class, cfg.mode);
                cfg.panKeyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "panKeyCode", cfg.panKeyCode));
            } catch (Exception e) {
                cfg = new FreelookConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("mode", mode.name());
            obj.addProperty("panKeyCode", panKeyCode);
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
