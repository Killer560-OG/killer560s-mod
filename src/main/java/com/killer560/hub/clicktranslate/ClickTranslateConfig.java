package com.killer560.hub.clicktranslate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Click Translate settings: on/off, and which language to translate clicked messages
 *  into (defaults to English for anyone who's never changed it). */
public final class ClickTranslateConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-clicktranslate.json");

    private static ClickTranslateConfig instance;

    private boolean enabled = true;
    private String targetLanguageCode = "en";

    private ClickTranslateConfig() {
    }

    public static ClickTranslateConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ClickTranslateConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ClickTranslateConfig cfg = new ClickTranslateConfig();
            cfg.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
            cfg.targetLanguageCode = obj.has("targetLanguageCode") ? obj.get("targetLanguageCode").getAsString() : "en";
            instance = cfg;
        } catch (Exception e) {
            instance = new ClickTranslateConfig();
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
        this.targetLanguageCode = (targetLanguageCode == null || targetLanguageCode.isBlank()) ? "en" : targetLanguageCode;
    }
}
