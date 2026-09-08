package com.killer560.hub.rngmeter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent, disk-backed record of every RNG Meter reward item ever discovered by scanning a
 * real menu, keyed by a normalized menu title (a leading pagination marker like "(1/2) " stripped,
 * so every page of a multi-page floor accumulates under the same key - opening just page 1 of F7
 * still shows every reward ever seen across both its pages, from this session or a past one).
 *
 * <p>Saved to {@code config/killer560smod-rng-item-log.json}, sorted for a stable, human-readable,
 * shareable file - the whole point being that this can be copied to a friend's install and they
 * start with everything already discovered instead of re-scanning every menu themselves.
 */
public final class RngItemLog {

    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-rng-item-log.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, Map<String, Long>> LOG = new ConcurrentHashMap<>();

    private RngItemLog() {
    }

    public static synchronized void load() {
        LOG.clear();
        if (!Files.exists(PATH)) {
            return;
        }
        try {
            String json = Files.readString(PATH, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (String group : root.keySet()) {
                JsonObject items = root.getAsJsonObject(group);
                Map<String, Long> groupMap = new ConcurrentHashMap<>();
                for (String name : items.keySet()) {
                    groupMap.put(name, items.get(name).getAsLong());
                }
                LOG.put(group, groupMap);
            }
        } catch (Exception ignored) {
        }
    }

    /** Merges newly-scanned items into a group's accumulated set (overwriting stale values for names already known) and persists to disk. */
    public static synchronized void merge(String group, Map<String, Long> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<String, Long> groupMap = LOG.computeIfAbsent(group, k -> new ConcurrentHashMap<>());
        groupMap.putAll(items);
        save();
    }

    public static Map<String, Long> get(String group) {
        return LOG.getOrDefault(group, Map.of());
    }

    private static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Map<String, Object> sorted = new TreeMap<>();
            for (var entry : LOG.entrySet()) {
                sorted.put(entry.getKey(), new TreeMap<>(entry.getValue()));
            }
            Files.writeString(PATH, GSON.toJson(sorted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
