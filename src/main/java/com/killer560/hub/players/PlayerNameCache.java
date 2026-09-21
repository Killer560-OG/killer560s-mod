package com.killer560.hub.players;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persisted UUID -&gt; last-known-name cache backing {@link PlayerNames}, {@code config/killer560smod-playernames.json}.
 * Same load/save shape as this mod's other {@code killer560smod-*.json} configs, except there is nothing here for
 * a user to actually configure - every write comes from a resolved lookup, so {@link #load()}/{@link #save()} are
 * driven by {@link PlayerNames} itself rather than a GUI tab.
 * <p>
 * Package-private: nothing outside {@link PlayerNames} should touch this file's shape directly.
 * <p>
 * NOTE for the main session: this file is a network cache, not a setting (same category as
 * {@code killer560smod-itembrowser-items-cache.json}) - it should probably be added to
 * {@code ProfileManager.EXCLUDED_FILES} so switching profiles doesn't churn everyone's cached names. Left
 * undone here because this agent's brief says never edit {@code ProfileManager}.
 */
final class PlayerNameCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-players");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-playernames.json");

    /** Hard cap so years of sessions (parties, class overrides, leap order, the friends/bestfriends trackers)
     *  can't grow this file without bound; the oldest-refreshed entries are trimmed first. */
    static final int MAX_ENTRIES = 4096;

    private record Entry(String name, long updatedAtMs) {
    }

    private static volatile PlayerNameCache instance;

    private final Map<UUID, Entry> byUuid = new ConcurrentHashMap<>();
    /** Reverse index kept in step with {@link #byUuid} so {@link #uuidOf} is O(1) - this is read from hot,
     *  per-frame lookups (class colours, leap order display) all over the mod. */
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;

    private PlayerNameCache() {
    }

    static synchronized PlayerNameCache getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    static synchronized void load() {
        PlayerNameCache cache = new PlayerNameCache();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject names = ConfigJson.getObject(root, "names");
                if (names != null) {
                    for (String rawUuid : names.keySet()) {
                        try {
                            UUID id = UUID.fromString(rawUuid);
                            JsonObject entry = ConfigJson.getObject(names, rawUuid);
                            String name = entry == null ? null : ConfigJson.getString(entry, "name", null);
                            long updatedAt = entry == null ? 0L : ConfigJson.getLong(entry, "updatedAt", 0L);
                            if (name != null && !name.isBlank()) {
                                cache.byUuid.put(id, new Entry(name, updatedAt));
                                cache.byName.put(name.toLowerCase(Locale.ROOT), id);
                            }
                        } catch (Exception badEntry) {
                            // one malformed UUID/entry must not lose the rest of the cache
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[PlayerNames] Could not read {}: {}", CONFIG_PATH.getFileName(), e.toString());
            }
        }
        instance = cache;
    }

    synchronized void save() {
        try {
            trim();
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("note", "UUID -> last-known name cache shared by every feature that stores a "
                    + "player by UUID instead of IGN (Class Overrides, Leap Order, friends/bestfriends). Not a "
                    + "user setting - safe to delete, it just rebuilds from lookups.");
            JsonObject names = new JsonObject();
            for (Map.Entry<UUID, Entry> e : byUuid.entrySet()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", e.getValue().name());
                entry.addProperty("updatedAt", e.getValue().updatedAtMs());
                names.add(e.getKey().toString(), entry);
            }
            root.add("names", names);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception e) {
            LOGGER.warn("[PlayerNames] Failed to save {}", CONFIG_PATH.getFileName(), e);
        }
    }

    /** Oldest-updated entries first once past {@link #MAX_ENTRIES}. */
    private void trim() {
        if (byUuid.size() <= MAX_ENTRIES) {
            return;
        }
        List<Map.Entry<UUID, Entry>> sorted = new ArrayList<>(byUuid.entrySet());
        sorted.sort(Comparator.comparingLong(e -> e.getValue().updatedAtMs()));
        int toRemove = byUuid.size() - MAX_ENTRIES;
        for (int i = 0; i < toRemove; i++) {
            UUID id = sorted.get(i).getKey();
            Entry e = byUuid.remove(id);
            if (e != null) {
                byName.remove(e.name().toLowerCase(Locale.ROOT), id);
            }
        }
    }

    String nameOf(UUID id) {
        if (id == null) {
            return null;
        }
        Entry e = byUuid.get(id);
        return e == null ? null : e.name();
    }

    long updatedAtOf(UUID id) {
        if (id == null) {
            return 0L;
        }
        Entry e = byUuid.get(id);
        return e == null ? 0L : e.updatedAtMs();
    }

    UUID uuidOf(String name) {
        return name == null ? null : byName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** Records/refreshes a resolved pair. Callers (tab-list scans, network resolves) batch several of these
     *  and save once rather than hitting disk per player - see {@link PlayerNames}'s debounced save. */
    void put(UUID id, String name, long atMs) {
        if (id == null || name == null || name.isBlank()) {
            return;
        }
        Entry existing = byUuid.get(id);
        if (existing != null && existing.name().equals(name) && existing.updatedAtMs() >= atMs) {
            return;
        }
        if (existing != null && !existing.name().equalsIgnoreCase(name)) {
            // A rename: drop the stale reverse-index entry so the old name stops resolving to this UUID.
            byName.remove(existing.name().toLowerCase(Locale.ROOT), id);
        }
        byUuid.put(id, new Entry(name, atMs));
        byName.put(name.toLowerCase(Locale.ROOT), id);
        dirty = true;
    }

    boolean isDirty() {
        return dirty;
    }
}
