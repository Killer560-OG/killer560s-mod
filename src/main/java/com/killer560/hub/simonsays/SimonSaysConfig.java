package com.killer560.hub.simonsays;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted settings for the Simon Says diagnostic logger - see {@link SimonSaysFeature}'s class doc
 *  for why this is a logger, not a solver, in this first pass. Ships disabled by default. */
public final class SimonSaysConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-simonsays.json");

    private static SimonSaysConfig instance;

    private boolean enabled = false;
    private int horizontalRadius = 8;
    private int verticalRadius = 4;

    private SimonSaysConfig() {
    }

    public static SimonSaysConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new SimonSaysConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            SimonSaysConfig cfg = new SimonSaysConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.horizontalRadius = obj.has("horizontalRadius") ? obj.get("horizontalRadius").getAsInt() : 8;
            cfg.verticalRadius = obj.has("verticalRadius") ? obj.get("verticalRadius").getAsInt() : 4;
            instance = cfg;
        } catch (Exception e) {
            instance = new SimonSaysConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("horizontalRadius", horizontalRadius);
            obj.addProperty("verticalRadius", verticalRadius);
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

    public int getHorizontalRadius() {
        return horizontalRadius;
    }

    public void setHorizontalRadius(int horizontalRadius) {
        this.horizontalRadius = Math.max(2, Math.min(16, horizontalRadius));
    }

    public int getVerticalRadius() {
        return verticalRadius;
    }

    public void setVerticalRadius(int verticalRadius) {
        this.verticalRadius = Math.max(1, Math.min(10, verticalRadius));
    }
}
