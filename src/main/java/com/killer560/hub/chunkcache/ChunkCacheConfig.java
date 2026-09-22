package com.killer560.hub.chunkcache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Chunk Cache settings - see {@link ChunkCacheManager}. Ships disabled by default, same as every other
 *  new feature in this mod. */
public final class ChunkCacheConfig {

    public static final int MIN_CHUNKS = 500;
    public static final int MAX_CHUNKS = 20000;
    public static final int DEFAULT_CHUNKS = 4000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-chunkcache.json");

    private static ChunkCacheConfig instance;

    /** Default ON (killer560, 2026-09-21: "Default Chunk Cache to on"). */
    private boolean enabled = true;
    private int maxChunks = DEFAULT_CHUNKS;

    private ChunkCacheConfig() {
    }

    public static ChunkCacheConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ChunkCacheConfig cfg = new ChunkCacheConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                // One-time switch-on: files written before the default changed saved the old OFF default, so they
                // come up ON once (marked by defaultOnV1); after that his own choice is kept.
                cfg.enabled = obj.has("defaultOnV1") ? ConfigJson.getBool(obj, "enabled", true) : true;
                cfg.setMaxChunks(ConfigJson.getInt(obj, "maxChunks", DEFAULT_CHUNKS));
            } catch (Exception ignored) {
                // per-key readers above never throw; only an unreadable/non-object file lands here
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("maxChunks", maxChunks);
            obj.addProperty("defaultOnV1", true);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated by "Skyblock Only" like every other feature getter - the settings screen still sees the raw value. */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxChunks() {
        return maxChunks;
    }

    public void setMaxChunks(int maxChunks) {
        this.maxChunks = Math.max(MIN_CHUNKS, Math.min(MAX_CHUNKS, maxChunks));
    }
}
