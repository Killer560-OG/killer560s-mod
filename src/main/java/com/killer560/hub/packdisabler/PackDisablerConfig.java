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
    /** Draw Hypixel's map menu buttons (Create Sell Offer, Create Buy Order...) as paper on 26.3 (2026-11-03). */
    private volatile boolean paperMenuButtons = true;

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
                cfg.paperMenuButtons = ConfigJson.getBool(obj, "paperMenuButtons", cfg.paperMenuButtons);
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
            obj.addProperty("paperMenuButtons", paperMenuButtons);
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

    /** Map menu buttons drawn as paper while Pack Disabler is active (26.3 only; 26.2's map is still the old sheet). */
    public boolean isPaperMenuButtons() {
        return paperMenuButtons;
    }

    public void setPaperMenuButtons(boolean paperMenuButtons) {
        this.paperMenuButtons = paperMenuButtons;
    }
}
