package com.killer560.hub.util;

/**
 * The server's lag, in ms, on {@link PerTickServerClock}: Split Timers' lagless times and Lag row, Blood Camp's camp lag
 * and PingTracker's clean round trips all read this one count ({@link PerTickServerClock#stallTicksSince}). The deficit
 * {@code wall - 50 ms * server ticks} counts in two parts that only ever add:
 * <ul>
 * <li><b>Stalls</b> (tickfix2-1104, killer560: "run splits never shows lag now"): one ping gap more than
 * {@link #STALL_MS} over its 50 ms counts in full and grows live while it lasts. The server ticks that then come in a
 * burst within {@link #PAYBACK_MS} (pings faster than one per 50 ms, and the clock moved up to the server's game time)
 * pay it back (splitlag-1116, killer560 2026-11-16: "it should only count lag as the server lag, not my client-side
 * lag"): the server ran those ticks on time and only their delivery waited, on a frozen client (the queued packets are
 * handled at once when it wakes) or a stalled connection. Hypixel does not catch up a real stall (its game time stays
 * behind, so no burst comes). His 11-14/15 logs: per 60 s census window the old count read 2-6 s (16 s in one) while the
 * server's game time fell 0.1-0.5 s behind the wall clock.</li>
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
    /** How long after a stall ends a burst of server ticks may still pay it back (a frozen client handles its queued
     *  packets in one go, a stalled connection delivers them in one go; a second covers a slow frame or two). */
    static final long PAYBACK_MS = 1000L;
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
    /** The last stall while a burst may still pay it back: its size, its ping gap, the net of every tick's lateness since
     *  it ended and that net's low point, what was paid back, and until when. */
    private static long pendingMs;
    private static long pendingGapMs;
    private static long pendingNetMs;
    private static long pendingLowMs;
    private static long pendingPaidMs;
    private static long pendingUntilMs;
    /** Testkit: stalls seen and ms paid back since the last {@link #reset}, so a case can prove the gap was seen. */
    private static int stallsSeen;
    private static long paidBackMs;

    private StallLag() {
    }

    /** Lag counted on earlier connections: a rejoin of the same run keeps it, so a split begun before the rejoin still
     *  reads only real lag when it subtracts across it. */
    private static long baseMs;

    /** A new or dropped connection: start the count over, keeping what was counted so far. */
    public static synchronized void newConnection() {
        baseMs += shownMs;
        clear();
    }

    /** Start over from zero (testkit). */
    public static synchronized void reset() {
        baseMs = 0L;
        clear();
    }

    private static void clear() {
        deficitMs = 0L;
        blockStartMs = 0L;
        blockLowMs = Long.MAX_VALUE;
        refMs = Long.MIN_VALUE;
        stallLagMs = 0L;
        slowLagMs = 0L;
        lastTickMs = 0L;
        shownMs = 0L;
        shownAtMs = 0L;
        pendingMs = 0L;
        stallsSeen = 0;
        paidBackMs = 0L;
    }

    /** Ends the pay-back window of the last stall with one line saying what stayed counted. */
    private static void closePending() {
        if (pendingMs == 0L) return;
        if (pendingMs - pendingPaidMs <= 0L) {
            pendingMs = 0L;
            return;
        }
        LOGGER.info("[StallLag] stall: +{} ms of lag (ping gap {} ms, {} ms paid back by the server ticks that came in a burst"
                + " after it), {} ms this connection", pendingMs - pendingPaidMs, pendingGapMs, pendingPaidMs,
                stallLagMs + slowLagMs);
        pendingMs = 0L;
    }

    /** A {@link PerTickServerClock} tick (subscribed by its register). */
    static synchronized void onTick() {
        if (!PerTickServerClock.trusted()) {
            lastTickMs = 0L;
            closePending();
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
            closePending();
            stallLagMs += over;
            stallsSeen++;
            pendingMs = over;
            pendingGapMs = now - last;
            pendingNetMs = 0L;
            pendingLowMs = 0L;
            pendingPaidMs = 0L;
            pendingUntilMs = now + PAYBACK_MS;
        } else if (pendingMs > 0L && now <= pendingUntilMs) {
            // The low point of the ticks' net lateness since the stall ended: a burst drives it down by 50 ms a tick the
            // server ran during the gap; jitter (a late tick, then an early one) leaves it where it was.
            pendingNetMs += over;
            pendingLowMs = Math.min(pendingLowMs, pendingNetMs);
            long paid = Math.min(pendingMs, -pendingLowMs);
            long step = paid - pendingPaidMs;
            pendingPaidMs = paid;
            stallLagMs -= step;
            paidBackMs += step;
            deficitMs += over + step;
        } else {
            closePending();
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

    /** Testkit: stalls seen since the last {@link #reset}. */
    public static synchronized int stallsSeen() { return stallsSeen; }

    /** Testkit: ms of stalls paid back by a burst since the last {@link #reset}. */
    public static synchronized long paidBackMs() { return paidBackMs; }

    /** Lag so far: stalls (one still running counts live) plus a slow server, climbing no faster than the wall clock,
     *  and dropping only when a burst pays a stall back (a lagless time only climbs either way).
     *  Only differences between two reads mean anything; never read as an absolute value. */
    public static synchronized long lagMs() {
        long now = PerTickServerClock.wallMs();
        long last = lastTickMs;
        long live = last == 0L ? 0L : now - last - NOMINAL_TICK_MS;
        long lag = stallLagMs + slowLagMs + (live > STALL_MS ? live : 0L);
        long cap = shownAtMs == 0L ? lag : shownMs + Math.max(0L, now - shownAtMs);
        shownMs = Math.min(lag, cap);
        shownAtMs = now;
        return baseMs + shownMs;
    }
}
