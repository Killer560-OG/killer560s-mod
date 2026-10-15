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
    private static long lastPingMs = 0L;
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
            if (client.level != lastLevel) {
                lastLevel = client.level;
                untrust();
            }
        }));
    }

    private static synchronized void reset() {
        accumulatedLagMs = 0L;
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
                stallsSeen++;
                longestStallMs = Math.max(longestStallMs, stall);
            }
        }
        lastPingMs = now;
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

    /** A reading to measure a wait's lag from: take it when the wait starts, or whenever it makes progress. */
    public static long mark() {
        return lagMs();
    }

    /** @return how long the server has stalled since {@code mark}, in ms, at most {@link #MAX_STALL_MS}. */
    public static long msSince(long mark) {
        return Math.max(0L, Math.min(MAX_STALL_MS, lagMs() - mark));
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
        long ms = msSince(mark);
        return ms <= 0L ? 0 : (int) Math.min(MAX_EXTENSION_TICKS, (ms + NOMINAL_TICK_MS - 1) / NOMINAL_TICK_MS);
    }

    /** The expected extra server delay right now, in ticks: the stall in progress (0 while the server keeps time). */
    public static synchronized int expectedDelayTicks() {
        long ms = stallNowMs();
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
