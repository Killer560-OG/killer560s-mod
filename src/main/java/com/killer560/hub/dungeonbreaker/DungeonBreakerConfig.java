package com.killer560.hub.dungeonbreaker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "0 Ping Dungeon Breaker" settings - see {@link DungeonBreakerFeature}'s class doc for the
 *  real mechanic this is built on. Ships disabled by default, same as every other new feature in this
 *  mod. */
public final class DungeonBreakerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-dungeonbreaker.json");

    private static DungeonBreakerConfig instance;

    private boolean enabled = false;
    private boolean fatigueOnly = false;

    private DungeonBreakerConfig() {
    }

    public static DungeonBreakerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new DungeonBreakerConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            DungeonBreakerConfig cfg = new DungeonBreakerConfig();
            cfg.enabled = com.killer560.hub.util.ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.fatigueOnly = com.killer560.hub.util.ConfigJson.getBool(obj, "fatigueOnly", cfg.fatigueOnly);
            instance = cfg;
        } catch (Exception e) {
            instance = new DungeonBreakerConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("fatigueOnly", fatigueOnly);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - a real mechanical edge
     *  (insta-mining, not just a safety net), same category as Simon Says' Trigger Bot/Auto Solve. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFatigueOnly() {
        return fatigueOnly;
    }

    public void setFatigueOnly(boolean fatigueOnly) {
        this.fatigueOnly = fatigueOnly;
    }
}
