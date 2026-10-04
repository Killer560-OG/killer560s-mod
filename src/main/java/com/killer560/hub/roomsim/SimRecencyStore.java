package com.killer560.hub.roomsim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps {@link SimFloorLayout}'s "which rooms were on recent floors" memory across game restarts.
 *
 * <p>killer560 (2026-10-04): "It still feels like just about the exact same rooms every run." The recency map
 * lived only in memory, so every launch began with none and the first floors of each session leaned on the same
 * favourites. Kept in its own class rather than in {@link SimFloorLayout}, which deliberately has no Minecraft
 * or Fabric classes under it so the generator can be run outside the game.
 */
public final class SimRecencyStore {

    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-sim-recent.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean loaded;

    private SimRecencyStore() {
    }

    /** Loads the saved memory into the generator, once per session. */
    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(PATH)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Double> saved = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                saved.put(e.getKey(), e.getValue().getAsDouble());
            }
            SimFloorLayout.restoreRecency(saved);
        } catch (Exception ignored) {
            // Unreadable file: start with no memory, which is exactly what used to happen every launch.
        }
    }

    /** Writes the generator's memory out. Called after each generated floor. */
    public static synchronized void save() {
        try {
            JsonObject o = new JsonObject();
            for (Map.Entry<String, Double> e : SimFloorLayout.recencySnapshot().entrySet()) {
                o.addProperty(e.getKey(), Math.round(e.getValue() * 1000.0) / 1000.0);
            }
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
