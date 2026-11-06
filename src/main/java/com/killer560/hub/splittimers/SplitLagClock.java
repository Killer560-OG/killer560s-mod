package com.killer560.hub.splittimers;

import com.killer560.hub.util.PerTickServerClock;
import com.killer560.hub.util.StallLag;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Lag for Split Timers' "lagless" per-split times and the bottom Total (without lag) / Lag Lost lines - killer560,
 * 2026-09-20: "add in lagless time to the right of each split... so I know what the split was without lag".
 * <p>
 * <b>Counted the way NoammAddons and Odin count (2026-11-03, timers2).</b> Both keep, beside the wall clock, a count of
 * server ticks (Noamm's {@code DualTime(ticks, real)}, Odin's {@code SplitsManager.tickCounter}); lag lost is the wall
 * time minus 50 ms per tick. The count is {@link StallLag} (stalls in full, a slow server where the deficit's low rises,
 * never climbing faster than the wall clock), shared with Blood Camp's camp lag since bcstall-1105.
 * <p>
 * Only counted while {@link PerTickServerClock#trusted()}: on a server that does not ping once a tick the clock is the
 * client ticking itself, which is not lag, and every lag-derived number is hidden ({@link #isTrustworthy()}) instead of
 * showing a dishonest zero.
 */
final class SplitLagClock {

    private static volatile boolean pingClockSeen;
    private static boolean registered;

    private SplitLagClock() {
    }

    static synchronized void register() {
        if (registered) return;
        registered = true;
        PerTickServerClock.register();
        PerTickServerClock.subscribe(() -> {
            if (PerTickServerClock.trusted()) pingClockSeen = true;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pingClockSeen = false);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> pingClockSeen = false);
    }

    /** Testkit: start over (a fresh connection). */
    static void resetForTest() {
        StallLag.reset();
        pingClockSeen = false;
    }

    /** Lag so far ({@link StallLag#lagMs}). Split Timers snapshots it at each split boundary and subtracts; never read as
     *  an absolute value. */
    static long accumulatedLagMs() { return StallLag.lagMs(); }

    /** A lag span as shown on the Lag row: under 150 ms (three ticks) is arrival jitter, not a stall (2026-10-18). */
    static long shownLag(long ms) { return ms < 150L ? 0L : ms; }

    /** False until a per-tick pinging server has been trusted this connection. Split Timers hides every lag-derived
     *  number while this is false. */
    static boolean isTrustworthy() { return pingClockSeen; }
}
