package com.killer560.hub.window;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** "Confine Mouse In Menus" (killer560, 2026-10-24). Its default follows the window mode: with no saved value it
 *  reads ON while borderless fullscreen is on ({@link WindowModeConfig#isBorderlessFullscreenEnabled()}) and OFF
 *  otherwise. Once he flips it, the saved value wins in every mode. The key is only written once it has been set,
 *  so an unsaved file keeps following the mode. */
public final class ConfineMouseConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-window-confinemouse.json");

    private static ConfineMouseConfig instance;

    /** Null until he sets it; then the saved value. */
    private Boolean enabled;

    private ConfineMouseConfig() {
    }

    public static ConfineMouseConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ConfineMouseConfig cfg = new ConfineMouseConfig();
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (obj.has("enabled") && obj.get("enabled").isJsonPrimitive()) {
                    cfg.enabled = obj.get("enabled").getAsBoolean();
                }
            }
        } catch (Exception ignored) {
            cfg.enabled = null;
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            if (enabled != null) {
                obj.addProperty("enabled", enabled);
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The saved value, or, with none saved, whether borderless fullscreen is on. */
    public boolean isEnabled() {
        return enabled != null ? enabled : WindowModeConfig.getInstance().isBorderlessFullscreenEnabled();
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    /** Whether he has set it himself (so the default no longer follows the window mode). */
    public boolean isExplicitlySet() {
        return enabled != null;
    }
}
