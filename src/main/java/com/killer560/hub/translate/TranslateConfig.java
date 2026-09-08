package com.killer560.hub.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Chat Translate settings: on/off and the target language to translate outgoing chat into. */
public final class TranslateConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-translate.json");

    private static TranslateConfig instance;

    private boolean enabled = false;
    private String targetLanguageCode = "";

    private TranslateConfig() {
    }

    public static TranslateConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TranslateConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            TranslateConfig cfg = new TranslateConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.targetLanguageCode = obj.has("targetLanguageCode") ? obj.get("targetLanguageCode").getAsString() : "";
            instance = cfg;
        } catch (Exception e) {
            instance = new TranslateConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("targetLanguageCode", targetLanguageCode);
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

    public String getTargetLanguageCode() {
        return targetLanguageCode;
    }

    public void setTargetLanguageCode(String targetLanguageCode) {
        this.targetLanguageCode = (targetLanguageCode == null) ? "" : targetLanguageCode;
    }
}
