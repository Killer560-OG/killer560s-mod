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
import java.util.List;
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
        // killer560, 2026-09-20: "you can log things like the run number as well for bigger drops like master
        // stars, handle, mask". The run number is just this claim's 1-based position in the log; it is written
        // out so a hand-trimmed file keeps the numbers it already showed, and derived from the index when a
        // pre-2026-09-21 entry has none.
        entry.addProperty("run", entries.size() + 1);
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

    // ---- claim history / item log ------------------------------------------------------------------
    // killer560, 2026-09-20: "make sure it separates things by floors and keeps a running log of all items.
    // I should be able to open a menu that shows how many of each item from how many floors". Everything
    // below is DERIVED from the entries already in killer560smod-croesus-log.json - no second store, no new
    // file format, so an existing log gets the item menu retroactively.

    /** One reward line of one claim. */
    public record LoggedItem(String name, String id, int amount, long total) {
    }

    /** One claimed chest, as the log file recorded it. */
    public record Claim(int run, long timestampMs, String floor, String chest, String source, long cost, long value,
                        long profit, List<LoggedItem> items) {
        public Claim {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** One Skyblock item across the whole log: how many, from how many chests, split by floor. */
    public record ItemTotals(String id, String name, int amount, int chests, long value,
                             Map<String, Integer> byFloor, List<Integer> notableRuns) {
        public ItemTotals {
            byFloor = byFloor == null ? Map.of() : Map.copyOf(byFloor);
            notableRuns = notableRuns == null ? List.of() : List.copyOf(notableRuns);
        }
    }

    /** Ids that always get their run number recorded, however the Bazaar happens to be priced that day.
     *  killer560 named "master stars, handle, mask, or anything you deem to be rare". */
    private static final java.util.Set<String> NOTABLE_IDS = java.util.Set.of(
            "FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR",
            "NECRON_HANDLE", "DARK_CLAYMORE", "SHADOW_FURY", "GIANTS_SWORD", "PRECURSOR_EYE", "WITHER_BLOOD",
            "NECRON_DYE", "DYE_NECRON", "DYE_LIVID", "IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL", "WITHER_SHIELD_SCROLL",
            "BONZO_MASK", "SPIRIT_MASK", "STARRED_BONZO_MASK", "STARRED_SPIRIT_MASK", "THIRD_EYE_MASK",
            "LIVID_DAGGER", "WARPED_STONE", "AOTE_STONE", "SPIRIT_DECOY", "NECROMANCER_BROOCH", "MASTER_SKULL_TIER_7",
            "SHARD_APEX_DRAGON", "PET_GOLDEN_DRAGON");
    /** Anything worth this much in one claim counts as a big drop even if it isn't on the list above. */
    private static final long NOTABLE_VALUE = 10_000_000L;

    public static boolean isNotable(String id, long total) {
        return (id != null && NOTABLE_IDS.contains(id)) || total >= NOTABLE_VALUE;
    }

    /** Every claim in the log, oldest first. */
    public static synchronized List<Claim> claims() {
        if (!loaded) {
            load();
        }
        List<Claim> out = new java.util.ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            JsonElement element = entries.get(i);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject o = element.getAsJsonObject();
            List<LoggedItem> items = new java.util.ArrayList<>();
            if (o.has("items") && o.get("items").isJsonArray()) {
                for (JsonElement ie : o.getAsJsonArray("items")) {
                    if (ie == null || !ie.isJsonObject()) {
                        continue;
                    }
                    JsonObject it = ie.getAsJsonObject();
                    if (excluded(it)) {
                        continue; // essence when "Include Essence" was off - it was never counted as value
                    }
                    items.add(new LoggedItem(str(it, "name", "?"), str(it, "id", ""),
                            (int) num(it, "amount", 1), num(it, "total", 0)));
                }
            }
            long ts = 0L;
            try {
                ts = Instant.parse(str(o, "timestamp", "")).toEpochMilli();
            } catch (Exception ignored) {
            }
            out.add(new Claim((int) num(o, "run", i + 1), ts, str(o, "floor", "Unknown"), str(o, "chest", "?"),
                    str(o, "source", "?"), num(o, "cost", 0), num(o, "value", 0), num(o, "profit", 0), items));
        }
        return out;
    }

    /** Every item ever claimed, most valuable first, with per-floor counts and the run numbers of big drops. */
    public static List<ItemTotals> itemIndex() {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Long> values = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> floors = new LinkedHashMap<>();
        Map<String, List<Integer>> notable = new LinkedHashMap<>();
        for (Claim claim : claims()) {
            for (LoggedItem item : claim.items()) {
                String key = item.id() == null || item.id().isBlank() ? item.name() : item.id();
                names.putIfAbsent(key, item.name());
                int[] c = counts.computeIfAbsent(key, k -> new int[2]);
                c[0] += Math.max(1, item.amount());
                c[1]++;
                values.merge(key, item.total(), Long::sum);
                floors.computeIfAbsent(key, k -> new TreeMap<>())
                        .merge(claim.floor(), Math.max(1, item.amount()), Integer::sum);
                if (isNotable(item.id(), item.total())) {
                    notable.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(claim.run());
                }
            }
        }
        List<ItemTotals> out = new java.util.ArrayList<>(counts.size());
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            String key = e.getKey();
            out.add(new ItemTotals(key, names.getOrDefault(key, key), e.getValue()[0], e.getValue()[1],
                    values.getOrDefault(key, 0L), floors.get(key), notable.get(key)));
        }
        out.sort((a, b) -> Long.compare(b.value(), a.value()));
        return out;
    }

    /** Every big drop in the log, newest first - the "run number for master stars / handle / mask" view. */
    public static List<Object[]> notableDrops() {
        List<Object[]> out = new java.util.ArrayList<>();
        for (Claim claim : claims()) {
            for (LoggedItem item : claim.items()) {
                if (isNotable(item.id(), item.total())) {
                    out.add(new Object[]{claim.run(), claim.floor(), item.name(), item.total(), claim.timestampMs()});
                }
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    private static String str(JsonObject o, String key, String def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static long num(JsonObject o, String key, long def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean excluded(JsonObject o) {
        try {
            return o.has("excluded") && o.get("excluded").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
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
