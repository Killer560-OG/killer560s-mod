package com.killer560.hub.dungeonbreaker;

import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.ConfigJson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Dungeon Breaker settings (Don't break secrets). Old keys (enabled, fatigueOnly, dungeonsOnly) are ignored. */
public final class DungeonBreakerConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-dungeonbreaker.json");

    // volatile: BreakerGuard.protects is also asked from AP3's planner thread, which must not see a half-set config.
    private static volatile DungeonBreakerConfig instance;

    private boolean noBreakSecrets = false;

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
            cfg.noBreakSecrets = com.killer560.hub.util.ConfigJson.getBool(obj, "noBreakSecrets", cfg.noBreakSecrets);
            instance = cfg;
        } catch (Exception e) {
            instance = new DungeonBreakerConfig();
        }
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("noBreakSecrets", noBreakSecrets);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** "Don't break secrets": the Dungeon Breaker (Breaker Aura, breaker nodes, the manual click) leaves secret blocks alone.
     *  See {@link BreakerGuard}. Fairy rooms are always protected and have no setting. */
    public boolean isNoBreakSecrets() {
        return noBreakSecrets;
    }

    public void setNoBreakSecrets(boolean noBreakSecrets) {
        this.noBreakSecrets = noBreakSecrets;
    }
}
