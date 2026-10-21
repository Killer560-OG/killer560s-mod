package com.killer560.hub.util;

import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * How long the SERVER has stalled - the mod-wide server-lag signal every wait for a server answer reads.
 * killer560, 2026-10-15: "Make sure interactive map detects server lag and will not proc the warning if the server
 * lags preventing it from etherwarping. Same with auto puzzles and auto routes and things it needs to always account
 * for server lag." (It began as Superpairs' Adaptive Timeout sensor, 2026-10-04.)
 * <p>
 * Hypixel sends one non-zero ping per server tick (see {@link ServerTickClock}), and so does the sim's server
 * ({@code roomsim.SimServerTicks}), so two pings further apart than one tick (plus a jitter allowance) mean the
 * server stopped ticking for the excess. It listens to RAW pings, because a real stall drops the ping rate below the
 * threshold that keeps {@link ServerTickClock} ping-driven, and the ticks it then fires are client ticks.
 * <p>
 * {@link #lagMs()} is monotonic: what has stalled so far this connection, INCLUDING a stall still in progress. A wait
 * takes {@link #mark()} when it starts (or makes progress) and gives itself {@link #ticksSince(long)} more ticks before
 * it calls anything a failure, so its deadline grows by exactly the time the server spent frozen and by nothing at all
 * while the server keeps time: with no lag every timeout is the same tick count it always was. It only ever EXTENDS a
 * deadline; an answer that arrives is acted on the moment it arrives, lag or not.
 * <p>
 * Nothing is counted until the server has been seen pinging once per tick in this world; a server that pings once a
 * second (or not at all) would otherwise read as permanently lagging, so there it reads 0 and every timeout is
 * unchanged. A world change drops that trust until the new world pings at the full rate again. One stall counts at
 * most {@link #MAX_STALL_MS}: a server that simply stopped pinging must not hold every wait open forever.
 * <p>
 * Two measures. The wall-clock one ({@link #lagMs}, {@link #msSince}, {@link #serverMsSince}) is for waits timed in
 * ms. Waits counted in CLIENT ticks ({@link #ticksSince}, {@link #expectedDelayTicks}) get only the part of a gap the
 * client spent ticking with no ping: a gap the client itself did not tick through (a client hitch, a frame slower than a
 * tick, the testkit's lockstep recorder) has cost such a wait no ticks, so it owes it none. Before (1915-video-autosecret,
 * 2026-10-20): the recorder took ~150 ms per tick, every one-tick gap read as a 100 ms stall, and the Interactive Map's
 * eaten-hop resend ({@code ClearExecutor.tickEatenHop}, a few ticks) was pushed out by the full 600-tick cap - he stood
 * still for 30 s outside Ice Fill until Auto Secret's trip timeout ended it. {@link #mark} carries both readings.
 */
public final class ServerLag {

    private static final long NOMINAL_TICK_MS = 50L;
    /** Below this gap it is ordinary jitter, not a stall. */
    private static final long STALL_THRESHOLD_MS = 75L;
    /** The most one stall (one gap between pings) adds. */
    public static final long MAX_STALL_MS = 30_000L;
    /** The most {@link #ticksSince} extends one wait by. */
    public static final int MAX_EXTENSION_TICKS = (int) (MAX_STALL_MS / NOMINAL_TICK_MS);

    private static long accumulatedLagMs = 0L;
    /** The tick-bounded measure: each stall counted at most up to the client ticks that ended without a ping. */
    private static long accumulatedTickLagMs = 0L;
    private static long lastPingMs = 0L;
    /** Client ticks ended (END_CLIENT_TICK), and the count at the last ping. */
    private static long clientTicks = 0L;
    private static long lastPingTick = 0L;
    private static boolean trusted = false;
    private static boolean registered = false;
    private static ClientLevel lastLevel = null;
    /** Test hook: the stalls measured since launch (each gap past the threshold), for a scenario to prove it saw one. */
    private static int stallsSeen = 0;
    private static long longestStallMs = 0L;

    private ServerLag() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickClock.register();
        ServerTickClock.subscribeRawPing(ServerLag::onPing);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ServerLag", client -> {
            synchronized (ServerLag.class) {
                clientTicks++;
            }
            if (client.level != lastLevel) {
                lastLevel = client.level;
                untrust();
            }
        }));
    }

    private static synchronized void reset() {
        accumulatedLagMs = 0L;
        accumulatedTickLagMs = 0L;
        untrust();
    }

    /** A new world: its server has to show it pings every tick before a gap means anything. The total is kept, so a
     *  wait that spans the change keeps the lag it has already been given. */
    private static synchronized void untrust() {
        lastPingMs = 0L;
        trusted = false;
    }

    private static synchronized void onPing() {
        long now = System.currentTimeMillis();
        if (ServerTickClock.isPingDriven()) {
            trusted = true;
        }
        if (trusted && lastPingMs != 0L) {
            long gap = now - lastPingMs;
            if (gap > STALL_THRESHOLD_MS) {
                long stall = Math.min(MAX_STALL_MS, gap - NOMINAL_TICK_MS);
                accumulatedLagMs += stall;
                long ticked = Math.min(stall, tickedWithoutPingMs());
                accumulatedTickLagMs += ticked;
                if (ticked > 0) {
                    stallsSeen++;
                    longestStallMs = Math.max(longestStallMs, ticked);
                }
            }
        }
        lastPingMs = now;
        lastPingTick = clientTicks;
    }

    /** The client ticks that ended since the last ping beyond the one a ping is due in, as ms. */
    private static long tickedWithoutPingMs() {
        return Math.max(0L, clientTicks - lastPingTick - 1) * NOMINAL_TICK_MS;
    }

    /** @return total server stall this connection in ms, counting a stall still in progress; 0 while the
     *  server has not yet been seen pinging once per tick. */
    public static synchronized long lagMs() {
        return accumulatedLagMs + stallNowMs();
    }

    /** The stall in progress right now, in ms (0 when the last ping was on time or the source is not trusted). */
    private static long stallNowMs() {
        if (!trusted || lastPingMs == 0L) {
            return 0L;
        }
        long gap = System.currentTimeMillis() - lastPingMs;
        return gap > STALL_THRESHOLD_MS ? Math.min(MAX_STALL_MS, gap - NOMINAL_TICK_MS) : 0L;
    }

    /** The tick-bounded total ({@link #ticksSince}), counting a stall still in progress. */
    private static synchronized long tickLagMs() {
        return accumulatedTickLagMs + Math.min(stallNowMs(), tickedWithoutPingMs());
    }

    /**
     * A reading to measure a wait's lag from: take it when the wait starts, or whenever it makes progress. It packs both
     * measures (the tick-bounded one in the high 32 bits, the wall-clock one in the low 32, ms - 49 days each), so a
     * stored mark serves {@link #ticksSince} and {@link #msSince} alike; it is only ever handed back to them.
     */
    public static synchronized long mark() {
        return (Math.min(tickLagMs(), MASK) << 32) | Math.min(lagMs(), MASK);
    }

    private static final long MASK = 0xFFFFFFFFL;

    /** @return how long the server has stalled since {@code mark}, in ms, at most {@link #MAX_STALL_MS}. */
    public static long msSince(long mark) {
        return Math.max(0L, Math.min(MAX_STALL_MS, Math.min(lagMs(), MASK) - (mark & MASK)));
    }

    /** {@link #msSince} on the tick-bounded measure. */
    private static long tickMsSince(long mark) {
        return Math.max(0L, Math.min(MAX_STALL_MS, Math.min(tickLagMs(), MASK) - (mark >>> 32)));
    }

    /**
     * Wall-clock ms since {@code startMs} minus the server's stall since {@code mark} (both taken together when the wait
     * began): the time the SERVER has had to answer. Compare it with a wait's ms timeout where it used to compare
     * {@code System.currentTimeMillis() - startMs}; with no lag the two are the same number.
     */
    public static long serverMsSince(long startMs, long mark) {
        return Math.max(0L, System.currentTimeMillis() - startMs - msSince(mark));
    }

    /**
     * The ticks to add to a wait's deadline: the server's stall since {@code mark}, rounded UP to whole ticks (a
     * 60 ms stall is one tick the server did not run), at most {@link #MAX_EXTENSION_TICKS}. 0 with no lag, so
     * {@code ticks > TIMEOUT + ServerLag.ticksSince(mark)} is exactly {@code ticks > TIMEOUT} on a server that keeps time.
     */
    public static int ticksSince(long mark) {
        long ms = tickMsSince(mark);
        return ms <= 0L ? 0 : (int) Math.min(MAX_EXTENSION_TICKS, (ms + NOMINAL_TICK_MS - 1) / NOMINAL_TICK_MS);
    }

    /** The expected extra server delay right now, in ticks: the stall in progress (0 while the server keeps time). */
    public static synchronized int expectedDelayTicks() {
        long ms = Math.min(stallNowMs(), tickedWithoutPingMs());
        return ms <= 0L ? 0 : (int) ((ms + NOMINAL_TICK_MS - 1) / NOMINAL_TICK_MS);
    }

    /** True while the server is stalled right now (no ping for longer than a tick and its jitter). */
    public static boolean stalled() {
        return expectedDelayTicks() > 0;
    }

    /** True once the server has been seen pinging every tick in this world, so a gap is a measurement. */
    public static synchronized boolean measuring() {
        return trusted;
    }

    /** Test hook: stalls measured since launch. */
    public static synchronized int stallsSeen() {
        return stallsSeen;
    }

    /** Test hook: the longest single stall measured since launch, ms. */
    public static synchronized long longestStallMs() {
        return longestStallMs;
    }
}
