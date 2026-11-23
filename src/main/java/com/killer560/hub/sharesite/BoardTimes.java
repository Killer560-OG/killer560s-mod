package com.killer560.hub.sharesite;

import com.google.gson.JsonObject;
import com.killer560.hub.autoupdate.BuildInfo;
import com.killer560.hub.relay.RelayCalls;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The website's five hand-verified boards (modlb5-1120, killer560 2026-11-20: "the mod should auto track those things"):
 * Fastest P3 ({@code p3}), F7 ({@code f7}), M7 ({@code m7}), F7 Solo Clear ({@code f7solo}), M7 Solo Clear ({@code m7solo}).
 * Each finished real run is measured from what the server said, using Run Summary's record: the run's time is its start line
 * to the end-of-run line of a beaten boss ({@code totalMs}, only when the "Defeated" line was seen), P3 is Split Timers'
 * Terminals plus Goldor segments (Goldor's "Who dares trespass" to Necron's first line; F7 only), solo is a party of one for
 * the whole run (Run Summary keeps the largest party it saw). A time that beats the player's stored best for that board (a
 * shared ACCOUNT record, {@link RecordCatalog#BOARD_BESTS}) is uploaded to {@code POST /site/leaderboards/mod-entry} through
 * the Share To Website path, with the same switch and notice gate. The stored best moves only when the relay answered 200,
 * so a failed upload is tried again with the next run or world join. Never from the sim, never off Hypixel.
 */
final class BoardTimes {

    private static final Logger LOGGER = ModLog.get("killer560smod-sharesite");
    private static final String PATH = "/site/leaderboards/mod-entry";
    private static final long MIN_MS = 15_000L;
    private static final long MAX_MS = 2 * 3_600_000L;
    /** One waiting upload per board: a newer, faster run replaces an older one that has not been sent yet. */
    private static final Map<String, JsonObject> PENDING = new LinkedHashMap<>();
    /** Test hook: counts a local server as Hypixel SkyBlock (the sim and Share-off checks stay in force). */
    private static volatile boolean anyHostForTest;
    private static boolean inFlight;
    private static int sent;

    private BoardTimes() {
    }

    /** True only for a real run on Hypixel SkyBlock: not the dungeon sim, not a testkit or offline server. */
    private static boolean realRun() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return (anyHostForTest || (server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net")
                && SkyblockGate.isOnSkyblock()))
                && !DungeonState.isRoomSim() && !DungeonState.isSimOverrideActive() && !DungeonState.isSimBoss();
    }

    /** The boards this run counts for and its time on each: (board, ms). Empty unless the boss was beaten. */
    static Map<String, Long> times(RunRecord r) {
        Map<String, Long> out = new LinkedHashMap<>();
        String floor = r.floor();
        boolean f7 = "F7".equals(floor);
        if ((!f7 && !"M7".equals(floor)) || r.hypixelTime() == null || r.hypixelTime().isBlank()
                || r.totalMs() < MIN_MS || r.totalMs() > MAX_MS) {
            return out;
        }
        String base = f7 ? "f7" : "m7";
        out.put(base, r.totalMs());
        if (r.partySize() == 1) out.put(base + "solo", r.totalMs());
        if (f7) {
            long terms = 0L;
            long goldor = 0L;
            for (RunRecord.Split s : r.splits()) {
                if ("Terminals".equals(s.name())) terms += s.ms();
                else if ("Goldor".equals(s.name())) goldor += s.ms();
            }
            if (terms > 0L && goldor > 0L && terms + goldor >= RecordCatalog.P3_MIN_MS) out.put("p3", terms + goldor);
        }
        return out;
    }

    /** A finished run: queues an upload for each board it beats the stored best on, then sends. */
    static void onRun(RunRecord r) {
        if (!realRun()) return;
        Map<String, Long> times = times(r);
        if (times.isEmpty()) return;
        JsonObject bests = RecordCatalog.withoutImpossibleBoards(SharedRecords.load(SharedRecords.file(RecordCatalog.BOARD_BESTS)));
        synchronized (BoardTimes.class) {
            for (Map.Entry<String, Long> t : times.entrySet()) {
                JsonObject best = bests.has(t.getKey()) ? bests.getAsJsonObject(t.getKey()) : null;
                JsonObject waiting = PENDING.get(t.getKey());
                if ((best != null && best.get("ms").getAsLong() <= t.getValue())
                        || (waiting != null && waiting.get("ms").getAsLong() <= t.getValue())) {
                    continue;
                }
                JsonObject b = new JsonObject();
                b.addProperty("v", 1);
                b.addProperty("cat", t.getKey());
                b.addProperty("ms", t.getValue());
                b.addProperty("floor", r.floor());
                b.addProperty("team", Math.max(1, r.partySize()));
                b.addProperty("endedAt", r.endedAtMs());
                b.addProperty("version", FabricLoader.getInstance().getModContainer("killer560smod")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
                b.addProperty("commit", BuildInfo.shortCommit());
                b.addProperty("variant", BuildInfo.variant());
                PENDING.put(t.getKey(), b);
            }
        }
        flush();
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
            next = PENDING.values().iterator().next();
            inFlight = true;
            sent++;
        }
        RelayCalls.call(ShareToWebsite.SERVICE, "POST", PATH, next).thenAccept(r -> {
            boolean stored = r.ok();
            // 200 = the relay has it (or has a better one); another 4xx but 401/429 = it never will: drop it, keep the best.
            boolean drop = stored || (r.status() >= 400 && r.status() < 500 && r.status() != 401 && r.status() != 429);
            synchronized (BoardTimes.class) {
                inFlight = false;
                if (drop) PENDING.remove(next.get("cat").getAsString(), next);
            }
            if (stored) remember(next);
            else LOGGER.info("[ShareToWebsite] Board time not stored ({}){}", r.error(), drop ? "" : "; kept for the next try");
            if (drop) flush();
        });
    }

    private static void remember(JsonObject sentBody) {
        String cat = sentBody.get("cat").getAsString();
        JsonObject mine = new JsonObject();
        JsonObject entry = new JsonObject();
        entry.addProperty("ms", sentBody.get("ms").getAsLong());
        entry.addProperty("at", sentBody.get("endedAt").getAsLong());
        mine.add(cat, entry);
        SharedWriter.submit(() -> SharedRecords.save(SharedRecords.file(RecordCatalog.BOARD_BESTS), mine));
    }

    static void anyHostForTest(boolean on) {
        anyHostForTest = on;
    }

    static synchronized List<String> pendingForTest() {
        return new ArrayList<>(PENDING.keySet());
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
