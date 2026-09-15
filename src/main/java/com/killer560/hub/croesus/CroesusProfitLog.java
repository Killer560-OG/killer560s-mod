package com.killer560.hub.croesus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Croesus Profit Logger storage: every confirmed claim appended to
 * {@code config/killer560smod-croesus-log.json} ({@code entries}), plus running all-time totals per floor
 * ({@code totals}) and in-memory session totals. "Reset totals" clears both totals; the entry history is
 * kept (it's the "full log" - delete the file to wipe it).
 */
public final class CroesusProfitLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-croesus");
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-croesus-log.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-croesus-log-writer");
        t.setDaemon(true);
        return t;
    });

    public static final String ALL = "All";

    public static final class Totals {
        public int chests;
        public long cost;
        public long value;
        public long profit;

        void add(long cost, long value, long profit) {
            chests++;
            this.cost += cost;
            this.value += value;
            this.profit += profit;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("chests", chests);
            o.addProperty("cost", cost);
            o.addProperty("value", value);
            o.addProperty("profit", profit);
            return o;
        }

        static Totals fromJson(JsonObject o) {
            Totals t = new Totals();
            t.chests = o.has("chests") ? o.get("chests").getAsInt() : 0;
            t.cost = o.has("cost") ? o.get("cost").getAsLong() : 0;
            t.value = o.has("value") ? o.get("value").getAsLong() : 0;
            t.profit = o.has("profit") ? o.get("profit").getAsLong() : 0;
            return t;
        }
    }

    private static final Map<String, Totals> ALL_TIME = new TreeMap<>();
    private static final Map<String, Totals> SESSION = new TreeMap<>();
    private static JsonArray entries = new JsonArray();
    private static boolean loaded = false;

    private CroesusProfitLog() {
    }

    public static synchronized void load() {
        loaded = true;
        ALL_TIME.clear();
        entries = new JsonArray();
        if (!Files.exists(PATH)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("totals")) {
                JsonObject totals = root.getAsJsonObject("totals");
                for (String key : totals.keySet()) {
                    ALL_TIME.put(key, Totals.fromJson(totals.getAsJsonObject(key)));
                }
            }
            if (root.has("entries")) {
                entries = root.getAsJsonArray("entries");
            }
        } catch (Exception e) {
            // 2026-09-15 persistence audit: the next record() saves over PATH, which used to silently
            // destroy the whole unreadable claim history - keep a copy of the original file first.
            Path backup = PATH.resolveSibling(PATH.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.copy(PATH, backup);
                LOGGER.warn("[Croesus] Could not read {} - backed it up to {} and starting a fresh log",
                        PATH.getFileName(), backup.getFileName(), e);
            } catch (Exception backupError) {
                LOGGER.warn("[Croesus] Could not read {} - starting a fresh log (backup copy also failed: {})",
                        PATH.getFileName(), backupError.toString(), e);
            }
        }
    }

    /** Appends one confirmed claim and updates session + all-time totals (per floor and "All"). */
    public static synchronized void record(String floor, DungeonChestValuer.ChestValue chest, String source) {
        if (!loaded) {
            load();
        }
        String floorKey = floor == null || floor.isBlank() ? "Unknown" : floor;
        JsonObject entry = new JsonObject();
        entry.addProperty("timestamp", Instant.now().toString());
        entry.addProperty("floor", floorKey);
        entry.addProperty("chest", chest.type().display);
        entry.addProperty("source", source);
        JsonArray items = new JsonArray();
        for (DungeonChestValuer.PricedItem item : chest.items()) {
            JsonObject it = new JsonObject();
            it.addProperty("name", item.name());
            it.addProperty("id", item.id());
            it.addProperty("amount", item.amount());
            if (item.unitPrice() != null) {
                it.addProperty("unitPrice", item.unitPrice());
            }
            it.addProperty("total", item.total());
            if (item.excluded()) {
                it.addProperty("excluded", true);
            }
            items.add(it);
        }
        entry.add("items", items);
        entry.addProperty("cost", chest.cost());
        entry.addProperty("value", chest.value());
        entry.addProperty("profit", chest.profit());
        entry.addProperty("unpricedItems", chest.unpricedCount());
        entries.add(entry);

        for (Map<String, Totals> map : java.util.List.of(ALL_TIME, SESSION)) {
            map.computeIfAbsent(ALL, k -> new Totals()).add(chest.cost(), chest.value(), chest.profit());
            map.computeIfAbsent(floorKey, k -> new Totals()).add(chest.cost(), chest.value(), chest.profit());
        }
        LOGGER.info("[Croesus] Logged claim: {} {} chest, cost={}, value={}, profit={}, unpriced={} ({})",
                floorKey, chest.type().display, chest.cost(), chest.value(), chest.profit(), chest.unpricedCount(), source);
        saveAsync();
    }

    public static synchronized void resetTotals() {
        if (!loaded) {
            load();
        }
        ALL_TIME.clear();
        SESSION.clear();
        LOGGER.info("[Croesus] Totals reset (entry history kept)");
        saveAsync();
    }

    public static synchronized Map<String, Totals> allTime() {
        if (!loaded) {
            load();
        }
        return copy(ALL_TIME);
    }

    public static synchronized Map<String, Totals> session() {
        return copy(SESSION);
    }

    public static synchronized int entryCount() {
        if (!loaded) {
            load();
        }
        return entries.size();
    }

    private static Map<String, Totals> copy(Map<String, Totals> src) {
        Map<String, Totals> out = new LinkedHashMap<>();
        Totals all = src.get(ALL);
        if (all != null) {
            out.put(ALL, all);
        }
        for (Map.Entry<String, Totals> e : src.entrySet()) {
            if (!e.getKey().equals(ALL)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static void saveAsync() {
        JsonObject root = new JsonObject();
        JsonObject totals = new JsonObject();
        for (Map.Entry<String, Totals> e : ALL_TIME.entrySet()) {
            totals.add(e.getKey(), e.getValue().toJson());
        }
        root.add("totals", totals);
        root.add("entries", entries.deepCopy());
        String json = GSON.toJson((JsonElement) root);
        WRITER.submit(() -> {
            try {
                Files.createDirectories(PATH.getParent());
                Path tmp = PATH.resolveSibling(PATH.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LOGGER.warn("[Croesus] Failed to write {}", PATH.getFileName(), e);
            }
        });
    }
}
