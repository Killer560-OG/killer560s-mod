package com.killer560.hub.splittimers;

import com.killer560.hub.util.PerTickServerClock;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Lag for Split Timers' "lagless" per-split times and the bottom Total (without lag) / Lag Lost lines - killer560,
 * 2026-09-20: "add in lagless time to the right of each split... so I know what the split was without lag".
 * <p>
 * <b>Counted the way NoammAddons and Odin count (2026-11-03, timers2).</b> Both keep, beside the wall clock, a count of
 * server ticks (Noamm's {@code DualTime(ticks, real)}, Odin's {@code SplitsManager.tickCounter}); lag lost is the wall
 * time minus 50 ms per tick. This clock follows the same deficit, {@code wall - 50 ms * server ticks}, on
 * {@link PerTickServerClock}, in two parts that only ever add:
 * <ul>
 * <li><b>Stalls</b> (tickfix2-1104, killer560: "run splits never shows lag now"): one ping gap more than
 * {@link #STALL_MS} over its 50 ms counts in full, grows live while it lasts, and is never paid back by the catch-up
 * burst that follows (the server ran those ticks late; the player still waited).</li>
 * <li><b>A slow server</b> (laglag-1105, killer560: "the lag timer is going up while the server isnt lagging"): the rest
 * of the deficit counts only where its LOW point rises, the lowest value in each {@link #BLOCK_MS} block. Network jitter
 * only ever makes a ping late, so a late ping and the quick one after it never move a block's low; a server at 19.8 TPS
 * moves every block's low up by 20 ms. Until laglag the deficit's rise over its all-time low counted at 150 ms and the low
 * then fell back with the jitter, so the jitter was counted again and again: his ship 8 Goldor phase read 6452 ms of
 * stalls while the server's own ticks said 1545 ms (2100 ticks in 106545 ms); a sim of 20 TPS with 7% in-chain extras and
 * one ping in 40 late 60-100 ms read 330-700 ms in 2 min (now 0-50), and 19.8 TPS read 2.3-2.6 s for 1.2 s (now 1.12-1.16 s).
 * A drop of more than {@link #DROP_MS} (a catch-up burst) lowers the reference, so a stall's burst never hides later lag.</li>
 * </ul>
 * The total is shown so it never climbs faster than the wall clock, so the lagless time (real minus lag) only climbs too.
 * <p>
 * Only counted while {@link PerTickServerClock#trusted()}: on a server that does not ping once a tick the clock is the
 * client ticking itself, which is not lag, and every lag-derived number is hidden ({@link #isTrustworthy()}) instead of
 * showing a dishonest zero.
 */
final class SplitLagClock {

    private static final long NOMINAL_TICK_MS = 50L;
    /** A ping gap more than this over 50 ms is a stall (three ticks, as {@link #shownLag}). */
    static final long STALL_MS = 150L;
    /** The slow-server part compares the deficit's lowest point block by block, this long each (two time packets). */
    static final long BLOCK_MS = 2000L;
    /** A block low this far under the reference is a catch-up burst: the reference follows it down. */
    static final long DROP_MS = 100L;

    /** Wall minus 50 ms per tick since the first tick, without the stalls (they count on their own). */
    private static long deficitMs;
    private static long blockStartMs;
    private static long blockLowMs = Long.MAX_VALUE;
    private static long refMs = Long.MIN_VALUE;
    private static volatile long stallLagMs;
    private static volatile long slowLagMs;
    private static volatile long lastTickMs;
    private static long shownMs;
    private static long shownAtMs;
    private static volatile boolean pingClockSeen;
    private static boolean registered;

    private SplitLagClock() {
    }

    static synchronized void register() {
        if (registered) return;
        registered = true;
        PerTickServerClock.register();
        PerTickServerClock.subscribe(SplitLagClock::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
    }

    private static synchronized void reset() {
        deficitMs = 0L;
        blockStartMs = 0L;
        blockLowMs = Long.MAX_VALUE;
        refMs = Long.MIN_VALUE;
        stallLagMs = 0L;
        slowLagMs = 0L;
        lastTickMs = 0L;
        shownMs = 0L;
        shownAtMs = 0L;
        pingClockSeen = false;
    }

    private static synchronized void onTick() {
        if (!PerTickServerClock.trusted()) {
            // A client-tick fallback fire is this mod ticking itself, not the server: no reference point, no lag.
            lastTickMs = 0L;
            return;
        }
        pingClockSeen = true;
        long now = PerTickServerClock.wallMs();
        long last = lastTickMs;
        lastTickMs = now;
        if (last == 0L) {
            blockStartMs = now;
            return;
        }
        long over = now - last - NOMINAL_TICK_MS;
        if (over > STALL_MS) {
            stallLagMs += over;
            SplitTimersFeature.LOGGER.info("[SplitLag] stall: +{} ms of lag (ping gap {} ms), {} ms this connection",
                    over, now - last, stallLagMs + slowLagMs);
        } else {
            deficitMs += over;
        }
        blockLowMs = Math.min(blockLowMs, deficitMs);
        if (now - blockStartMs < BLOCK_MS) return;
        long low = blockLowMs;
        blockStartMs = now;
        blockLowMs = Long.MAX_VALUE;
        if (refMs == Long.MIN_VALUE || low < refMs - DROP_MS) {
            refMs = low;
        } else if (low > refMs) {
            slowLagMs += low - refMs;
            refMs = low;
        }
    }

    /** Testkit: start over (a fresh connection). */
    static void resetForTest() { reset(); }

    /** Lag so far: stalls (one still running counts live) plus a slow server, climbing no faster than the wall clock, so
     *  the lagless time (real minus lag) only ever climbs. Split Timers snapshots it at each split boundary and subtracts;
     *  never read as an absolute value. */
    static synchronized long accumulatedLagMs() {
        long now = PerTickServerClock.wallMs();
        long last = lastTickMs;
        long live = last == 0L ? 0L : now - last - NOMINAL_TICK_MS;
        long lag = stallLagMs + slowLagMs + (live > STALL_MS ? live : 0L);
        long cap = shownAtMs == 0L ? lag : shownMs + Math.max(0L, now - shownAtMs);
        shownMs = Math.max(shownMs, Math.min(lag, cap));
        shownAtMs = now;
        return shownMs;
    }

    /** A lag span as shown on the Lag row: under 150 ms (three ticks) is arrival jitter, not a stall (2026-10-18). */
    static long shownLag(long ms) { return ms < 150L ? 0L : ms; }

    /** False until a per-tick pinging server has been trusted this connection. Split Timers hides every lag-derived
     *  number while this is false. */
    static boolean isTrustworthy() { return pingClockSeen; }
}
