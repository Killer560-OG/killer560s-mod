import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.shared.JsonMerge;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone test of the shared record store's merge, legacy import and two-instance concurrency (shared-stats-1101).
 * The mod has no unit-test setup, and these classes need only Gson, so: tools/shared-records/run-test.sh compiles
 * src/main/java/com/killer560/hub/shared/{JsonMerge,SharedFile,RecordCatalog}.java with this file and runs it.
 * The concurrency case starts two real JVMs ("instances", each with its own config dir and old file) on one store.
 */
public final class SharedRecordsTest {

    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("worker")) {
            worker(Path.of(args[1]), args[2], Integer.parseInt(args[3]), Path.of(args[4]));
            return;
        }
        Path tmp = Files.createTempDirectory("shared-records-test");
        mergeRules();
        importDedupe(tmp.resolve("import"));
        concurrent(tmp.resolve("concurrent"));
        System.out.println("ALL PASSED: " + checks + " checks");
    }

    // ---------------------------------------------------------------------------------------------------------------

    static void mergeRules() {
        JsonMerge.Policy exp = RecordCatalog.EXPERIMENTS.policy();
        JsonObject base = json("{buckets:[{account:'A',accountId:'a',sbProfile:'p',modProfile:'m',sessions:5,xpGained:100,"
                + "rewardValueCoins:1.5,items:{'Mr. Item':{count:2,value:10.0}}}],sessionLog:[{endedAtMs:1,game:'S'}]}");
        JsonObject mine = json("{buckets:[{account:'A',accountId:'a',sbProfile:'p',modProfile:'m',sessions:6,xpGained:150,"
                + "rewardValueCoins:2.5,items:{'Mr. Item':{count:3,value:15.0}}}],sessionLog:[{endedAtMs:1,game:'S'},{endedAtMs:3,game:'S'}]}");
        JsonObject disk = json("{buckets:[{account:'A',accountId:'a',sbProfile:'p',modProfile:'m',sessions:7,xpGained:120,"
                + "rewardValueCoins:1.5,items:{'Mr. Item':{count:2,value:10.0}}}],sessionLog:[{endedAtMs:1,game:'S'},{endedAtMs:2,game:'C'}]}");
        JsonObject m = JsonMerge.merge(disk, base, mine, exp);
        JsonObject b = m.getAsJsonArray("buckets").get(0).getAsJsonObject();
        eq(8L, b.get("sessions").getAsLong(), "counter: disk 7 + my +1");
        eq(170L, b.get("xpGained").getAsLong(), "counter: disk 120 + my +50");
        eq(2.5, b.get("rewardValueCoins").getAsDouble(), "double counter");
        eq(3L, b.getAsJsonObject("items").getAsJsonObject("Mr. Item").get("count").getAsLong(), "item key with a dot");
        eq(3, m.getAsJsonArray("sessionLog").size(), "session log union, sorted");
        eq(3L, m.getAsJsonArray("sessionLog").get(2).getAsJsonObject().get("endedAtMs").getAsLong(), "sorted by time");

        // A reset here removes; a bucket another instance added survives.
        JsonObject disk2 = json("{buckets:[{account:'A',accountId:'a',sbProfile:'p',modProfile:'m',sessions:7},"
                + "{account:'B',accountId:'b',sbProfile:'p',modProfile:'m',sessions:4}]}");
        JsonObject m2 = JsonMerge.merge(disk2, base, json("{buckets:[]}"), exp);
        eq(1, m2.getAsJsonArray("buckets").size(), "reset removed A, kept B");
        eq("B", m2.getAsJsonArray("buckets").get(0).getAsJsonObject().get("account").getAsString(), "B kept");

        // Croesus log: the same claim twice is kept once, renumbered in time order.
        JsonMerge.Policy cl = RecordCatalog.CROESUS_LOG.policy();
        JsonObject c = JsonMerge.merge(
                json("{entries:[{timestamp:'2026-10-02T00:00:00Z',floor:'F7',chest:'Obsidian',profit:5,run:1}]}"), null,
                json("{entries:[{timestamp:'2026-10-01T00:00:00Z',floor:'F7',chest:'Bedrock',profit:9,run:1},"
                        + "{timestamp:'2026-10-02T00:00:00Z',floor:'F7',chest:'Obsidian',profit:5,run:2}]}"), cl);
        eq(2, c.getAsJsonArray("entries").size(), "croesus dedupe");
        eq("Bedrock", c.getAsJsonArray("entries").get(0).getAsJsonObject().get("chest").getAsString(), "time order");
        eq(2L, c.getAsJsonArray("entries").get(1).getAsJsonObject().get("run").getAsLong(), "renumbered");

        // Best friends: time adds, first-seen is the earliest, last-seen the latest.
        JsonMerge.Policy bf = RecordCatalog.BEST_FRIENDS.policy();
        JsonObject f = JsonMerge.merge(
                json("{players:[{uuid:'u',totalPartyMs:1000,firstPartiedAtMs:50,lastPartiedAtMs:60,dungeonRunsByFloor:{F7:2}}]}"),
                null,
                json("{players:[{uuid:'u',totalPartyMs:500,firstPartiedAtMs:40,lastPartiedAtMs:55,dungeonRunsByFloor:{F7:1,M7:1}}]}"),
                bf);
        JsonObject p = f.getAsJsonArray("players").get(0).getAsJsonObject();
        eq(1500L, p.get("totalPartyMs").getAsLong(), "party time sums");
        eq(1L, p.get("totalPartySeconds").getAsLong(), "seconds recomputed");
        eq(40L, p.get("firstPartiedAtMs").getAsLong(), "MIN");
        eq(60L, p.get("lastPartiedAtMs").getAsLong(), "MAX");
        eq(3L, p.getAsJsonObject("dungeonRunsByFloor").get("F7").getAsLong(), "floor runs sum");

        // NPC sales: same day adds this instance's change; a later day replaces.
        JsonMerge.Policy npc = RecordCatalog.NPC_SOLD.policy();
        JsonObject n = JsonMerge.merge(json("{day:'2026-11-01',account:'a',soldCoins:100.0}"),
                json("{day:'2026-11-01',account:'a',soldCoins:10.0}"), json("{day:'2026-11-01',account:'a',soldCoins:30.0}"), npc);
        eq(120.0, n.get("soldCoins").getAsDouble(), "npc same day");
        n = JsonMerge.merge(json("{day:'2026-11-01',account:'a',soldCoins:100.0}"),
                json("{day:'2026-11-01',account:'a',soldCoins:100.0}"), json("{day:'2026-11-02',account:'a',soldCoins:7.0}"), npc);
        eq(7.0, n.get("soldCoins").getAsDouble(), "npc new day");
    }

    static void importDedupe(Path dir) throws Exception {
        Files.createDirectories(dir);
        SharedFile f = new SharedFile(dir.resolve("experiments-profit.json"), RecordCatalog.EXPERIMENTS.policy());
        byte[] a = bytes("{buckets:[{account:'A',accountId:'a',sbProfile:'p',modProfile:'m',sessions:5}],"
                + "sessionLog:[{endedAtMs:1,game:'S'}]}");
        eq(true, f.importLegacy(a, "instA"), "first import");
        eq(false, f.importLegacy(a, "instB-copy"), "identical copy skipped");
        byte[] a2 = bytes("{buckets:[{account:'A',accountId:'a',sbProfile:'p',modProfile:'m',sessions:8}],"
                + "sessionLog:[{endedAtMs:1,game:'S'},{endedAtMs:2,game:'S'}]}");
        eq(true, f.importLegacy(a2, "instA"), "same source grew");
        byte[] c = bytes("{buckets:[{account:'A',accountId:'a',sbProfile:'p',modProfile:'m',sessions:2}],"
                + "sessionLog:[{endedAtMs:9,game:'C'}]}");
        eq(true, f.importLegacy(c, "instC"), "other instance");
        JsonObject out = f.load();
        eq(10L, out.getAsJsonArray("buckets").get(0).getAsJsonObject().get("sessions").getAsLong(),
                "8 (A, re-imported as a delta, not 5+8) + 2 (C), the copy not counted");
        eq(3, out.getAsJsonArray("sessionLog").size(), "log union");
    }

    // ---------------------------------------------------------------------------------------------------------------

    static final int N = 60;

    static void concurrent(Path dir) throws Exception {
        Path store = dir.resolve("store");
        Files.createDirectories(store);
        List<Process> procs = new ArrayList<>();
        String java = ProcessHandle.current().info().command().orElse("java");
        String cp = System.getProperty("java.class.path");
        for (String id : new String[]{"one", "two"}) {
            Path config = dir.resolve("instance-" + id).resolve("config");
            Files.createDirectories(config);
            // Each "instance" has its own old file: one claim of its own, and one both share (a copied folder).
            Files.writeString(config.resolve("killer560smod-croesus-log.json"),
                    "{\"version\":2,\"entries\":[{\"timestamp\":\"2026-01-01T00:00:00Z\",\"floor\":\"F7\",\"chest\":\"Shared\",\"profit\":1},"
                            + "{\"timestamp\":\"2026-01-02T00:00:00Z\",\"floor\":\"F7\",\"chest\":\"Old-" + id + "\",\"profit\":1}]}",
                    StandardCharsets.UTF_8);
            procs.add(new ProcessBuilder(java, "-cp", cp, "SharedRecordsTest", "worker", store.toString(), id,
                    String.valueOf(N), config.toString()).inheritIO().start());
        }
        for (Process p : procs) {
            eq(0, p.waitFor(), "worker exit");
        }
        // The two must really have run at the same time, or this proves nothing about locking and merging.
        long[] one = times(store.resolve("times-one.txt"));
        long[] two = times(store.resolve("times-two.txt"));
        eq(true, one[0] < two[1] && two[0] < one[1], "the two instances overlapped in time (" + (Math.min(one[1], two[1])
                - Math.max(one[0], two[0])) + " ms)");
        System.out.println("instances overlapped for " + (Math.min(one[1], two[1]) - Math.max(one[0], two[0])) + " ms");
        JsonObject log = JsonParser.parseString(Files.readString(store.resolve("croesus-log.json"))).getAsJsonObject();
        JsonArray entries = log.getAsJsonArray("entries");
        // 2*N new claims + "Shared" once + Old-one + Old-two.
        eq(2 * N + 3, entries.size(), "every claim from both instances, the shared old one once");
        for (int i = 0; i < entries.size(); i++) {
            eq((long) i + 1, entries.get(i).getAsJsonObject().get("run").getAsLong(), "run numbers contiguous");
        }
        JsonObject exp = JsonParser.parseString(Files.readString(store.resolve("experiments-profit.json"))).getAsJsonObject();
        eq((long) 2 * N, exp.getAsJsonArray("buckets").get(0).getAsJsonObject().get("sessions").getAsLong(),
                "counter: every session of both instances");
    }

    static long[] times(Path p) throws Exception {
        String[] t = Files.readString(p).trim().split(" ");
        return new long[]{Long.parseLong(t[0]), Long.parseLong(t[1])};
    }

    /** One "instance": folds its old file in, then records N claims and N experiment sessions, saving after each. */
    static void worker(Path store, String id, int n, Path config) throws Exception {
        long start = System.currentTimeMillis();
        SharedFile log = new SharedFile(store.resolve("croesus-log.json"), RecordCatalog.CROESUS_LOG.policy());
        Path legacy = config.resolve("killer560smod-croesus-log.json");
        log.importLegacy(Files.readAllBytes(legacy), legacy.toString());
        JsonObject mine = log.load();
        SharedFile exp = new SharedFile(store.resolve("experiments-profit.json"), RecordCatalog.EXPERIMENTS.policy());
        JsonObject e = exp.load();
        for (int i = 0; i < n; i++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("timestamp", String.format("2026-11-01T00:00:%02d.%03d%sZ", i % 60, i, id.equals("one") ? "1" : "2"));
            entry.addProperty("floor", "F7");
            entry.addProperty("chest", "Obsidian-" + id);
            entry.addProperty("profit", i);
            if (!mine.has("entries")) {
                mine.add("entries", new JsonArray());
            }
            mine.getAsJsonArray("entries").add(entry);
            if (System.getenv("SHARED_RECORDS_NEGATIVE_CONTROL") != null) {
                // The old per-instance behavior (whole-file overwrite): the run must then FAIL, or the test is vacuous.
                Files.writeString(log.path(), mine.toString());
            } else {
                log.save(mine);
            }

            if (!e.has("buckets")) {
                e.add("buckets", JsonParser.parseString("[{account:'A',accountId:'a',sbProfile:'p',modProfile:'m',sessions:0}]"));
            }
            JsonObject b = e.getAsJsonArray("buckets").get(0).getAsJsonObject();
            b.addProperty("sessions", b.get("sessions").getAsLong() + 1);
            exp.save(e);
            Thread.sleep(1); // interleave with the other process
        }
        Files.writeString(store.resolve("times-" + id + ".txt"), start + " " + System.currentTimeMillis());
    }

    // ---------------------------------------------------------------------------------------------------------------

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static byte[] bytes(String lenient) {
        return JsonParser.parseString(lenient).toString().getBytes(StandardCharsets.UTF_8);
    }

    static void eq(Object expected, Object actual, String what) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but got " + actual);
        }
    }
}
