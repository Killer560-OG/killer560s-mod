package com.killer560.hub.shared;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Every per-player RECORD the mod keeps (profit, run history, stats), as opposed to a setting (shared-stats-1101). Records
 * live in the shared store, so every instance on the PC adds to the same ones; settings stay per instance and in profiles.
 *
 * <p>GLOBAL records carry their account inside each entry (the profit trackers' ProfitScope key) or are game facts (RNG
 * Meter item scores), so all accounts share one file and the screens filter by account as before. ACCOUNT records are
 * one player's own and live in {@code accounts/<uuid>/}.
 *
 * <p>Left out on purpose: the Party Finder stats cache, auction price history and other downloads (caches, not his
 * records), the sim's map history (sim tooling), and the shelved mining profit file (mining is not compiled until
 * after 2.0; the 2026-11-01 backup keeps it).
 */
public final class RecordCatalog {

    private RecordCatalog() {
    }

    public enum Scope { GLOBAL, ACCOUNT }

    /**
     * @param legacyName the old file or folder name under the instance config ({@code ModPaths.config(legacyName)})
     * @param legacyInner the file inside that folder, or null when legacyName is the file itself
     * @param sharedName  the file's name in the shared store
     */
    public record Kind(String legacyName, String legacyInner, String sharedName, Scope scope, JsonMerge.Policy policy) {
    }

    public static final Kind CROESUS_LOG = new Kind("killer560smod-croesus-log.json", null, "croesus-log.json",
            Scope.GLOBAL, new JsonMerge.Policy() {
        @Override
        public Function<JsonElement, String> arrayKey(String path) {
            if (path.equals("entries")) return JsonMerge.fields("timestamp", "floor", "chest", "profit");
            if (path.equals("resets")) {
                return e -> e.getAsJsonObject().get("atMs").getAsString() + "|" + e.getAsJsonObject().get("filter");
            }
            return null;
        }

        @Override
        public void finish(JsonObject merged) {
            // Claims from several instances interleave: keep them in time order and number them again ("run" is a
            // claim's place in the log, shown for big drops).
            JsonArray entries = sortBy(merged, "entries", "timestamp", false);
            if (entries != null) {
                for (int i = 0; i < entries.size(); i++) {
                    entries.get(i).getAsJsonObject().addProperty("run", i + 1);
                }
            }
        }
    });

    public static final Kind CROESUS_RUNS = new Kind("killer560smod-croesus-runs.json", null, "croesus-runs.json",
            Scope.ACCOUNT, new JsonMerge.Policy() {
        @Override
        public Function<JsonElement, String> arrayKey(String path) {
            if (path.equals("runs")) return JsonMerge.fields("id");
            if (path.equals("pages")) return JsonMerge.fields("page");
            return null;
        }
    });

    public static final Kind RNG_ITEM_LOG = new Kind("killer560smod-rng-item-log.json", null, "rng-item-log.json",
            Scope.GLOBAL, JsonMerge.DEFAULT);

    public static final Kind EXPERIMENTS = new Kind("killer560smod-experiments-profit.json", null,
            "experiments-profit.json", Scope.GLOBAL, new JsonMerge.Policy() {
        @Override
        public Function<JsonElement, String> arrayKey(String path) {
            if (path.equals("buckets")) return JsonMerge.fields("account", "accountId", "sbProfile", "modProfile");
            if (path.equals("sessionLog")) return JsonMerge.fields("endedAtMs", "game");
            return null;
        }

        @Override
        public JsonMerge.NumberMode number(String path) {
            for (String c : new String[]{"buckets.[].sessions", "buckets.[].sessionsPerGame.*", "buckets.[].xpGained",
                    "buckets.[].rewardValueCoins", "buckets.[].bitsSpent", "buckets.[].items.*.count",
                    "buckets.[].items.*.value"}) {
                if (JsonMerge.matches(path, c)) return JsonMerge.NumberMode.COUNTER;
            }
            return JsonMerge.matches(path, "totals.trackingSinceMs") ? JsonMerge.NumberMode.MIN : JsonMerge.NumberMode.STATE;
        }

        @Override
        public void finish(JsonObject merged) { sortBy(merged, "sessionLog", "endedAtMs", false); }
    });

    public static final Kind BAZAAR_FLIP = new Kind("killer560smod-bazaarflip-profit.json", null,
            "bazaarflip-profit.json", Scope.GLOBAL, new JsonMerge.Policy() {
        @Override
        public Function<JsonElement, String> arrayKey(String path) {
            return path.equals("cycles") ? JsonMerge.fields("atMs", "productId") : null;
        }

        @Override
        public void finish(JsonObject merged) { sortBy(merged, "cycles", "atMs", false); }
    });

