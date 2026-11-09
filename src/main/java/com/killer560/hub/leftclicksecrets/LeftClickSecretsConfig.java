package com.killer560.hub.leftclicksecrets;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Left Click Opens Secrets settings - see {@link LeftClickSecrets}. Off by default. */
public final class LeftClickSecretsConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-leftclicksecrets.json");

    private static LeftClickSecretsConfig instance;

    private boolean enabled = false;

    private LeftClickSecretsConfig() {
    }

    public static LeftClickSecretsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LeftClickSecretsConfig cfg = new LeftClickSecretsConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            Files.writeString(CONFIG_PATH, obj.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
