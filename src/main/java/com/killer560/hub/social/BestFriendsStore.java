package com.killer560.hub.social;

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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The persisted Party Time Tracker data: {@code config/killer560smod-social/bestfriends.json}.
 * <p>
 * A subfolder file, deliberately - same reasoning as {@code runsummary.RunHistoryStore}: this is real
 * accumulated data killer560 explicitly wants kept "forever" (2026-09-21 settled answer), not a setting, so
 * it must never live where {@code profiles.ProfileManager} could overwrite it by applying/importing a
 * profile. {@link BestFriendsConfig} (the on/off toggle and the menu's own sort/filter) is the separate,
 * ordinary settings file for this feature.
 * <p>
 * Keyed on UUID, never IGN ("incase they ever change their name" - killer560's rule, restated in the task
 * brief). {@link #lastKnownName} is refreshed opportunistically whenever the player is seen on the tab list
 * again; the record itself never moves if they change name.
 * <p>
 * Writes go through a tmp file + atomic move on a daemon thread, exactly like {@code RunHistoryStore}/
 * {@code croesus.CroesusProfitLog}. {@link BestFriendsTracker} calls {@link #saveAsync()} periodically
 * (every 30s while actively accruing) and on disconnect, so a crash loses at most that window - the brief's
 * own "so a crash loses at most a minute" bound.
 */
public final class BestFriendsStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-bestfriends");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-social");
    private static final Path FILE = DIR.resolve("bestfriends.json");
    private static final int FILE_VERSION = 1;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-bestfriends-writer");
        t.setDaemon(true);
        return t;
    });

    /** One tracked player. Mutable on purpose - {@link BestFriendsTracker} updates these in place every
     *  flush rather than replacing the whole record, so a read mid-update never sees a half-built object. */
    public static final class Record {
        public final UUID uuid;
        public String lastKnownName;
        public long totalPartySeconds;
        public long firstPartiedAtMs;
        public long lastPartiedAtMs;
        /** Dungeon runs completed together, by {@code DungeonState.getFloor()}'s own key ("F1".."F7",
         *  "M1".."M7" for Master Mode - Master floors are their own keys, never merged with normal ones). */
        public final Map<String, Integer> dungeonRunsByFloor = new LinkedHashMap<>();
        /** Work-in-progress per the brief ("Kuudra can be work-in-progress") - this mod has no verified,
         *  chat/scoreboard-based "a Kuudra run just finished" signal to hook (see impl-bestfriends.md's
         *  "Left undone" note), so this is always 0 for now. Field kept (and persisted) so a later pass
         *  can start incrementing it without a data-migration. */
        public int kuudraRuns;

        Record(UUID uuid) {
            this.uuid = uuid;
        }

        public int totalDungeonRuns() {
            int total = 0;
            for (int v : dungeonRunsByFloor.values()) {
                total += v;
            }
            return total;
        }
    }

    private static final Map<UUID, Record> RECORDS = new LinkedHashMap<>();
    private static boolean loaded = false;
    private static boolean dirty = false;

    private BestFriendsStore() {
    }

    public static synchronized void load() {
        RECORDS.clear();
        loaded = true;
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray players = ConfigJson.getArray(root, "players");
            if (players != null) {
                for (JsonElement element : players) {
                    Record record = fromJson(element);
                    if (record != null) {
                        RECORDS.put(record.uuid, record);
                    }
                }
            }
            LOGGER.info("[BestFriends] Loaded {} tracked player(s) from {}", RECORDS.size(), FILE);
        } catch (Exception e) {
            Path backup = FILE.resolveSibling(FILE.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.copy(FILE, backup);
                LOGGER.warn("[BestFriends] Could not read {} - backed it up to {} and starting fresh",
                        FILE.getFileName(), backup.getFileName(), e);
            } catch (Exception backupError) {
                LOGGER.warn("[BestFriends] Could not read {} - starting fresh (backup also failed: {})",
                        FILE.getFileName(), backupError.toString(), e);
            }
            RECORDS.clear();
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static Record fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        String uuidStr = ConfigJson.getString(obj, "uuid", null);
        if (uuidStr == null) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Record record = new Record(uuid);
        record.lastKnownName = ConfigJson.getString(obj, "lastKnownName", "");
        record.totalPartySeconds = ConfigJson.getLong(obj, "totalPartySeconds", 0L);
        record.firstPartiedAtMs = ConfigJson.getLong(obj, "firstPartiedAtMs", 0L);
        record.lastPartiedAtMs = ConfigJson.getLong(obj, "lastPartiedAtMs", 0L);
        record.kuudraRuns = ConfigJson.getInt(obj, "kuudraRuns", 0);
        JsonObject floors = ConfigJson.getObject(obj, "dungeonRunsByFloor");
        if (floors != null) {
            for (Map.Entry<String, JsonElement> e : floors.entrySet()) {
                try {
                    int v = e.getValue().getAsInt();
                    if (v > 0) {
                        record.dungeonRunsByFloor.put(e.getKey(), v);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return record;
    }

    private static JsonObject toJson(Record record) {
        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", record.uuid.toString());
        obj.addProperty("lastKnownName", record.lastKnownName == null ? "" : record.lastKnownName);
        obj.addProperty("totalPartySeconds", record.totalPartySeconds);
        obj.addProperty("firstPartiedAtMs", record.firstPartiedAtMs);
        obj.addProperty("lastPartiedAtMs", record.lastPartiedAtMs);
        obj.addProperty("kuudraRuns", record.kuudraRuns);
        JsonObject floors = new JsonObject();
        for (Map.Entry<String, Integer> e : record.dungeonRunsByFloor.entrySet()) {
            floors.addProperty(e.getKey(), e.getValue());
        }
        obj.add("dungeonRunsByFloor", floors);
        return obj;
    }

    /** @return the live record for {@code uuid}, creating one (with {@code firstPartiedAtMs} = now) if this
     *  is the first time this player has ever been seen partied. Never returns null. */
    public static synchronized Record getOrCreate(UUID uuid, String name) {
        ensureLoaded();
        Record record = RECORDS.computeIfAbsent(uuid, id -> {
            Record r = new Record(id);
            r.firstPartiedAtMs = System.currentTimeMillis();
            return r;
        });
        if (name != null && !name.isBlank() && !name.equals(record.lastKnownName)) {
            record.lastKnownName = name;
        }
        dirty = true;
        return record;
    }

    /** Read-only snapshot for the menu - never mutate the returned records directly. */
    public static synchronized List<Record> records() {
        ensureLoaded();
        return new ArrayList<>(RECORDS.values());
    }

    public static synchronized Record get(UUID uuid) {
        ensureLoaded();
        return RECORDS.get(uuid);
    }

    /** Writes now only if something changed since the last save - called on the periodic flush timer and
     *  on disconnect. Safe to call often; a no-op write costs nothing but a boolean check. */
    public static synchronized void saveIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        saveAsync();
    }

    /** Forces a write regardless of the dirty flag - used on disconnect so the very last few seconds of a
     *  session are never lost even if nothing else has changed the flag since the last periodic flush. */
    public static synchronized void saveAsync() {
        ensureLoaded();
        JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        JsonArray array = new JsonArray();
        for (Record record : RECORDS.values()) {
            array.add(toJson(record));
        }
        root.add("players", array);
        String json = GSON.toJson((JsonElement) root);
        WRITER.submit(() -> {
            try {
                Files.createDirectories(DIR);
                Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LOGGER.warn("[BestFriends] Failed to write {}", FILE, e);
            }
        });
    }
}