    /** Today's NPC sales for the daily cap: one account's one day. Same day: both instances' sales count; else the
     *  later day wins. */
    public static final Kind NPC_SOLD = new Kind("killer560smod-bazaarflip-npcsold.json", null, "bazaarflip-npcsold.json",
            Scope.ACCOUNT, new JsonMerge.Policy() {
        @Override
        public JsonObject custom(JsonObject disk, JsonObject base, JsonObject mine) {
            if (mine == null) return null;
            String day = str(mine, "day") + "|" + str(mine, "account");
            if (disk == null || !day.equals(str(disk, "day") + "|" + str(disk, "account"))) {
                // Another day or account on disk: the later day wins whole (a new UTC day starts at 0 anyway).
                return disk != null && str(disk, "day").compareTo(str(mine, "day")) > 0 ? disk.deepCopy() : mine.deepCopy();
            }
            double sold = num(disk, "soldCoins") + num(mine, "soldCoins");
            if (base != null && day.equals(str(base, "day") + "|" + str(base, "account"))) {
                sold -= num(base, "soldCoins");
            }
            JsonObject out = mine.deepCopy();
            out.addProperty("soldCoins", Math.max(0, sold));
            return out;
        }
    });

    public static final Kind RUN_HISTORY = new Kind("killer560smod-runs", "history.json", "run-history.json",
            Scope.ACCOUNT, new JsonMerge.Policy() {
        @Override
        public Function<JsonElement, String> arrayKey(String path) {
            return path.equals("runs") ? JsonMerge.fields("startedAtMs", "floor") : null;
        }

        @Override
        public void finish(JsonObject merged) {
            sortBy(merged, "runs", "startedAtMs", true); // newest first, as RunHistoryStore keeps it
        }
    });

    public static final Kind BEST_FRIENDS = new Kind("killer560smod-social", "bestfriends.json", "bestfriends.json",
            Scope.ACCOUNT, new JsonMerge.Policy() {
        @Override
        public Function<JsonElement, String> arrayKey(String path) {
            return path.equals("players") ? JsonMerge.fields("uuid") : null;
        }

        @Override
        public JsonMerge.NumberMode number(String path) {
            if (JsonMerge.matches(path, "players.[].totalPartyMs") || JsonMerge.matches(path, "players.[].kuudraRuns")
                    || JsonMerge.matches(path, "players.[].dungeonRunsByFloor.*")) {
                return JsonMerge.NumberMode.COUNTER;
            }
            if (JsonMerge.matches(path, "players.[].firstPartiedAtMs")) return JsonMerge.NumberMode.MIN;
            if (JsonMerge.matches(path, "players.[].lastPartiedAtMs")) return JsonMerge.NumberMode.MAX;
            return JsonMerge.NumberMode.STATE;
        }

        @Override
        public void finish(JsonObject merged) {
            if (merged.has("players") && merged.get("players").isJsonArray()) {
                for (JsonElement e : merged.getAsJsonArray("players")) {
                    JsonObject o = e.getAsJsonObject();
                    if (o.has("totalPartyMs")) {
                        o.addProperty("totalPartySeconds", o.get("totalPartyMs").getAsLong() / 1000L);
                    }
                }
            }
        }
    });

    public static final Kind EGG_TRACKER = new Kind("killer560smod-eggtracker.json", null, "eggtracker.json",
            Scope.ACCOUNT, new JsonMerge.Policy() {
        @Override
        public Function<JsonElement, String> arrayKey(String path) {
            return path.equals("hunts") ? JsonMerge.fields("startedMs") : null;
        }

        @Override
        public JsonMerge.NumberMode number(String path) {
            return JsonMerge.matches(path, "hunts.[].startedMs") ? JsonMerge.NumberMode.STATE : JsonMerge.NumberMode.COUNTER;
        }

        @Override
        public void finish(JsonObject merged) { sortBy(merged, "hunts", "startedMs", false); }
    });

    /** How Lucky Are You (doc-rng-1101): per Skyblock profile, attempts per activity, expected drops (at the odds and
     *  Magic Find of each attempt) and drops got, all counters; plus a log of the drops themselves. Dungeon chests are
     *  not here: they are read from {@link #CROESUS_LOG}. The legacy name never existed (a new record). */
    public static final Kind LUCK = new Kind("killer560smod-luck-records.json", null, "luck.json",
            Scope.ACCOUNT, new JsonMerge.Policy() {
        @Override
        public Function<JsonElement, String> arrayKey(String path) {
            return path.equals("log") ? JsonMerge.fields("atMs", "drop") : null;
        }

        @Override
        public JsonMerge.NumberMode number(String path) {
            for (String c : new String[]{"profiles.*.attempts.*", "profiles.*.exp.*", "profiles.*.expMf.*",
                    "profiles.*.got.*"}) {
                if (JsonMerge.matches(path, c)) return JsonMerge.NumberMode.COUNTER;
            }
            return JsonMerge.NumberMode.STATE;
        }

        @Override
        public void finish(JsonObject merged) {
            JsonArray log = sortBy(merged, "log", "atMs", false);
            // The log is for the screen's "recent drops" list; the counters hold the totals. Keep the newest 500.
            if (log != null && log.size() > 500) {
                JsonArray kept = new JsonArray();
                for (int i = log.size() - 500; i < log.size(); i++) {
                    kept.add(log.get(i));
                }
                merged.add("log", kept);
            }
        }
    });

