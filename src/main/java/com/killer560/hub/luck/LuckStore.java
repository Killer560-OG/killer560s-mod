package com.killer560.hub.luck;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.croesus.CroesusProfitLog;
import com.killer560.hub.gui.profit.ProfitScope;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedFile;
import com.killer560.hub.shared.SharedRecords;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * How Lucky Are You's records, in the shared store ({@link RecordCatalog#LUCK}, one file per Minecraft account, every
 * instance adding to it). Per Skyblock profile: attempts per activity, expected drops (the chance of every table drop of
 * that activity added at each attempt, plain and at the attempt's Magic Find), and drops got. Dungeon chests are not
 * stored here: they are worked out from the Croesus claim log ({@link CroesusProfitLog#allClaims}), which already holds
 * every chest he opened with its contents, so his saved claims count from the first day.
 *
 * <p>All public methods are synchronized; writes go to the shared store on one background thread.
 */
public final class LuckStore {

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-luck-writer");
        t.setDaemon(true);
        return t;
    });

    /** One profile's counters. */
    static final class Bucket {
        final Map<String, Long> attempts = new HashMap<>();
        final Map<String, Double> exp = new HashMap<>();
        final Map<String, Double> expMf = new HashMap<>();
        final Map<String, Long> got = new HashMap<>();
    }

    /** One drop as it happened. */
    public record LogEntry(long atMs, String drop, int amount, String source, int magicFind, String profile) {
    }

    private static final Map<String, Bucket> PROFILES = new LinkedHashMap<>();
    private static final List<LogEntry> LOG = new ArrayList<>();
    private static boolean loaded;
    private static int revision;

    private LuckStore() {
    }

    public static synchronized void load() {
        loaded = true;
        revision++;
        PROFILES.clear();
        LOG.clear();
        JsonObject root = SharedRecords.load(file());
        if (root.has("profiles") && root.get("profiles").isJsonObject()) {
            JsonObject ps = root.getAsJsonObject("profiles");
            for (String p : ps.keySet()) {
                JsonObject o = ps.getAsJsonObject(p);
                Bucket b = new Bucket();
                readLongs(o, "attempts", b.attempts);
                readDoubles(o, "exp", b.exp);
                readDoubles(o, "expMf", b.expMf);
                readLongs(o, "got", b.got);
                PROFILES.put(p, b);
            }
        }
        if (root.has("log") && root.get("log").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("log")) {
                try {
                    JsonObject o = e.getAsJsonObject();
                    LOG.add(new LogEntry(o.get("atMs").getAsLong(), o.get("drop").getAsString(),
                            o.has("amount") ? o.get("amount").getAsInt() : 1, str(o, "source"),
                            o.has("mf") ? o.get("mf").getAsInt() : 0, str(o, "profile")));
                } catch (RuntimeException ignored) {
                    // a hand-edited entry; the counters still hold its drop
                }
            }
        }
    }

    private static SharedFile file() {
        return SharedRecords.file(RecordCatalog.LUCK);
    }

    private static void ensure() {
        if (!loaded) {
            load();
        }
    }

    public static synchronized int revision() {
        return revision;
    }

    /** One attempt at {@code activity} (a slayer kill) at {@code magicFind}%: adds every table drop's chance. */
    public static synchronized void attempt(String activity, int magicFind) {
        ensure();
        Bucket b = bucket(ProfitScope.currentSkyblockProfile());
        b.attempts.merge(activity, 1L, Long::sum);
        for (DropTable.Drop d : DropTable.get().ofActivity(activity)) {
            if (!d.tracked()) {
                continue;
            }
            b.exp.merge(d.key(), d.chance(), Double::sum);
            b.expMf.merge(d.key(), LuckMath.withMagicFind(d, magicFind), Double::sum);
        }
        revision++;
        saveAsync();
    }

    /** A drop he got: {@code key} is a table key or {@code "<activity>|<name>"} for one the table does not have. */
    public static synchronized void drop(String key, int amount, String source, int magicFind) {
        ensure();
        String profile = ProfitScope.currentSkyblockProfile();
        bucket(profile).got.merge(key, 1L, Long::sum);
        LOG.add(new LogEntry(System.currentTimeMillis(), key, Math.max(1, amount), source, magicFind, profile));
        revision++;
        saveAsync();
    }

    private static Bucket bucket(String profile) {
        return PROFILES.computeIfAbsent(profile == null ? ProfitScope.UNKNOWN : profile, k -> new Bucket());
    }

    /** The Skyblock profiles with records here or in his Croesus claims. */
    public static synchronized Collection<String> profiles() {
        ensure();
        TreeSet<String> out = new TreeSet<>(PROFILES.keySet());
        String id = ProfitScope.currentAccountId();
        for (CroesusProfitLog.Claim c : CroesusProfitLog.allClaims()) {
            if (mine(c.key(), id)) {
                out.add(c.key().profile());
            }
        }
        return out;
    }

    public static synchronized List<LogEntry> recent(int max) {
        ensure();
        List<LogEntry> out = new ArrayList<>();
        for (int i = LOG.size() - 1; i >= 0 && out.size() < max; i--) {
            out.add(LOG.get(i));
        }
        return out;
    }

    private static boolean mine(ProfitScope.Key k, String accountId) {
        return k.matchesAccount(accountId);
    }

    // ---- the view ---------------------------------------------------------------------------------------------------

    /** One drop's numbers across the selected profiles. */
    public record Row(String key, String name, String activity, long attempts, double expected, double expectedBase,
                      long got, DropTable.Drop drop) {
        public double percentile() {
            return LuckMath.percentile(got, expected);
        }
    }

    /** Totals over every row with something expected. */
    public record Summary(int drops, double expected, long got, double meanPercentile, int above, int below) {
    }

    /**
     * Every drop with an attempt or a drop, for {@code profile} (null = every profile of this account). Expected uses the
     * Magic Find of each slayer kill unless {@code plainOdds}, and the chest chance {@code chestOdds} names.
     */
    public static synchronized List<Row> rows(String profile, LuckConfig.ChestOdds chestOdds, boolean plainOdds) {
        ensure();
        String cacheKey = profile + "|" + chestOdds + "|" + plainOdds + "|" + revision + "|" + CroesusProfitLog.revision()
                + "|" + ProfitScope.currentAccountId();
        if (cacheKey.equals(cachedKey)) {
            return cachedRows;
        }
        cachedRows = List.copyOf(computeRows(profile, chestOdds, plainOdds));
        cachedKey = cacheKey;
        return cachedRows;
    }

    private static String cachedKey;
    private static List<Row> cachedRows = List.of();

    private static List<Row> computeRows(String profile, LuckConfig.ChestOdds chestOdds, boolean plainOdds) {
        DropTable table = DropTable.get();
        Map<String, Long> attempts = new HashMap<>();
        Map<String, Double> exp = new HashMap<>();
        Map<String, Double> expBase = new HashMap<>();
        Map<String, Long> got = new HashMap<>();
        for (Map.Entry<String, Bucket> e : PROFILES.entrySet()) {
            if (profile != null && !profile.equals(e.getKey())) {
                continue;
            }
            Bucket b = e.getValue();
            b.attempts.forEach((k, v) -> attempts.merge(k, v, Long::sum));
            b.exp.forEach((k, v) -> expBase.merge(k, v, Double::sum));
            (plainOdds ? b.exp : b.expMf).forEach((k, v) -> exp.merge(k, v, Double::sum));
            b.got.forEach((k, v) -> got.merge(k, v, Long::sum));
        }
        addChests(profile, chestOdds, attempts, exp, expBase, got);

        TreeSet<String> keys = new TreeSet<>(exp.keySet());
        keys.addAll(got.keySet());
        List<Row> out = new ArrayList<>();
        for (String k : keys) {
            DropTable.Drop d = table.byKey(k);
            int bar = k.lastIndexOf('|');
            String activity = bar > 0 ? k.substring(0, bar) : "untracked";
            String name = d != null ? d.name() : bar > 0 ? k.substring(bar + 1) : k;
            out.add(new Row(k, name, activity, attempts.getOrDefault(activity, 0L), exp.getOrDefault(k, 0.0),
                    expBase.getOrDefault(k, 0.0), got.getOrDefault(k, 0L), d));
        }
        return out;
    }

    /** Attempts per activity for the selected profiles (chests included). */
    public static synchronized Map<String, Long> attempts(String profile) {
        ensure();
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, Bucket> e : PROFILES.entrySet()) {
            if (profile == null || profile.equals(e.getKey())) {
                e.getValue().attempts.forEach((k, v) -> out.merge(k, v, Long::sum));
            }
        }
        addChests(profile, LuckConfig.ChestOdds.BASE, out, new HashMap<>(), new HashMap<>(), new HashMap<>());
        return out;
    }

    private static void addChests(String profile, LuckConfig.ChestOdds odds, Map<String, Long> attempts,
                                  Map<String, Double> exp, Map<String, Double> expBase, Map<String, Long> got) {
        DropTable table = DropTable.get();
        String id = ProfitScope.currentAccountId();
        for (CroesusProfitLog.Claim c : CroesusProfitLog.allClaims()) {
            if (!mine(c.key(), id) || (profile != null && !profile.equals(c.key().profile()))) {
                continue;
            }
            String activity = "chest:" + c.floor() + ":" + c.chest();
            List<DropTable.Drop> drops = table.ofActivity(activity);
            if (drops.isEmpty()) {
                continue; // "Unknown" floor, or a chest the table has no page for
            }
            attempts.merge(activity, 1L, Long::sum);
            for (DropTable.Drop d : drops) {
                double base = d.chance();
                double used = odds == LuckConfig.ChestOdds.MAX && !Double.isNaN(d.chanceMax()) ? d.chanceMax() : base;
                if (!Double.isNaN(base)) {
                    exp.merge(d.key(), used, Double::sum);
                    expBase.merge(d.key(), base, Double::sum);
                }
            }
            for (CroesusProfitLog.LoggedItem item : c.items()) {
                DropTable.Drop d = table.find(activity, item.name());
                if (d != null) {
                    got.merge(d.key(), 1L, Long::sum);
                }
            }
        }
    }

    /** The screen's headline over {@code rows}: only rows with something expected count. */
    public static Summary summarize(List<Row> rows) {
        int n = 0;
        int above = 0;
        int below = 0;
        double exp = 0;
        long got = 0;
        double pSum = 0;
        for (Row r : rows) {
            if (r.expected() <= 0) {
                continue;
            }
            n++;
            exp += r.expected();
            got += r.got();
            double p = r.percentile();
            pSum += p;
            if (r.got() > r.expected()) {
                above++;
            } else if (r.got() < r.expected()) {
                below++;
            }
        }
        return new Summary(n, exp, got, n == 0 ? 0.5 : pSum / n, above, below);
    }

    // ---- disk ---------------------------------------------------------------------------------------------------------

    private static void saveAsync() {
        JsonObject root = toJson();
        SharedFile target = file();
        WRITER.execute(() -> SharedRecords.save(target, root));
    }

    /** Waits for every queued write (testkit). */
    public static void drain() throws Exception {
        WRITER.submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonObject ps = new JsonObject();
        for (Map.Entry<String, Bucket> e : PROFILES.entrySet()) {
            JsonObject o = new JsonObject();
            Bucket b = e.getValue();
            o.add("attempts", longs(b.attempts));
            o.add("exp", doubles(b.exp));
            o.add("expMf", doubles(b.expMf));
            o.add("got", longs(b.got));
            ps.add(e.getKey(), o);
        }
        root.add("profiles", ps);
        JsonArray log = new JsonArray();
        for (LogEntry l : LOG) {
            JsonObject o = new JsonObject();
            o.addProperty("atMs", l.atMs());
            o.addProperty("drop", l.drop());
            o.addProperty("amount", l.amount());
            o.addProperty("source", l.source());
            o.addProperty("mf", l.magicFind());
            o.addProperty("profile", l.profile());
            log.add(o);
        }
        root.add("log", log);
        return root;
    }

    private static JsonObject longs(Map<String, Long> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }

    private static JsonObject doubles(Map<String, Double> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }

    private static void readLongs(JsonObject parent, String key, Map<String, Long> into) {
        if (parent.has(key) && parent.get(key).isJsonObject()) {
            JsonObject o = parent.getAsJsonObject(key);
            for (String k : o.keySet()) {
                try {
                    into.put(k, o.get(k).getAsLong());
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static void readDoubles(JsonObject parent, String key, Map<String, Double> into) {
        if (parent.has(key) && parent.get(key).isJsonObject()) {
            JsonObject o = parent.getAsJsonObject(key);
            for (String k : o.keySet()) {
                try {
                    into.put(k, o.get(k).getAsDouble());
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
