package com.killer560.hub.sharesite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.killer560.hub.relay.RelayCalls;
import com.killer560.hub.runsummary.RunRecord;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Share To Website (sharesite-1119, killer560 2026-11-19): while ON (the default) each finished dungeon run is sent to the
 * relay ({@code POST /site/stats}) for the website's Player Stats page: the floor, its time, whether the boss was beaten,
 * the player's own secrets (the end-of-run Stats block), the run's score, and the player's own terminal solve times since the last upload. The relay keeps the totals;
 * nothing is tallied or stored here. Turning it OFF sends {@code DELETE /site/stats}, retried at each world join until the
 * relay confirms. Design: killer560s-mod-logs/sharesite-1119-design.md.
 * <p>
 * Inputs are the mod's existing measurements: {@code runsummary/RunSummaryFeature.finishRun} hands over its
 * {@link RunRecord} and {@code splittimers/TerminalTimersFeature} each solve it confirmed by the player's own "completed a
 * terminal" line. Every call runs on {@link RelayCalls}' pool (never the render thread, never per tick); who is
 * uploading comes from the relay's bearer token only. Service key {@value #SERVICE} points it at a fake.
 */
public final class ShareToWebsite {

    public static final String SERVICE = "sharesite-relay";
    private static final Logger LOGGER = ModLog.get("killer560smod-sharesite");
    /** The relay's own bounds (src/sitestats.ts): a value outside them would refuse the whole upload, so it is left out here. */
    static final Set<String> FLOORS = Set.of("E", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7");
    static final Set<String> TERMINALS = Set.of("Panes", "Rubix", "Numbers", "Starts With", "Select", "Melody");
    private static final long RUN_MIN_MS = 20_000L;
    private static final long RUN_MAX_MS = 2 * 3_600_000L;
    private static final int MAX_SECRETS = 150;
    private static final int TERM_MIN_MS = 200;
    private static final int TERM_MAX_MS = 60_000;
    private static final int MAX_TERMS = 40;
    /** Uploads waiting for the relay (one per run); the oldest goes first when more pile up. */
    private static final int MAX_PENDING = 10;

    private static final JsonArray TERMS = new JsonArray();
    private static final Deque<JsonObject> PENDING = new ArrayDeque<>();
    private static boolean inFlight;
    private static int uploadsSent;
    private static int deletesSent;
    private static int seq;

    private ShareToWebsite() {
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            flush();
            BoardTimes.resyncP3();
        });
    }

    /** On, and the player has seen the intro's notice (a player who finished the intro before this existed is shown it once). */
    public static boolean active() {
        ShareToWebsiteConfig cfg = ShareToWebsiteConfig.getInstance();
        return cfg.isEnabled() && cfg.isNoticeShown();
    }

    /** The notice scene was shown and answered (either way): sharing may now follow the switch. */
    public static void acknowledgeNotice() {
        ShareToWebsiteConfig cfg = ShareToWebsiteConfig.getInstance();
        if (!cfg.isNoticeShown()) {
            cfg.setNoticeShown(true);
            cfg.save();
        }
    }

    /** The switch, from the card, the tour or a profile apply. OFF drops what is waiting and asks the relay to delete. */
    public static void setEnabled(boolean on) {
        ShareToWebsiteConfig cfg = ShareToWebsiteConfig.getInstance();
        cfg.setEnabled(on);
        if (!on) {
            synchronized (ShareToWebsite.class) {
                PENDING.clear();
                while (!TERMS.isEmpty()) TERMS.remove(0);
            }
            cfg.setPendingDelete(true);
        }
        cfg.save();
        flush();
    }

    /** One of the player's own terminal solves (confirmed by his "completed a terminal" line); sent with the next run. */
    public static synchronized void onTerminalSolved(String type, double seconds) {
        long ms = Math.round(seconds * 1000.0);
        if (!active() || !BoardTimes.realRun() || !TERMINALS.contains(type) || ms < TERM_MIN_MS || ms > TERM_MAX_MS) return;
        if (TERMS.size() >= MAX_TERMS) TERMS.remove(0);
        JsonObject t = new JsonObject();
        t.addProperty("type", type);
        t.addProperty("ms", ms);
        TERMS.add(t);
    }

    /** A finished run from Run Summary's tracker and the player's own secret count from the end-of-run Stats block (-1
     *  unknown: the run is left out, the terminal solves still go): queued with the solves since the last one, then sent. */
    public static void onRun(RunRecord record, int ownSecrets) {
        if (record == null || !active() || !BoardTimes.realRun()) return;
        BoardTimes.onRun(record);
        String floor = record.floor();
        boolean usable = floor != null && FLOORS.contains(floor) && record.totalMs() >= RUN_MIN_MS
                && record.totalMs() <= RUN_MAX_MS && ownSecrets >= 0 && ownSecrets <= MAX_SECRETS;
        synchronized (ShareToWebsite.class) {
            JsonArray runs = new JsonArray();
            if (usable) {
                JsonObject run = new JsonObject();
                run.addProperty("floor", floor);
                run.addProperty("ms", record.totalMs());
                run.addProperty("done", record.hypixelTime() != null && !record.hypixelTime().isBlank());
                run.addProperty("secrets", ownSecrets);
                if (record.effectiveScore() >= 0) run.addProperty("score", record.effectiveScore());
                runs.add(run);
            }
            if (runs.isEmpty() && TERMS.isEmpty()) return;
            JsonObject body = new JsonObject();
            body.addProperty("v", 1);
            body.addProperty("batch", record.startedAtMs() + "-" + (++seq & 0xffff));
            body.add("runs", runs);
            body.add("terms", TERMS.deepCopy());
            while (!TERMS.isEmpty()) TERMS.remove(0);
            if (PENDING.size() >= MAX_PENDING) PENDING.removeFirst();
            PENDING.addLast(body);
        }
        flush();
    }

    /** Sends a waiting delete first, then the waiting uploads, one at a time; a failure waits for the next run or join. */
    public static void flush() {
        BoardTimes.flush();
        JsonObject next;
        boolean delete;
        synchronized (ShareToWebsite.class) {
            ShareToWebsiteConfig cfg = ShareToWebsiteConfig.getInstance();
            delete = cfg.isPendingDelete();
            next = delete ? null : PENDING.peekFirst();
            if (inFlight || (!delete && (next == null || !cfg.isEnabled()))) return;
            inFlight = true;
            if (delete) deletesSent++;
            else uploadsSent++;
        }
        if (delete) {
            RelayCalls.call(SERVICE, "DELETE", "/site/stats", null).thenAccept(r -> {
                boolean done = r.ok();
                synchronized (ShareToWebsite.class) {
                    inFlight = false;
                    if (done) {
                        ShareToWebsiteConfig cfg = ShareToWebsiteConfig.getInstance();
                        cfg.setPendingDelete(false);
                        cfg.save();
                    }
                }
                if (done) {
                    LOGGER.info("[ShareToWebsite] Removed this account's stats from the website");
                    flush();
                } else {
                    LOGGER.info("[ShareToWebsite] Delete not confirmed ({}); trying again at the next world join", r.error());
                }
            });
            return;
        }
        RelayCalls.call(SERVICE, "POST", "/site/stats", next).thenAccept(r -> {
            // 200 = stored, 4xx other than 401/429 = the relay will never take it: either way it is done with.
            boolean drop = r.ok() || (r.status() >= 400 && r.status() < 500 && r.status() != 401 && r.status() != 429);
            synchronized (ShareToWebsite.class) {
                inFlight = false;
                if (drop) PENDING.remove(next);
            }
            if (!r.ok()) LOGGER.info("[ShareToWebsite] Upload not stored ({}){}", r.error(), drop ? "" : "; kept for the next try");
            if (drop) flush();
        });
    }

    /** Test hooks: the waiting uploads as JSON, and counts of requests started. */
    public static synchronized String pendingForTest() {
        return PENDING.toString();
    }

    public static synchronized int termsForTest() {
        return TERMS.size();
    }

    public static void setNoticeForTest(boolean shown) {
        ShareToWebsiteConfig.getInstance().setNoticeShown(shown);
    }

    public static synchronized String termsJsonForTest() {
        return TERMS.toString();
    }

    public static synchronized int[] sentForTest() {
        return new int[]{uploadsSent, deletesSent};
    }

    /** Board uploads waiting (board ids) and how many were started (modlb5-1120). */
    public static java.util.List<String> boardsPendingForTest() {
        return BoardTimes.pendingForTest();
    }

    public static void boardsAnyHostForTest(boolean on) {
        BoardTimes.anyHostForTest(on);
    }

    public static void boardsResyncForTest() {
        BoardTimes.resyncP3();
    }

    public static String boardsPendingJsonForTest() {
        return BoardTimes.pendingJsonForTest();
    }

    public static int boardsSentForTest() {
        return BoardTimes.sentForTest();
    }

    public static synchronized void resetForTest() {
        BoardTimes.resetForTest();
        PENDING.clear();
        while (!TERMS.isEmpty()) TERMS.remove(0);
        inFlight = false;
        uploadsSent = 0;
        deletesSent = 0;
    }
}
