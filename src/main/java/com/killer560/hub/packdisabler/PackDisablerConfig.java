package com.killer560.hub.packdisabler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Pack Disabler toggle. Default OFF: it changes how every SkyBlock item looks, so he turns it on. */
public final class PackDisablerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-packdisabler.json");

    private static volatile PackDisablerConfig instance;

    private volatile boolean enabled = false;

    private PackDisablerConfig() {
    }

    public static PackDisablerConfig getInstance() {
        PackDisablerConfig cfg = instance;
        if (cfg == null) {
            synchronized (PackDisablerConfig.class) {
                if (instance == null) {
                    load();
                }
                cfg = instance;
            }
        }
        return cfg;
    }

    public static synchronized void load() {
        PackDisablerConfig cfg = new PackDisablerConfig();
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            }
        } catch (Exception ignored) {
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The saved toggle. Whether it is actually in force is {@link PackDisabler#isActive()}. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
