package com.killer560.hub.social;

import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.players.PlayerNames;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * doesn't accrue for that ~1s window rather than ever being keyed by name.
 * <p>
 * <b>Gating.</b> Tracking only runs while {@link BestFriendsConfig#isEnabled()} is on - the same "off by
 * default, nothing happens until killer560 turns it on" rule every other feature in this mod follows (e.g.
 * {@code runsummary.RunSummaryFeature} does nothing at all while its own config is disabled). This is a
 * judgement call worth him confirming: he might instead want the clock always running in the background
 * (even with the New-tab menu itself off) so no history is lost before he gets around to turning the tab on -
 * see "Needs his answer" in the staging notes.
 * <p>
 * <b>Crash safety.</b> Each currently-partied player's elapsed time is flushed into {@link BestFriendsStore}
 * (and the store's dirty flag written to disk) every {@link #FLUSH_INTERVAL_POLLS} polls (~30s), and
 * unconditionally on disconnect - so a crash loses at most that ~30s window, comfortably inside the brief's
 * "at most a minute" bound.
 * <p>
 * <b>Dungeon run crediting.</b> A run is "finished" the instant {@link DungeonState#isInDungeon()} falls from
 * true to false (mirrors how {@code runsummary.RunSummaryFeature} detects a finished run, but independent of
 * that feature's own on/off switch - Best Friends must keep counting runs even if Run Summary is off). The
 * floor credited is the last non-null {@link DungeonState#getFloor()} seen while still in the dungeon, and
 * every teammate this tracker was actively accruing time for at that instant gets +1 for that floor key.
 */
public final class BestFriendsTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-bestfriends");

    private static final int POLL_INTERVAL_TICKS = 20; // ~1s
    private static final int FLUSH_INTERVAL_POLLS = 30; // ~30s of polls

    /** Currently-partied, resolved teammates: uuid -> accrual segment start (ms). */
    private static final Map<UUID, Long> SESSION_START = new HashMap<>();
    private static final Map<UUID, String> SESSION_NAMES = new HashMap<>();

    private static int pollCounter = 0;
    private static int flushCounter = 0;
    private static boolean wasInDungeon = false;
    private static String lastFloorWhileInDungeon = null;

    private BestFriendsTracker() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BestFriendsTracker::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
    }

    private static void tick(Minecraft client) {
        if (++pollCounter < POLL_INTERVAL_TICKS) {
            return;
        }
        pollCounter = 0;

        if (!BestFriendsConfig.getInstance().isEnabled()) {
            if (!SESSION_START.isEmpty()) {
                flushAll(System.currentTimeMillis());
                SESSION_START.clear();
                SESSION_NAMES.clear();
            }
            wasInDungeon = false;
            lastFloorWhileInDungeon = null;
            return;
        }

        long now = System.currentTimeMillis();
        pollParty(now);
        if (++flushCounter >= FLUSH_INTERVAL_POLLS) {
            flushCounter = 0;
            flushAll(now);
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
    private static void pollParty(long now) {
        Set<UUID> current = new LinkedHashSet<>();
        for (String name : PartyTracker.teammates()) {
            PlayerNames.resolveAsync(name, id -> {
                if (id == null) {
                    return;
                }
                current.add(id);
                SESSION_NAMES.put(id, name);
                SESSION_START.putIfAbsent(id, now);
            });
        }
        SESSION_START.keySet().removeIf(id -> {
            if (current.contains(id)) {
                return false;
            }
            flushOne(id, now);
            SESSION_NAMES.remove(id);
            return true;
        });
    }

    private static void flushOne(UUID id, long now) {
        Long start = SESSION_START.get(id);
        if (start == null) {
            return;
        }
        long elapsedSeconds = Math.max(0, (now - start) / 1000L);
        if (elapsedSeconds <= 0) {
            return;
        }
        BestFriendsStore.Record record = BestFriendsStore.getOrCreate(id, SESSION_NAMES.get(id));
        record.totalPartySeconds += elapsedSeconds;
        record.lastPartiedAtMs = now;
    }

    private static void flushAll(long now) {
        for (UUID id : new ArrayList<>(SESSION_START.keySet())) {
            flushOne(id, now);
            // Keep accruing (segment restarts at "now") rather than stopping - flushAll during normal play
            // is just the periodic checkpoint, not the end of the party.
            SESSION_START.put(id, now);
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
        if (SESSION_START.isEmpty()) {
            return;
        }
        flushAll(System.currentTimeMillis());
        SESSION_START.clear();
        SESSION_NAMES.clear();
        wasInDungeon = false;
        lastFloorWhileInDungeon = null;
        BestFriendsStore.saveAsync();
    }
}
