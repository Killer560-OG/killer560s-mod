package com.killer560.hub.modlist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mods Screen (killer560, 2026-11-04: "add another button for mods that opens a browser to see all of their mods.
 * essentially mod menu built into our mod" and "it should also be in your normal esc button press ingame"). Two
 * independent switches, both ON by default: the Mods button on the Themed Main Menu and the one on the pause menu; and
 * whether library mods are listed in {@link ModsScreen}.
 */
public final class ModListConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-modlist.json");

    private static volatile ModListConfig instance;

    private boolean mainMenuButton = true;
    private boolean pauseButton = true;
    private boolean showLibraries = true;

    private ModListConfig() {
    }

    public static ModListConfig getInstance() {
        ModListConfig cfg = instance;
        if (cfg == null) {
            load();
            cfg = instance;
        }
        return cfg;
    }

    public static void load() {
        ModListConfig cfg = new ModListConfig();
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.mainMenuButton = ConfigJson.getBool(obj, "mainMenuButton", cfg.mainMenuButton);
                cfg.pauseButton = ConfigJson.getBool(obj, "pauseButton", cfg.pauseButton);
                cfg.showLibraries = ConfigJson.getBool(obj, "showLibraries", cfg.showLibraries);
            }
        } catch (Exception ignored) {
            // Unparseable file: defaults for this session.
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("mainMenuButton", mainMenuButton);
        obj.addProperty("pauseButton", pauseButton);
        obj.addProperty("showLibraries", showLibraries);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isMainMenuButton() {
        return mainMenuButton;
    }

    public void setMainMenuButton(boolean on) {
        this.mainMenuButton = on;
    }

    public boolean isPauseButton() {
        return pauseButton;
    }

    public void setPauseButton(boolean on) {
        this.pauseButton = on;
    }

    public boolean isShowLibraries() {
        return showLibraries;
    }

    public void setShowLibraries(boolean on) {
        this.showLibraries = on;
    }
}
