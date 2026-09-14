package com.killer560.hub.p4platform;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted P4 Platform Highlight settings - see {@link P4PlatformHighlightFeature}'s class doc for the
 *  real QUOI-ported "highlight the 3x3 you need to mine after Goldor dies" this is built on. Ships
 *  disabled by default, same as every other new feature in this mod. */
public final class P4PlatformHighlightConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-p4platformhighlight.json");

    private static P4PlatformHighlightConfig instance;

    private boolean enabled = false;
    private boolean filled = true;

    private P4PlatformHighlightConfig() {
    }

    public static P4PlatformHighlightConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new P4PlatformHighlightConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            P4PlatformHighlightConfig cfg = new P4PlatformHighlightConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.filled = !obj.has("filled") || obj.get("filled").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new P4PlatformHighlightConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("filled", filled);
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

    public boolean isFilled() {
        return filled;
    }

    public void setFilled(boolean filled) {
        this.filled = filled;
    }
}
