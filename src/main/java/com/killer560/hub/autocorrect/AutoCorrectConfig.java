package com.killer560.hub.autocorrect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Chat Auto Correct settings: chat on/off, plus command-name correction on/off. */
public final class AutoCorrectConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-autocorrect.json");

    private static AutoCorrectConfig instance;

    private boolean enabled = false;
    /** Fix typos in typed command NAMES ("/wardorbe" -> "/wardrobe") - see
     *  {@link AutoCorrectFeature#correctOutgoingCommand}. Independent of {@link #enabled} (chat text),
     *  off by default (2026-09-15 roadmap). */
    private boolean correctCommands = false;

    private AutoCorrectConfig() {
    }

    public static AutoCorrectConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new AutoCorrectConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AutoCorrectConfig cfg = new AutoCorrectConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.correctCommands = ConfigJson.getBool(obj, "correctCommands", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new AutoCorrectConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("correctCommands", correctCommands);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isCorrectCommands() {
        return correctCommands && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setCorrectCommands(boolean correctCommands) {
        this.correctCommands = correctCommands;
    }
}
