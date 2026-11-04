package com.killer560.hub.splittimers;

import com.killer560.hub.util.PerTickServerClock;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Lag for Split Timers' "lagless" per-split times and the bottom Total (without lag) / Lag Lost lines - killer560,
 * 2026-09-20: "add in lagless time to the right of each split... so I know what the split was without lag".
 * <p>
 * <b>Counted the way NoammAddons and Odin count (2026-11-03, timers2).</b> Both keep, beside the wall clock, a count of
 * server ticks (one per non-zero {@code ClientboundPingPacket}; Noamm's {@code DualTime(ticks, real)}, Odin's
 * {@code SplitsManager.tickCounter}), and a split's lagless time is its tick count times 50 ms; the lag is the wall time
 * minus that. This clock is the same thing on {@link PerTickServerClock}: {@link #accumulatedLagMs()} is
 * {@code wall time - 50 ms * server ticks}, so {@code real - (lag at the end - lag at the start)} is exactly the ticks
 * between the two, and it can only stand still or climb: during a stall the lag grows live (so the lagless time stands
 * still instead of climbing and then dropping when the next ping lands, which is what the old gap-by-gap accumulator did),
 * and a burst of pings after a network spike (the server kept ticking) gives the time back.
 * <p>
 * <b>2026-11-04 (killer560: "run splits never shows lag now").</b> That deficit nets out: his 2026-11-03 23:56 run showed
 * "Storm took 45.15s (43.90s)" and then "Lag: 0.00s" for the whole run, because the server ran the 25 lost ticks later
 * (a catch-up burst of pings) and the bursts' -50 ms each paid the Storm stall back, while each split clamps its own
 * lag at 0. So the lag shown is now the stalls only: the deficit ({@code wall - 50 ms * ticks}) is followed from its
 * lowest point, and a rise of more than {@link #STALL_MS} above that point counts in full and is never paid back; a
 * catch-up burst only lowers the low point. Arrival jitter (a rise under three ticks that falls back) counts nothing, a
 * steady 20 TPS reads 0, a server stall or a network spike reads as long as it lasted, and every split's lag adds up to
 * the run's Lag row. Each counted stall logs one {@code [SplitLag]} line.
 * <p>
 * Only counted while {@link PerTickServerClock#trusted()}: on a server that does not ping once a tick the clock is the
 * client ticking itself, which is not lag, and every lag-derived number is hidden ({@link #isTrustworthy()}) instead of
 * showing a dishonest zero.
 */
final class SplitLagClock {

    private static final long NOMINAL_TICK_MS = 50L;

    /** Lag as of the last clock tick (committed): wall time minus 50 ms per tick, relative to the first tick. */
    private static volatile long accumulatedLagMs = 0L;
    /** A rise of the deficit above its lowest point by more than this is a stall (three ticks, as {@link #shownLag}). */
    static final long STALL_MS = 150L;
    /** The lowest deficit since the last counted stall, and the stall lag counted so far (what the splits subtract). */
    private static volatile long lowMs = 0L;
    private static volatile long stallLagMs = 0L;
    private static volatile long lastTickMs = 0L;
    private static volatile boolean pingClockSeen = false;
    private static boolean registered = false;

    private SplitLagClock() {
    }

    static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        PerTickServerClock.register();
        PerTickServerClock.subscribe(SplitLagClock::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
    }

    private static void reset() {
        accumulatedLagMs = 0L;
        lowMs = 0L;
        stallLagMs = 0L;
        lastTickMs = 0L;
        pingClockSeen = false;
    }

    private static void onTick() {
        if (!PerTickServerClock.trusted()) {
            // A client-tick fallback fire is this mod ticking itself, not the server: no reference point, no lag.
            lastTickMs = 0L;
            return;
        }
        pingClockSeen = true;
        long now = System.currentTimeMillis();
        if (lastTickMs != 0L) {
            accumulatedLagMs += (now - lastTickMs) - NOMINAL_TICK_MS;
            long rise = accumulatedLagMs - lowMs;
            if (rise > STALL_MS) {
                stallLagMs += rise;
                lowMs = accumulatedLagMs;
                SplitTimersFeature.LOGGER.info("[SplitLag] stall: +{} ms of lag (last ping gap {} ms), {} ms this connection",
                        rise, now - lastTickMs, stallLagMs);
            } else if (rise < 0L) {
                lowMs = accumulatedLagMs;
            }
        }
        lastTickMs = now;
    }

    /** Testkit: start over (a fresh connection). */
    static synchronized void resetForTest() { reset(); }

    /** Lag so far: committed per tick, plus the live stall since the last tick, so the lagless time (real minus lag) only ever climbs. Split
     *  Timers snapshots it at each split boundary and subtracts; never read as an absolute value. */
    static long accumulatedLagMs() { return lagAt(System.currentTimeMillis()); }

    /** The stall lag counted so far, plus a stall still running at {@code nowMs} (the same rule as {@link #onTick}). */
    private static long lagAt(long nowMs) {
        long last = lastTickMs;
        long live = last == 0L ? 0L : Math.max(0L, nowMs - last - NOMINAL_TICK_MS);
        long rise = accumulatedLagMs + live - lowMs;
        return stallLagMs + (rise > STALL_MS ? rise : 0L);
    }

    /** A lag span as shown on the Lag row: under 150 ms (three ticks) is arrival jitter, not a stall (2026-10-18). */
    static long shownLag(long ms) { return ms < 150L ? 0L : ms; }

    /** False until a per-tick pinging server has been trusted this connection. Split Timers hides every lag-derived
     *  number while this is false. */
    static boolean isTrustworthy() { return pingClockSeen; }
}
