package com.killer560.hub.autoclear;

import com.killer560.hub.livemap.autoclear.ClearExecutor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where a room's clear spends its ticks (killer560, 2026-10-20: "the best legit players clear rooms faster than solo Auto
 * Clear, purely because of mob-kill speed"). One clock per room Auto Clear works on, started on the first tick the clear
 * itself owns (he is in the room, or a mob of it is in sight from where he stands - never the trip TO the room, which is
 * the route side's business), and ended when the room is cleared, done without a map or given up. Every running tick in
 * between goes into exactly one bucket, so the buckets add up to the room's ticks, and the line it logs is what to read:
 * <pre>[AutoClear] room time: Mossy 143 ticks (cleared): trips 3 (38 ticks, 6 warps), firing 21, turning 9, swap 3, ...</pre>
 * Trips are also logged one by one ({@code [AutoClear] trip time:}) with the estimate they were chosen on, so a price
 * that is off shows up as the difference between the two figures.
 */
final class RoomClock {

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-autoclear");

    private static String room = null;
    private static long startTick = -1;
    private static long lastTick = -1;
    private static final Map<String, Integer> buckets = new LinkedHashMap<>();
    private static int trips = 0;
    private static int tripTicks = 0;
    private static int tripWarps = 0;
    private static int weaponUses = 0;
    private static int kills = 0;
    private static int usesSeen = -1;

    // ---- the trip under way ----
    private static long tripStart = -1;
    private static int tripUses = 0;
    private static int tripPriced = -1;
    private static String tripWhat = "";
    private static boolean tripWalk = false;

    private RoomClock() {
    }

    /** True while a room's clock runs. */
    static boolean running() {
        return room != null;
    }

    /** Starts the clock for {@code name} unless it already runs for it (a different room's clock is ended first). */
    static void start(String name) {
        if (name == null) {
            return;
        }
        if (name.equals(room)) {
            return;
        }
        if (room != null) {
            end("left for " + name);
        }
        room = name;
        lastDeath = -1;
        startTick = ClearExecutor.clientTick();
        lastTick = startTick - 1;
        buckets.clear();
        trips = 0;
        tripTicks = 0;
        tripWarps = 0;
        weaponUses = 0;
        kills = 0;
        usesSeen = com.killer560.hub.util.RightClick.itemUsesSent();
        tripStart = -1;
    }

    /**
     * One running tick, into {@code bucket}. Item uses that went out since the last tick are counted as warps while a
     * trip runs, as weapon uses otherwise. Called once a tick at most (a second call in the same tick is ignored).
     */
    static void tick(String bucket, boolean inTrip) {
        if (room == null) {
            return;
        }
        long now = ClearExecutor.clientTick();
        if (now == lastTick) {
            return;
        }
        lastTick = now;
        buckets.merge(bucket, 1, Integer::sum);
        int uses = com.killer560.hub.util.RightClick.itemUsesSent();
        int d = usesSeen < 0 ? 0 : Math.max(0, uses - usesSeen);
        usesSeen = uses;
        if (inTrip || tripStart >= 0) {
            tripUses += d;
        } else {
            weaponUses += d;
        }
    }

    /** A trip of the clear is under way (started and not yet ended). */
    static boolean tripOpen() {
        return tripStart >= 0;
    }

    static void kill() {
        if (room != null) {
            kills++;
        }
    }

    /** The tick (from the clock's start) the last starred mob of this room was seen dying, -1 none yet. */
    private static long lastDeath = -1;

    /** A starred mob counted for {@code mobRoom} was seen dying (by anything: a bat, a splash, the doomed one). */
    static void death(String mobRoom) {
        if (room != null && room.equals(mobRoom)) {
            lastDeath = ClearExecutor.clientTick() - startTick;
        }
    }

    /** A trip (an etherwarp chain or a walk) inside the clear started, priced at {@code priced} ticks (-1: not priced). */
    static void tripStarted(String what, int priced, boolean walk) {
        if (room == null) {
            return;
        }
        tripWalk = walk;
        tripStart = ClearExecutor.clientTick();
        tripUses = 0;
        tripPriced = priced;
        tripWhat = what;
    }

    /** The trip ended (landed, failed or abandoned): one {@code trip time} line, and its ticks into the room's figure. */
    static void tripEnded(boolean failed) {
        if (room == null || tripStart < 0) {
            return;
        }
        int ticks = (int) (ClearExecutor.clientTick() - tripStart);
        trips++;
        tripTicks += ticks;
        tripWarps += tripUses;
        if (!tripWalk) {
            AutoClearFeature.noteTripCost(ticks, tripUses, failed);
        }
        LOGGER.info(String.format(Locale.US, "[AutoClear] trip time: %s in %s - %d tick(s), %d use(s)"
                        + " sent%s%s", tripWhat, room, ticks, tripUses, tripPriced >= 0 ? ", priced ~" + tripPriced : "",
                failed ? " (failed)" : ""));
        tripStart = -1;
    }

    /** Ends the clock with {@code how} (cleared, given up, ...), logging the room's line. */
    static void end(String how) {
        if (room == null) {
            return;
        }
        if (tripStart >= 0) {
            tripEnded(false);
        }
        long total = Math.max(0, lastTick - startTick + 1);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : buckets.entrySet()) {
            sb.append(", ").append(e.getKey()).append(' ').append(e.getValue());
        }
        // "last death": when the room's last starred mob was seen dying, from the clock's start - the rest is the wait for
        // the map's clear (Hypixel's map, the sim's 10-tick room check).
        LOGGER.info(String.format(Locale.US, "[AutoClear] room time: %s %d ticks (%s; last death %d): trips %d"
                        + " (%d ticks, %d warp use(s)), weapon uses %d, kills %d%s", room, total, how, lastDeath, trips,
                tripTicks, tripWarps, weaponUses, kills, sb));
        room = null;
        lastDeath = -1;
        startTick = -1;
        tripStart = -1;
    }
}
