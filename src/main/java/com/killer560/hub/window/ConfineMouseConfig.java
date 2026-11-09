package com.killer560.hub.window;

import com.killer560.hub.util.ConfigJson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** "Confine Mouse In Menus" (killer560, 2026-10-24). OFF by default since 2026-11-04 (killer560: "by default confine mouse in menus should be off").
 *  Before that an unsaved value followed borderless fullscreen.
 *  The key is only written once it has been set. */
public final class ConfineMouseConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-window-confinemouse.json");

    private static ConfineMouseConfig instance;

    /** Null until he sets it; then the saved value. */
    private Boolean enabled;
    private boolean onlyInDungeons = false;

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
                if (obj.has("onlyInDungeons") && obj.get("onlyInDungeons").isJsonPrimitive()) {
                    cfg.onlyInDungeons = obj.get("onlyInDungeons").getAsBoolean();
                }
            }
        } catch (Exception ignored) {
            cfg.enabled = null;
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        if (enabled != null) {
            obj.addProperty("enabled", enabled);
        }
        obj.addProperty("onlyInDungeons", onlyInDungeons);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** The saved value, or OFF with none saved. */
    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    public void setEnabled(boolean v) { enabled = v; }

    /** Only In Dungeons (killer560, 2026-11-01): confine only while in a Catacombs run. */
    public boolean isOnlyInDungeons() { return onlyInDungeons; }

    public void setOnlyInDungeons(boolean v) { onlyInDungeons = v; }

    /** Whether he has set it himself. */
    public boolean isExplicitlySet() {
        return enabled != null;
    }
}
