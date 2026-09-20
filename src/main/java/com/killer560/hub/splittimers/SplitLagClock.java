package com.killer560.hub.splittimers;

import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Accumulates real server-tick stall time for Split Timers' "lagless" per-split times and the bottom
 * Total (without lag) / Lag Lost lines - killer560, 2026-09-20: "add in lagless time to the right of each
 * split... so I know what the split was without lag" and "total running time with and without lag, and the
 * lag lost timer."
 * <p>
 * <b>Reuses, not reinvents:</b> per this task's own instruction, this is built on the exact same clock
 * {@code hub.lagdisplay.LagDisplayFeature} already reads for its "zzz for N.NNs" line -
 * {@link ServerTickClock} (Odin's "one server tick per non-zero ClientboundPingPacket", i.e. one fire per
 * real Hypixel server tick while {@link ServerTickClock#isPingDriven()}). LagDisplayFeature shows that
 * clock's signal as an instant "ms since the last tick"; this class is a second, independent subscriber
 * (the same pattern Wither Dragons/Tick Timers/Lag Display already use - {@code ServerTickClock.register()}
 * is explicitly idempotent for exactly this) that instead accumulates it over time, so Split Timers can
 * answer "how much of this split/run was actually lag" rather than just "is the server stalled right now".
 * <p>
 * <b>Definition ("without lag" / "lag lost"):</b> whenever two consecutive ping-driven ticks are further
 * apart than one nominal Minecraft tick (50ms, plus a small jitter allowance), the excess over 50ms is real
 * server stall and is added to the running total. A split's/run's "lagless" time is its plain wall-clock
 * duration minus however much of this total accumulated during that exact window (the delta between the
 * accumulator's value at the window's start and end) - never a second, differently-defined "lag".
 * <p>
 * Only counted while ping-driven, same restriction and same reason as LagDisplayFeature: a plain
 * client-tick fallback (a server that doesn't ping ~20x/s - possible on a p3sim-style test server) is this
 * mod ticking itself, not Hypixel actually stalling, and must never be counted as lag lost. On a connection
 * where a real ping-driven tick is never observed at all, {@link #isTrustworthy()} is false and Split
 * Timers hides every lag-derived number instead of showing a dishonest zero - he needs to be able to trust
 * these against Hypixel's own end-of-run time, and a number this class can't actually vouch for is worse
 * than none.
 */
final class SplitLagClock {

    /** One Minecraft tick - the cadence {@link ServerTickClock}'s ping-driven mode fires at. */
    private static final long NOMINAL_TICK_MS = 50L;
    /** Below this gap it's ordinary jitter, not a real stall - only excess beyond this counts. */
    private static final long STALL_THRESHOLD_MS = 75L;

    private static volatile long accumulatedLagMs = 0L;
    private static volatile long lastPingDrivenTickMs = 0L;
    private static volatile boolean pingClockSeen = false;
    private static boolean registered = false;

    private SplitLagClock() {
    }

    static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickClock.register();
        ServerTickClock.subscribe(SplitLagClock::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
    }

    private static void reset() {
        accumulatedLagMs = 0L;
        lastPingDrivenTickMs = 0L;
        pingClockSeen = false;
    }

    private static void onTick() {
        if (!ServerTickClock.isPingDriven()) {
            // Don't move the reference point on a client-tick fallback fire, so the next real ping's
            // gap captures the whole stalled/fallback stretch instead of resetting to a small number.
            return;
        }
        pingClockSeen = true;
        long now = System.currentTimeMillis();
        if (lastPingDrivenTickMs != 0L) {
            long gap = now - lastPingDrivenTickMs;
            if (gap > STALL_THRESHOLD_MS) {
                accumulatedLagMs += gap - NOMINAL_TICK_MS;
            }
        }
        lastPingDrivenTickMs = now;
    }

    /** Total lag accumulated since the last (re)connect - monotonic. Split Timers snapshots this at each
     *  split boundary and subtracts the deltas to get lagless times; never read as an absolute value. */
    static long accumulatedLagMs() {
        return accumulatedLagMs;
    }

    /** False until a real ping-driven server tick has actually been observed this connection - e.g. a test
     *  server that never pings ~20x/s. Split Timers hides every lag-derived number while this is false. */
    static boolean isTrustworthy() {
        return pingClockSeen;
    }
}
