package com.killer560.hub.croesus;

import com.killer560.hub.gui.profit.ProfitScope;
import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Croesus Profit Logger storage: every confirmed claim appended to
 * {@code config/killer560/dungeons/runs/killer560smod-croesus-log.json} ({@code entries}).
 *
 * <p>Since 2026-10-11 every figure is DERIVED from the entries: each claim carries the Minecraft account, Skyblock
 * profile and mod profile it was made on ({@link ProfitScope.Key}), and the tracker screen's switches
 * ({@link #view()}) pick which of them the totals, graph and loot log add up. A reset records
 * {@code {filter, atMs}} in {@code resets} and hides every claim that filter selects from before that moment - the
 * entry history itself is never deleted (it is the "full log"; delete the file to wipe it). "Session" is every
 * claim since this game was started.
 *
 * <p>A pre-2026-10-11 file kept running {@code totals} beside its entries, and its old "Reset totals" cleared
 * those while keeping the entries. On first load that is turned into a reset at the claim the totals start after,
 * so the numbers it showed before are the numbers it shows now. Its claims have no names and read as "?".
 *
 * <p>Since 2026-10-15 every claim also carries the account's UUID, and the numbers follow {@link ProfitScope#resolve}
 * (This Account by default). A claim without a UUID is given one the first time the current account is known
 * ({@link #migrateAccounts}), and the file is written back with it.
 */
public final class CroesusProfitLog {

    private static final Logger LOGGER = ModLog.get("killer560smod-croesus");
    private static final Path PATH = ModPaths.config("killer560smod-croesus-log.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-croesus-log-writer");
        t.setDaemon(true);
        return t;
    });

    public static final String ALL = "All";
    /** Claims at or after this moment are this session's. */
    private static final long SESSION_START_MS = System.currentTimeMillis();

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

    /** One reset: the claims {@code filter} selects from before {@code atMs} are hidden. */
    private record Reset(ProfitScope.Filter filter, long atMs) {
    }

    private static JsonArray entries = new JsonArray();
    private static final List<Reset> RESETS = new ArrayList<>();
    private static ProfitScope.Filter view = new ProfitScope.Filter();
    private static boolean loaded = false;
    /** Whether {@link #migrateAccounts} has run on what {@link #load} read. */
    private static boolean accountsMigrated = false;
    /** Every entry parsed, rebuilt only when the log changes (the screen reads it every frame). */
    private static List<Claim> parsed = null;
    /** Bumped by every change to the entries, resets or view, so a screen can keep what it worked out until it moves. */
    private static int revision = 0;

    private CroesusProfitLog() {
    }

    public static synchronized int revision() {
        return revision;
    }

    public static synchronized void load() {
        loaded = true;
        revision++;
        entries = new JsonArray();
        RESETS.clear();
        view = new ProfitScope.Filter();
        parsed = null;
        accountsMigrated = false;
        if (!Files.exists(PATH)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("entries")) {
                entries = root.getAsJsonArray("entries");
            }
            if (root.has("view") && root.get("view").isJsonObject()) {
                view = ProfitScope.Filter.fromJson(root.getAsJsonObject("view"));
                view.account = null; // the account shown is Show Profit's (ProfitConfig), not this file's
            }
            if (root.has("resets")) {
                for (JsonElement e : root.getAsJsonArray("resets")) {
                    JsonObject o = e.getAsJsonObject();
                    RESETS.add(new Reset(ProfitScope.Filter.fromJson(o.getAsJsonObject("filter")),
                            o.get("atMs").getAsLong()));
                }
            } else if (!root.has("version")) {
                migrateLegacyTotals(root);
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

    /** The old file's totals counted only the last {@code totals.All.chests} claims (an old reset cleared them and
     *  kept the entries); hide the ones before as a reset at the last hidden claim's time. */
    private static void migrateLegacyTotals(JsonObject root) {
        int counted = 0;
        if (root.has("totals") && root.getAsJsonObject("totals").has(ALL)) {
            counted = Totals.fromJson(root.getAsJsonObject("totals").getAsJsonObject(ALL)).chests;
        }
        int hidden = entries.size() - counted;
        if (hidden <= 0) {
            return;
        }
        long at = timestampOf(entries.get(hidden - 1)) + 1; // hides that claim too (the test is "before")
        RESETS.add(new Reset(new ProfitScope.Filter(), at));
        LOGGER.info("[Croesus] Old log: the first {} claims were reset before, kept hidden", hidden);
    }

    private static long timestampOf(JsonElement e) {
        try {
            return Instant.parse(e.getAsJsonObject().get("timestamp").getAsString()).toEpochMilli();
        } catch (Exception ex) {
            return 0L;
        }
    }

    /** Appends one confirmed claim, tagged with the account, Skyblock profile and mod profile it was made on. */
    public static synchronized void record(String floor, DungeonChestValuer.ChestValue chest, String source) {
        if (!loaded) {
            load();
        }
        String floorKey = floor == null || floor.isBlank() ? "Unknown" : floor;
        JsonObject entry = new JsonObject();
        entry.addProperty("timestamp", Instant.now().toString());
        ProfitScope.current().writeTo(entry);
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
        parsed = null;
        saveAsync();
    }

    /** Resets everything, every account and profile. */
    public static synchronized void resetTotals() {
        reset(new ProfitScope.Filter());
    }

    /** Hides every claim {@code filter} selects from the totals, graph and loot log; other accounts and profiles
     *  are untouched and the entries stay in the file. */
    public static synchronized void reset(ProfitScope.Filter filter) {
        if (!loaded) {
            load();
        }
        // +1 so a claim logged in this same millisecond is still hidden.
        RESETS.add(new Reset(filter.copy(), System.currentTimeMillis() + 1));
        LOGGER.info("[Croesus] Totals reset for {} (entry history kept)", filter.describe());
        saveAsync();
    }

    /** Resets what the screen shows now ({@link #scope()}), and only that. */
    public static synchronized void resetView() {
        reset(scope());
    }

    /** What the screen shows: {@link #view()} with Show Profit and Profit Data applied. */
    public static synchronized ProfitScope.Filter scope() {
        return ProfitScope.resolve(view());
    }

    /** What the tracker screen's switches select; change its fields and call {@link #saveView()}. */
    public static synchronized ProfitScope.Filter view() {
        if (!loaded) {
            load();
        }
        return view;
    }

    public static synchronized void saveView() {
        saveAsync();
    }

    /** Every account / profile / mod profile the log has a claim from. */
    public static synchronized List<ProfitScope.Key> keys() {
        LinkedHashSet<ProfitScope.Key> out = new LinkedHashSet<>();
        for (Claim c : parsedClaims()) {
            out.add(c.key());
        }
        return List.copyOf(out);
    }

    /** Totals per floor plus {@link #ALL} for the switches' selection. */
    public static synchronized Map<String, Totals> allTime() {
        return allTime(scope());
    }

    public static synchronized Map<String, Totals> allTime(ProfitScope.Filter filter) {
        return totals(claims(filter), 0L);
    }

    /** Like {@link #allTime()}, counting only this game session's claims. */
    public static synchronized Map<String, Totals> session() {
        return session(scope());
    }

    public static synchronized Map<String, Totals> session(ProfitScope.Filter filter) {
        return totals(claims(filter), SESSION_START_MS);
    }

    private static Map<String, Totals> totals(List<Claim> claims, long since) {
        Map<String, Totals> map = new TreeMap<>();
        for (Claim c : claims) {
            if (c.timestampMs() < since) {
                continue;
            }
            map.computeIfAbsent(ALL, k -> new Totals()).add(c.cost(), c.value(), c.profit());
            map.computeIfAbsent(c.floor(), k -> new Totals()).add(c.cost(), c.value(), c.profit());
        }
        return copy(map);
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
                        long profit, List<LoggedItem> items, ProfitScope.Key key) {
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

    /** The claims the tracker screen's switches select and no reset has hidden, oldest first. */
    public static synchronized List<Claim> claims() {
        return claims(scope());
    }

    /** The claims {@code filter} selects that no reset has hidden, oldest first. */
    public static synchronized List<Claim> claims(ProfitScope.Filter filter) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : parsedClaims()) {
            if (c.key().matches(filter) && !hidden(c)) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean hidden(Claim c) {
        for (Reset r : RESETS) {
            if (c.timestampMs() < r.atMs() && c.key().matches(r.filter())) {
                return true;
            }
        }
        return false;
    }

    /** Every entry in the file, parsed once per change. */
    private static List<Claim> parsedClaims() {
        if (!loaded) {
            load();
        }
        migrateAccounts();
        if (parsed != null) {
            return parsed;
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
                    str(o, "source", "?"), num(o, "cost", 0), num(o, "value", 0), num(o, "profit", 0), items,
                    ProfitScope.Key.readFrom(o)));
        }
        parsed = List.copyOf(out);
        return parsed;
    }

    /** Gives every claim without an account UUID (or mod profile) one, as {@link ProfitScope#migrated} decides, once
     *  the current account is known; writes the file back when anything changed. */
    private static void migrateAccounts() {
        if (accountsMigrated || !ProfitScope.canMigrate()) {
            return;
        }
        accountsMigrated = true;
        int changed = 0;
        for (JsonElement e : entries) {
            if (e == null || !e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            ProfitScope.Key k = ProfitScope.Key.readFrom(o);
            ProfitScope.Key m = ProfitScope.migrated(k);
            if (m != k) {
                m.writeTo(o);
                changed++;
            }
        }
        if (changed > 0) {
            parsed = null;
            LOGGER.info("[Croesus] Gave {} older claim(s) an account", changed);
            saveAsync();
        }
    }

    /** A mod profile was renamed: its claims, resets and the screen's switch follow the new name. */
    public static synchronized void renameModProfile(String oldName, String newName) {
        if (!loaded) {
            load();
        }
        int changed = 0;
        for (JsonElement e : entries) {
            if (e != null && e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                ProfitScope.Key k = ProfitScope.Key.readFrom(o);
                if (k.modProfile().equals(oldName)) {
                    k.withModProfile(newName).writeTo(o);
                    changed++;
                }
            }
        }
        for (Reset r : RESETS) {
            if (oldName.equals(r.filter().modProfile)) {
                r.filter().modProfile = newName;
                changed++;
            }
        }
        if (oldName.equals(view.modProfile)) {
            view.modProfile = newName;
            changed++;
        }
        if (changed > 0) {
            parsed = null;
            saveAsync();
        }
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
        revision++; // every change to the entries, resets or view ends here
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        root.add("view", view.toJson());
        JsonArray resets = new JsonArray();
        for (Reset r : RESETS) {
            JsonObject o = new JsonObject();
            o.add("filter", r.filter().toJson());
            o.addProperty("atMs", r.atMs());
            resets.add(o);
        }
        root.add("resets", resets);
        // Every visible claim's totals per floor, as the old format kept them - only so an older jar reading this
        // file still shows sensible numbers. Nothing here reads it back.
        JsonObject totals = new JsonObject();
        for (Map.Entry<String, Totals> e : totals(claims(new ProfitScope.Filter()), 0L).entrySet()) {
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
                CroesusRunMemory.replace(tmp, PATH, json);
            } catch (Exception e) {
                LOGGER.warn("[Croesus] Failed to write {}", PATH.getFileName(), e);
            }
        });
    }
}
