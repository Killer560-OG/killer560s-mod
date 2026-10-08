package com.killer560.hub.social;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.players.PlayerNames;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Party Time Tracker - killer560's settled 8.6 answer: counts ANY time in the same party (dungeon or not)
 * and keeps it forever, plus dungeon runs together by floor (Master Mode included as its own floor key) and
 * Kuudra runs (work-in-progress stub - see the note on {@link BestFriendsStore.Record#kuudraRuns}).
 * <p>
 * <b>Party membership</b> reuses {@code leapmenu.PartyTracker} rather than re-parsing party chat/tab-list -
 * that class is already the mod's one source of truth for "who is in my party right now" (Leap Menu, Run
 * Stats and Run Summary all read through it the same way). <b>UUIDs</b> come from the shared
 * {@code players.PlayerNames} resolver (persisted cache + tab list +, if neither already knows, a background
 * Mojang lookup) - never from the IGN itself; a teammate not yet resolved by the time a poll runs simply
 * does not accrue until a poll finds it rather than ever being keyed by name.
 * <p>
 * <b>Gating.</b> Tracking only runs while {@link BestFriendsConfig#isEnabled()} is on - the same "off by
 * default, nothing happens until killer560 turns it on" rule every other feature in this mod follows (e.g.
 * {@code runsummary.RunSummaryFeature} does nothing at all while its own config is disabled). This is a
 * judgement call worth him confirming: he might instead want the clock always running in the background
 * (even with the New-tab menu itself off) so no history is lost before he gets around to turning the tab on -
 * see "Needs his answer" in the staging notes.
 * <p>
 * <b>Crash safety.</b> Each currently-partied player's elapsed time is flushed into {@link BestFriendsStore}
 * (and the store's dirty flag written to disk) every {@link #FLUSH_INTERVAL_NS} (30 s), and
 * unconditionally on disconnect - so a crash loses at most that ~30s window, comfortably inside the brief's
 * "at most a minute" bound.
 * <p>
 * <b>Dungeon run crediting.</b> A run is "finished" the instant {@link DungeonState#isInDungeon()} falls from
 * true to false (mirrors how {@code runsummary.RunSummaryFeature} detects a finished run, but independent of
 * that feature's own on/off switch - Best Friends must keep counting runs even if Run Summary is off). The
 * floor credited is the last non-null {@link DungeonState#getFloor()} seen while still in the dungeon, and
 * every teammate this tracker was actively accruing time for at that instant gets +1 for that floor key.
 * <p>
 * <b>Clock.</b> Time is the {@link System#nanoTime()} elapsed between the poll that saw a teammate join and the
 * poll that saw them leave, added to {@link BestFriendsStore.Record#totalPartyMs} in whole milliseconds with the
 * sub-millisecond rest carried in the segment's start, so a checkpoint loses nothing. Until 2026-10-08 each 30 s
 * checkpoint added whole SECONDS and restarted the segment at "now", dropping the fraction every time (about half a
 * second a checkpoint), and the menu showed only the checkpointed total, so the clock sat still for up to 30 s at a
 * time ("it doesn't count up every second"). The menu now reads {@link #liveTotalMs}.
 */
public final class BestFriendsTracker {

    private static final Logger LOGGER = ModLog.get("killer560smod-bestfriends");

    /** A join or leave is seen within this many ticks (it was 20 until 2026-10-08, so each end of a segment could
     *  be up to a second off). The poll is a few map lookups for at most four names. */
    private static final int POLL_INTERVAL_TICKS = 5;
    private static final long FLUSH_INTERVAL_NS = 30_000_000_000L; // checkpoint to disk every 30 s

    /** Currently-partied, resolved teammates: uuid -> accrual segment start ({@link System#nanoTime()}). */
    private static final Map<UUID, Long> SESSION_START = new HashMap<>();
    private static final Map<UUID, String> SESSION_NAMES = new HashMap<>();

    private static int pollCounter = 0;
    private static long lastCheckpointNs = System.nanoTime();
    private static boolean wasInDungeon = false;
    private static String lastFloorWhileInDungeon = null;
    /** Set from the netty thread by DISCONNECT, drained on the client thread. */
    private static volatile boolean pendingDisconnect = false;
    private static volatile long pendingDisconnectNs = 0L;

    private BestFriendsTracker() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BestFriendsTracker.tick", BestFriendsTracker::tick));
        // DISCONNECT runs on the netty thread, beside the client thread's tick that owns these maps (CLAUDE.md), and a
        // client.execute from it can be dropped. Note the moment; the next tick (or CLIENT_STOPPING) flushes to it.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            pendingDisconnectNs = System.nanoTime();
            pendingDisconnect = true;
        });
        // Closing the game: the daemon writer may never get to run, so flush and write here, on this thread.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            try {
                if (pendingDisconnect) {
                    onDisconnect();
                } else if (!SESSION_START.isEmpty()) {
                    flushAll(System.nanoTime(), System.currentTimeMillis());
                }
                BestFriendsStore.saveNow();
            } catch (RuntimeException e) {
                LOGGER.warn("[BestFriends] Could not save on exit", e);
            }
        });
    }

    /** Stored time plus the running segment, if this player is partied right now - what the menu shows. */
    public static long liveTotalMs(BestFriendsStore.Record record) {
        if (record == null) {
            return 0L;
        }
        Long start = SESSION_START.get(record.uuid);
        long running = start == null ? 0L : Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
        return record.totalPartyMs + running;
    }

    /** True while time is accruing for this player. */
    public static boolean isAccruing(UUID id) {
        return id != null && SESSION_START.containsKey(id);
    }

    private static void tick(Minecraft client) {
        if (pendingDisconnect) {
            onDisconnect();
        }
        if (++pollCounter < POLL_INTERVAL_TICKS) {
            return;
        }
        pollCounter = 0;

        if (client.player == null || !BestFriendsConfig.getInstance().isEnabled()) {
            if (!SESSION_START.isEmpty()) {
                flushAll(System.nanoTime(), System.currentTimeMillis());
                SESSION_START.clear();
                SESSION_NAMES.clear();
            }
            wasInDungeon = false;
            lastFloorWhileInDungeon = null;
            return;
        }

        long nowNs = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        pollParty(nowNs, nowMs);
        if (nowNs - lastCheckpointNs >= FLUSH_INTERVAL_NS) {
            lastCheckpointNs = nowNs;
            flushAll(nowNs, nowMs);
            BestFriendsStore.saveIfDirty();
        }
        pollDungeonCompletion();
    }

    /** Starts accrual for newly-resolved party members, and flushes+stops it for anyone no longer partied
     *  (left the party, or never got resolved - either way, the time up to the last poll is kept).
     *  {@code PlayerNames.resolveAsync} answers immediately from its cache/tab-list scan (synchronously, on
     *  this thread) for anyone already known, which is the common case for an actual partymate; anyone not
     *  yet known kicks off a throttled background Mojang lookup and starts accruing automatically once a
     *  later poll sees them in the now-warm cache - see that class's own doc for the exact contract. */
    private static void pollParty(long nowNs, long nowMs) {
        Set<UUID> current = new LinkedHashSet<>();
        for (String name : PartyTracker.teammates()) {
            // Cache and tab list only, answered now. An unknown name starts a background lookup whose answer is
            // not used here: it would arrive ticks later carrying this poll's timestamp. The next poll finds the
            // name in the warm cache instead.
            UUID id = PlayerNames.uuidFor(name);
            if (id == null) {
                PlayerNames.resolveAsync(name, ignored -> {
                });
                continue;
            }
            current.add(id);
            SESSION_NAMES.put(id, name);
            SESSION_START.putIfAbsent(id, nowNs);
        }
        SESSION_START.keySet().removeIf(id -> {
            if (current.contains(id)) {
                return false;
            }
            flushOne(id, nowNs, nowMs);
            SESSION_NAMES.remove(id);
            return true;
        });
    }

    /** Adds the segment's whole milliseconds to the record and moves the segment's start forward by exactly that
     *  much, so the sub-millisecond rest stays in the segment instead of being dropped. */
    private static void flushOne(UUID id, long nowNs, long nowMs) {
        Long start = SESSION_START.get(id);
        if (start == null) {
            return;
        }
        long elapsedMs = Math.max(0L, (nowNs - start) / 1_000_000L);
        if (elapsedMs <= 0L) {
            return;
        }
        BestFriendsStore.Record record = BestFriendsStore.getOrCreate(id, SESSION_NAMES.get(id));
        record.totalPartyMs += elapsedMs;
        record.lastPartiedAtMs = nowMs;
        SESSION_START.put(id, start + elapsedMs * 1_000_000L);
    }

    /** The periodic checkpoint: every running segment is added and keeps running. */
    private static void flushAll(long nowNs, long nowMs) {
        for (UUID id : new ArrayList<>(SESSION_START.keySet())) {
            flushOne(id, nowNs, nowMs);
        }
    }

    /** Credits every currently-tracked teammate with a finished run on the floor last seen while still in
     *  the dungeon - see the class doc for why this doesn't depend on Run Summary being enabled. */
    private static void pollDungeonCompletion() {
        boolean inDungeonNow = DungeonState.isInDungeon();
        if (inDungeonNow) {
            String floor = DungeonState.getFloor();
            if (floor != null && !floor.isBlank()) {
                lastFloorWhileInDungeon = floor;
            }
        } else if (wasInDungeon && lastFloorWhileInDungeon != null) {
            String floor = lastFloorWhileInDungeon;
            for (Map.Entry<UUID, String> entry : SESSION_NAMES.entrySet()) {
                BestFriendsStore.Record record = BestFriendsStore.getOrCreate(entry.getKey(), entry.getValue());
                record.dungeonRunsByFloor.merge(floor, 1, Integer::sum);
            }
            LOGGER.info("[BestFriends] Credited a {} run together with {} teammate(s)", floor, SESSION_NAMES.size());
            BestFriendsStore.saveAsync();
            lastFloorWhileInDungeon = null;
        }
        wasInDungeon = inDungeonNow;
    }

    /** killer560's own crash-safety requirement: a disconnect must flush immediately, not wait for the next
     *  periodic checkpoint. Stops all accrual outright (rejoining starts fresh segments). */
    private static void onDisconnect() {
        pendingDisconnect = false;
        if (SESSION_START.isEmpty()) {
            return;
        }
        long at = pendingDisconnectNs != 0L ? Math.min(pendingDisconnectNs, System.nanoTime()) : System.nanoTime();
        flushAll(at, System.currentTimeMillis());
        SESSION_START.clear();
        SESSION_NAMES.clear();
        wasInDungeon = false;
        lastFloorWhileInDungeon = null;
        BestFriendsStore.saveAsync();
    }
}