    /** Carry Tracker (featG-1109): runs per carried player (counters, by lower-case name) and a log of the runs themselves.
     *  The legacy name never existed (a new record). */
    public static final Kind CARRY = new Kind("killer560smod-carry-records", "records.json", "carry-tracker.json",
            Scope.ACCOUNT, new JsonMerge.Policy() {
        @Override
        public Function<JsonElement, String> arrayKey(String path) {
            return path.equals("log") ? JsonMerge.fields("endedAtMs", "player") : null;
        }

        @Override
        public JsonMerge.NumberMode number(String path) {
            return JsonMerge.matches(path, "counts.*") ? JsonMerge.NumberMode.COUNTER : JsonMerge.NumberMode.STATE;
        }

        @Override
        public void finish(JsonObject merged) {
            JsonArray log = sortBy(merged, "log", "endedAtMs", false);
            if (log != null && log.size() > 300) {
                JsonArray kept = new JsonArray();
                for (int i = log.size() - 300; i < log.size(); i++) {
                    kept.add(log.get(i));
                }
                merged.add("log", kept);
            }
        }
    });

    /** Starred mob looks (simblood-1110): what each starred mob on a Blood Rush spawn spot looked like on Hypixel (type, name,
     *  equipment, skin), one entry per room and room-relative block, for the Dungeon Sim's Real Mob Looks
     *  ({@code roomsim.SimMobLooks}). Shared by every account and instance. The legacy name never existed (a new record). */
    public static final Kind SIM_MOB_LOOKS = new Kind("killer560smod-sim-mob-looks.json", null, "sim-mob-looks.json",
            Scope.GLOBAL, new JsonMerge.Policy() {
        @Override
        public Function<JsonElement, String> arrayKey(String path) {
            return path.equals("mobs") ? JsonMerge.fields("room", "x", "y", "z") : null;
        }

        @Override
        public JsonMerge.NumberMode number(String path) {
            return JsonMerge.matches(path, "mobs.[].seen") ? JsonMerge.NumberMode.COUNTER : JsonMerge.NumberMode.STATE;
        }
    });

    /** Carnival Tracker (carnival-1110): games played and tokens earned are counters, best scores the larger of two, the
     *  goals and the tickets day the last one written. The legacy file only existed on the unshipped staging branch. */
    public static final Kind CARNIVAL = new Kind("killer560smod-carnival-history.json", null, "carnival-history.json",
            Scope.ACCOUNT, new JsonMerge.Policy() {
        @Override
        public JsonMerge.NumberMode number(String path) {
            if (JsonMerge.matches(path, "played.*") || path.equals("tokensEarned")) return JsonMerge.NumberMode.COUNTER;
            if (JsonMerge.matches(path, "best.*") || path.equals("goalsReadAtMs")) return JsonMerge.NumberMode.MAX;
            return JsonMerge.NumberMode.STATE;
        }
    });

    public static final List<Kind> ALL = List.of(CROESUS_LOG, CROESUS_RUNS, RNG_ITEM_LOG, EXPERIMENTS, BAZAAR_FLIP,
            NPC_SOLD, RUN_HISTORY, BEST_FRIENDS, EGG_TRACKER, LUCK, CARRY, SIM_MOB_LOOKS, CARNIVAL);

    /** Sorts the array {@code key} of {@code root} by a field (numbers, or ISO timestamps as strings); returns it. */
    static JsonArray sortBy(JsonObject root, String key, String field, boolean descending) {
        if (!root.has(key) || !root.get(key).isJsonArray()) return null;
        List<JsonElement> list = new ArrayList<>();
        root.getAsJsonArray(key).forEach(list::add);
        Comparator<JsonElement> cmp = Comparator.comparing(e -> sortKey(e, field));
        list.sort(descending ? cmp.reversed() : cmp);
        JsonArray out = new JsonArray();
        list.forEach(out::add);
        root.add(key, out);
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static double num(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsDouble() : 0;
        } catch (RuntimeException bad) {
            return 0;
        }
    }

    private static String sortKey(JsonElement e, String field) {
        try {
            JsonElement v = e.getAsJsonObject().get(field);
            return v.getAsJsonPrimitive().isNumber() ? String.format("%020d", v.getAsLong()) : v.getAsString();
        } catch (RuntimeException bad) {
            return "";
        }
    }
}
