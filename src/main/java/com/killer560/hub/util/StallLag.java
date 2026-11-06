package com.killer560.hub.util;

/**
 * The server's lag, in ms, on {@link PerTickServerClock}: Split Timers' lagless times and Lag row, Blood Camp's camp lag
 * and PingTracker's clean round trips all read this one count ({@link PerTickServerClock#stallTicksSince}). The deficit
 * {@code wall - 50 ms * server ticks} counts in two parts that only ever add:
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
 * Blood Camp's stall ticks had the same ratchet on client ticks until bcstall-1105. A drop of more than {@link #DROP_MS}
 * (a catch-up burst) lowers the reference, so a stall's burst never hides later lag.</li>
 * </ul>
 * The total is shown so it never climbs faster than the wall clock, so a lagless time (real minus lag) only climbs too.
 * Counted only on trusted server ticks; a client-tick fallback fire is the mod ticking itself and clears the reference.
 * Reset on join and disconnect.
 */
public final class StallLag {

    private static final long NOMINAL_TICK_MS = 50L;
    /** A ping gap more than this over 50 ms is a stall (three ticks). */
    static final long STALL_MS = 150L;
    /** The slow-server part compares the deficit's lowest point block by block, this long each (two time packets). */
    static final long BLOCK_MS = 2000L;
    /** A block low this far under the reference is a catch-up burst: the reference follows it down. */
    static final long DROP_MS = 100L;
    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-servertick");

    /** Wall minus 50 ms per tick since the first tick, without the stalls (they count on their own). */
    private static long deficitMs;
    private static long blockStartMs;
    private static long blockLowMs = Long.MAX_VALUE;
    private static long refMs = Long.MIN_VALUE;
    private static long stallLagMs;
    private static long slowLagMs;
    private static long lastTickMs;
    private static long shownMs;
    private static long shownAtMs;

    private StallLag() {
    }

    /** Start over (a fresh connection; testkit). */
    public static synchronized void reset() {
        deficitMs = 0L;
        blockStartMs = 0L;
        blockLowMs = Long.MAX_VALUE;
        refMs = Long.MIN_VALUE;
        stallLagMs = 0L;
        slowLagMs = 0L;
        lastTickMs = 0L;
        shownMs = 0L;
        shownAtMs = 0L;
    }

    /** A {@link PerTickServerClock} tick (subscribed by its register). */
    static synchronized void onTick() {
        if (!PerTickServerClock.trusted()) {
            lastTickMs = 0L;
            return;
        }
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
            LOGGER.info("[StallLag] stall: +{} ms of lag (ping gap {} ms), {} ms this connection",
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

    /** Lag so far: stalls (one still running counts live) plus a slow server, climbing no faster than the wall clock.
     *  Only differences between two reads mean anything; never read as an absolute value. */
    public static synchronized long lagMs() {
        long now = PerTickServerClock.wallMs();
        long last = lastTickMs;
        long live = last == 0L ? 0L : now - last - NOMINAL_TICK_MS;
        long lag = stallLagMs + slowLagMs + (live > STALL_MS ? live : 0L);
        long cap = shownAtMs == 0L ? lag : shownMs + Math.max(0L, now - shownAtMs);
        shownMs = Math.max(shownMs, Math.min(lag, cap));
        shownAtMs = now;
        return shownMs;
    }
}
