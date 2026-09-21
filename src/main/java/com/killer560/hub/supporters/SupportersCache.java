package com.killer560.hub.supporters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Last-good {@code GET /supporters} response, persisted to {@code killer560smod-supporters-cache.json} so a
 * relay outage "changes nothing" ({@code SUPPORTERS-CONTRACT.md}) - the mod keeps showing whatever it last
 * fetched, across restarts, until a fresh fetch actually succeeds.
 * <p>
 * NOTE for the main session: this is a network cache, not a user setting (same category as
 * {@code killer560smod-playernames.json}) - it should be added to {@code ProfileManager.EXCLUDED_FILES} so
 * switching profiles doesn't churn it. Left undone here because this agent's brief says never edit
 * {@code ProfileManager} - see this feature's staging notes.
 */
final class SupportersCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-supporters");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-supporters-cache.json");

    private SupportersCache() {
    }

    record Loaded(int version, List<SupporterEntry> entries) {
    }

    static Loaded load() {
        if (!Files.exists(CONFIG_PATH)) {
            return new Loaded(-1, List.of());
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            int version = ConfigJson.getInt(obj, "version", -1);
            List<SupporterEntry> out = new ArrayList<>();
            JsonArray arr = ConfigJson.getArray(obj, "supporters");
            if (arr != null) {
                for (JsonElement el : arr) {
                    try {
                        if (el == null || !el.isJsonObject()) {
                            continue;
                        }
                        JsonObject o = el.getAsJsonObject();
                        UUID uuid = UUID.fromString(ConfigJson.getString(o, "uuid", ""));
                        String name = ConfigJson.getString(o, "name", "");
                        float scale = ConfigJson.getFloat(o, "scale", 1.0f);
                        out.add(new SupporterEntry(uuid, name, scale));
                    } catch (Exception badEntry) {
                        // one malformed row must not lose the rest of the cached list
                    }
                }
            }
            return new Loaded(version, List.copyOf(out));
        } catch (Exception e) {
            LOGGER.warn("[Supporters] Could not read {}: {}", CONFIG_PATH.getFileName(), e.toString());
            return new Loaded(-1, List.of());
        }
    }

    static void save(int version, List<SupporterEntry> entries) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("note", "Cached GET /supporters response - a network cache, not a setting. "
                    + "Safe to delete; it refetches from the relay.");
            obj.addProperty("version", version);
            JsonArray arr = new JsonArray();
            for (SupporterEntry e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", e.uuid().toString());
                o.addProperty("name", e.rawName());
                o.addProperty("scale", e.scale());
                arr.add(o);
            }
            obj.add("supporters", arr);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[Supporters] Failed to save {}", CONFIG_PATH.getFileName(), e);
        }
    }
}
