package com.killer560.hub.diorite;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "I Hate Diorite" settings - see {@link DioriteGlassFeature}. Ships disabled by default.
 *  Real client-side block replacement (Storm's real diorite pillars swapped to see-through stained
 *  glass), same real category Noamm's own reference implementation gates behind its cheat flag - no
 *  legit variant exists, same as {@link com.killer560.hub.autoleap.AutoLeapConfig}/
 *  {@link com.killer560.hub.dungeonbreaker.DungeonBreakerConfig}. */
public final class DioriteGlassConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-dioriteglass.json");

    private static DioriteGlassConfig instance;

    private boolean enabled = false;

    private DioriteGlassConfig() {
    }

    public static DioriteGlassConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new DioriteGlassConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            DioriteGlassConfig cfg = new DioriteGlassConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new DioriteGlassConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated the same single-source-of-truth way as {@code MobEspConfig#isCheatMode} - the legit jar
     *  can never report true here even from a copied cheat-build config.json. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
