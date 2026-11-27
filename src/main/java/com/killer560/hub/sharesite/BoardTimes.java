package com.killer560.hub.sharesite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.killer560.hub.autoupdate.BuildInfo;
import com.killer560.hub.relay.RelayCalls;
import com.killer560.hub.runsummary.RunHistoryStore;
import com.killer560.hub.runsummary.RunRecord;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The website's hand-verified boards (modlb5-1120, every-run since 1126, killer560: "show each individual ... 5 people will
 * always share a time"): Fastest P3 ({@code p3} on F7, {@code m7p3} on M7; P3 is the Terminals split ONLY, killer560
 * 2026-11-26: "For fastest p3 that is only terminal phase"), F7 ({@code f7}), M7 ({@code m7}), F7 Solo Clear
 * ({@code f7solo}) and M7 Solo Clear ({@code m7solo}). EVERY finished real run (boss beaten) sends one entry per board it
 * counts for, not only a personal best, so the site can list each run and merge a team's copies: one request per entry
 * through {@code POST /site/leaderboards/mod-entry}, same switch and notice gate as Share To Website. Each entry carries
 * the real time ({@code ms}), the tick time ({@code ticks}: real time minus lag, absent when no per-tick server clock was
 * trusted), the floor, the run's start and end, the party's uuids and size at start and end and whether it changed (solo
 * means one player from start to end), and a team key {@code runId} (floor + hash of the sorted party uuids: every party
 * member's client computes the same one; the relay merges entries with the same runId whose endedAt are close). A failed
 * upload (network, 401, 429, 5xx) waits for the next run or world join; the relay drops a repeat by runId + startedAt.
 * Never from the sim, never off Hypixel ({@link #realRun}).
 */
final class BoardTimes {

    private static final Logger LOGGER = ModLog.get("killer560smod-sharesite");
    private static final String PATH = "/site/leaderboards/mod-entry";
    private static final long MIN_MS = 15_000L;
    private static final long MAX_MS = 2 * 3_600_000L;
    private static final int MAX_PENDING = 24;
    /** The one-time P3 re-upload mark (the old P3 meant Terminals + Goldor): a BOARD_BESTS key, which is a record, not a setting. */
    private static final String P3_RESYNC_MARK = "p3-terminals-resync";
    private static final Deque<JsonObject> PENDING = new ArrayDeque<>();
    /** Test hook: counts a local server as Hypixel SkyBlock (the sim checks stay in force). */
    private static volatile boolean anyHostForTest;
    private static boolean inFlight;
    private static int sent;

    private BoardTimes() {
    }

    /** A time on a board: real ms and tick ms (-1 when unknown). */
    record Time(long ms, long ticks) {
    }

    /** True only for a real run on Hypixel SkyBlock: not the dungeon sim, not a testkit or offline server. The one guard for
     *  everything Share To Website sends. */
    static boolean realRun() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return (anyHostForTest || (server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net")
                && SkyblockGate.isOnSkyblock()))
                && !DungeonState.isRoomSim() && !DungeonState.isSimOverrideActive() && !DungeonState.isSimBoss();
    }

    /** The boards this run counts for and its time on each. Empty unless the boss was beaten on F7 or M7. */
    static Map<String, Time> times(RunRecord r) {
        Map<String, Time> out = new LinkedHashMap<>();
        String floor = r.floor();
        boolean f7 = "F7".equals(floor);
        if ((!f7 && !"M7".equals(floor)) || r.hypixelTime() == null || r.hypixelTime().isBlank()
                || r.totalMs() < MIN_MS || r.totalMs() > MAX_MS) {
            return out;
        }
        String base = f7 ? "f7" : "m7";
        Time total = new Time(r.totalMs(), r.share().totalTickMs());
        out.put(base, total);
        if (r.partySize() == 1 && r.share().soloThroughout()) out.put(base + "solo", total);
        long terms = 0L;
        long termTicks = 0L;
        for (RunRecord.Split s : r.splits()) {
            if (!"Terminals".equals(s.name())) continue;
            terms += s.ms();
            termTicks = termTicks < 0L || s.tickMs() < 0L ? -1L : termTicks + s.tickMs();
        }
        if (terms > 0L) out.put(f7 ? "p3" : "m7p3", new Time(terms, termTicks));
        return out;
    }

    /** The entry the relay gets for one board of one run. */
    static JsonObject body(String cat, Time t, RunRecord r) {
        JsonObject b = new JsonObject();
        b.addProperty("v", 1);
        b.addProperty("cat", cat);
        b.addProperty("ms", t.ms());
        if (t.ticks() >= 0L) b.addProperty("ticks", t.ticks());
        b.addProperty("floor", r.floor());
        b.addProperty("team", Math.max(1, r.partySize()));
        b.addProperty("startedAt", r.startedAtMs());
        b.addProperty("endedAt", r.endedAtMs());
        b.addProperty("runId", runId(r));
        if (r.share().partyStart() >= 0) b.addProperty("partyStart", r.share().partyStart());
        if (r.share().partyEnd() >= 0) b.addProperty("partyEnd", r.share().partyEnd());
        b.addProperty("partyChanged", r.share().partyChanged());
        JsonArray party = new JsonArray();
        uuids(r).forEach(party::add);
        b.add("party", party);
        b.addProperty("version", FabricLoader.getInstance().getModContainer("killer560smod")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
        b.addProperty("commit", BuildInfo.shortCommit());
        b.addProperty("variant", BuildInfo.variant());
        return b;
    }

    private static TreeSet<String> uuids(RunRecord r) {
        TreeSet<String> out = new TreeSet<>();
        for (RunRecord.PartyMember m : r.party()) if (m.uuid() != null) out.add(m.uuid());
        return out;
    }

    /** The team's key, the same on every party member's client: floor + the first 12 hex of SHA-1 over the sorted party uuids
     *  (only the ones the tab list gave a real uuid for); with none known, the floor and this client's own start time. */
    static String runId(RunRecord r) {
        TreeSet<String> ids = uuids(r);
        if (ids.isEmpty()) return r.floor() + ":" + r.startedAtMs();
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(String.join(",", ids).getBytes(StandardCharsets.UTF_8));
            return r.floor() + ":" + HexFormat.of().formatHex(h, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            return r.floor() + ":" + r.startedAtMs();
        }
    }

    /** A finished real run (the caller checked {@link #realRun}): queues one upload per board it counts for, then sends. */
    static void onRun(RunRecord r) {
        Map<String, Time> times = times(r);
        if (times.isEmpty()) return;
        synchronized (BoardTimes.class) {
            for (Map.Entry<String, Time> t : times.entrySet()) queue(body(t.getKey(), t.getValue(), r));
        }
        flush();
    }

    private static synchronized void queue(JsonObject b) {
        for (JsonObject w : PENDING) {
            if (w.get("cat").equals(b.get("cat")) && w.get("startedAt").equals(b.get("startedAt"))) return;
        }
        if (PENDING.size() >= MAX_PENDING) PENDING.removeFirst();
        PENDING.addLast(b);
    }

    /** Once per account (the mark is in the shared board-bests record): the P3 board used to be Terminals + Goldor, so the
     *  site holds a time that is no longer P3. Re-uploads the best Terminals split in Run History per floor so the relay can
     *  replace it. Called at a world join on Hypixel; the history read runs off the main thread. */
    static void resyncP3() {
        if (!ShareToWebsite.active() || !realRun()) return;
        SharedWriter.submit(() -> {
            if (SharedRecords.load(SharedRecords.file(RecordCatalog.BOARD_BESTS)).has(P3_RESYNC_MARK)) return;
            Map<String, JsonObject> best = new LinkedHashMap<>();
            for (RunRecord r : RunHistoryStore.runs()) {
                for (String cat : List.of("p3", "m7p3")) {
                    Time t = times(r).get(cat);
                    JsonObject b = t == null ? null : body(cat, t, r);
                    if (b != null && (!best.containsKey(cat) || rank(b) < rank(best.get(cat)))) best.put(cat, b);
                }
            }
            synchronized (BoardTimes.class) {
                best.values().forEach(BoardTimes::queue);
            }
            JsonObject mark = new JsonObject();
            JsonObject entry = new JsonObject();
            entry.addProperty("ms", 1L);
            entry.addProperty("at", System.currentTimeMillis());
            mark.add(P3_RESYNC_MARK, entry);
            SharedRecords.save(SharedRecords.file(RecordCatalog.BOARD_BESTS), mark);
            flush();
        });
    }

    private static long rank(JsonObject b) {
        return b.has("ticks") ? b.get("ticks").getAsLong() : b.get("ms").getAsLong();
    }

    /** Sends the waiting uploads one at a time; sharing off drops them. */
    static void flush() {
        JsonObject next;
        synchronized (BoardTimes.class) {
            if (!ShareToWebsite.active()) {
                PENDING.clear();
                return;
            }
            if (inFlight || PENDING.isEmpty()) return;
            next = PENDING.peekFirst();
            inFlight = true;
            sent++;
        }
        RelayCalls.call(ShareToWebsite.SERVICE, "POST", PATH, next).thenAccept(r -> {
            // 200 = the relay has it (or has a better one); another 4xx but 401/429 = it never will: drop it.
            boolean drop = r.ok() || (r.status() >= 400 && r.status() < 500 && r.status() != 401 && r.status() != 429);
            synchronized (BoardTimes.class) {
                inFlight = false;
                if (drop) PENDING.remove(next);
            }
            if (!r.ok()) LOGGER.info("[ShareToWebsite] Board time not stored ({}){}", r.error(), drop ? "" : "; kept for the next try");
            if (drop) flush();
        });
    }

    static void anyHostForTest(boolean on) {
        anyHostForTest = on;
    }

    static synchronized List<String> pendingForTest() {
        List<String> out = new ArrayList<>();
        PENDING.forEach(b -> out.add(b.get("cat").getAsString()));
        return out;
    }

    static synchronized String pendingJsonForTest() {
        return PENDING.toString();
    }

    static synchronized int sentForTest() {
        return sent;
    }

    static synchronized void resetForTest() {
        PENDING.clear();
        inFlight = false;
        sent = 0;
    }
}
