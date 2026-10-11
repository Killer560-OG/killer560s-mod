package com.killer560.hub.chocolatefactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Chocolate Factory helper settings (the legit, SkyHanni-style one) - see {@link ChocolateFactoryHelper}.
 *  The master switch ships off, like every new feature; its parts default on so turning it on shows everything. */
public final class ChocolateFactoryConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-chocolatefactory.json");

    private static ChocolateFactoryConfig instance;

    private boolean enabled = false;
    private boolean highlightBestUpgrade = true;
    private boolean highlightStrays = true;
    private boolean strayAlert = true;
    private boolean timeTowerReminder = true;
    private boolean infoLine = true;

    private ChocolateFactoryConfig() {
    }

    public static ChocolateFactoryConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ChocolateFactoryConfig cfg = new ChocolateFactoryConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", false);
                cfg.highlightBestUpgrade = ConfigJson.getBool(o, "highlightBestUpgrade", true);
                cfg.highlightStrays = ConfigJson.getBool(o, "highlightStrays", true);
                cfg.strayAlert = ConfigJson.getBool(o, "strayAlert", true);
                cfg.timeTowerReminder = ConfigJson.getBool(o, "timeTowerReminder", true);
                cfg.infoLine = ConfigJson.getBool(o, "infoLine", true);
            } catch (Exception e) {
                cfg = new ChocolateFactoryConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("highlightBestUpgrade", highlightBestUpgrade);
            o.addProperty("highlightStrays", highlightStrays);
            o.addProperty("strayAlert", strayAlert);
            o.addProperty("timeTowerReminder", timeTowerReminder);
            o.addProperty("infoLine", infoLine);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Raw switch (the tab's toggle); {@link #active()} also applies the Skyblock Only gate. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean active() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isHighlightBestUpgrade() {
        return highlightBestUpgrade;
    }

    public void setHighlightBestUpgrade(boolean v) {
        highlightBestUpgrade = v;
    }

    public boolean isHighlightStrays() {
        return highlightStrays;
    }

    public void setHighlightStrays(boolean v) {
        highlightStrays = v;
    }

    public boolean isStrayAlert() {
        return strayAlert;
    }

    public void setStrayAlert(boolean v) {
        strayAlert = v;
    }

    public boolean isTimeTowerReminder() {
        return timeTowerReminder;
    }

    public void setTimeTowerReminder(boolean v) {
        timeTowerReminder = v;
    }

    public boolean isInfoLine() {
        return infoLine;
    }

    public void setInfoLine(boolean v) {
        infoLine = v;
    }
}
