package com.killer560.hub.rngmeter;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * <p>Saved to {@code config/killer560/dungeons/runs/killer560smod-rng-item-log.json}, sorted for a stable, human-readable,
 * shareable file - the whole point being that this can be copied to a friend's install and they
 * start with everything already discovered instead of re-scanning every menu themselves.
 */
public final class RngItemLog {

    private static final Path PATH = ModPaths.config("killer560smod-rng-item-log.json");
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

    /**
     * Writes happen on one background thread: merge() runs on the render thread (a menu commit or a rescan that found
     * something new), and a pretty-printed JSON write there is file I/O in the middle of a frame. The map is copied
     * here, under the lock, so the writer never sees a half-merged log; writes queue in order, so the last one wins.
     */
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-rngmeter-log-writer");
        t.setDaemon(true);
        return t;
    });

    private static void save() {
        Map<String, Object> sorted = new TreeMap<>();
        for (var entry : LOG.entrySet()) {
            sorted.put(entry.getKey(), new TreeMap<>(entry.getValue()));
        }
        WRITER.execute(() -> {
            try {
                Files.createDirectories(PATH.getParent());
                Files.writeString(PATH, GSON.toJson(sorted), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        });
    }
}
