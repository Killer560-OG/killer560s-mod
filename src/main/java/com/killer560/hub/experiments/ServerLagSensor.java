package com.killer560.hub.experiments;

import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Measures how long the server has stalled, for Superpairs' Adaptive Timeout. killer560, 2026-10-04: "it
 * should sense when the server is lagging and auto delay by that amount until it stops lagging."
 * <p>
 * Hypixel sends one non-zero ping per server tick (see {@link ServerTickClock}), so two pings further apart
 * than one tick (plus a jitter allowance) mean the server stopped ticking for the excess. This is the same
 * definition {@code splittimers.SplitLagClock} uses for "lag lost", with one difference: it listens to RAW
 * pings, because a real stall drops the ping rate below the threshold that keeps {@link ServerTickClock}
 * ping-driven, and the ticks it then fires are client ticks that know nothing about the server.
 * <p>
 * {@link #lagMs()} is monotonic: what has stalled so far this connection, INCLUDING a stall still in
 * progress. A caller snapshots it when it starts waiting and subtracts, so its wait grows by exactly the time
 * the server spent frozen, and stops growing the moment pings come back at the normal rate.
 * <p>
 * Nothing is counted until the server has been seen pinging once per tick this connection; a server that
 * pings once a second would otherwise read as permanently lagging.
 */
public final class ServerLagSensor {

    private static final long NOMINAL_TICK_MS = 50L;
    /** Below this gap it is ordinary jitter, not a stall. */
    private static final long STALL_THRESHOLD_MS = 75L;

    private static long accumulatedLagMs = 0L;
    private static long lastPingMs = 0L;
    private static boolean trusted = false;
    private static boolean registered = false;

    private ServerLagSensor() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickClock.register();
        ServerTickClock.subscribeRawPing(ServerLagSensor::onPing);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
    }

    private static synchronized void reset() {
        accumulatedLagMs = 0L;
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
                accumulatedLagMs += gap - NOMINAL_TICK_MS;
            }
        }
        lastPingMs = now;
    }

    /** @return total server stall this connection in ms, counting a stall still in progress; 0 while the
     *  server has not yet been seen pinging once per tick. */
    public static synchronized long lagMs() {
        if (!trusted || lastPingMs == 0L) {
            return accumulatedLagMs;
        }
        long gap = System.currentTimeMillis() - lastPingMs;
        return gap > STALL_THRESHOLD_MS ? accumulatedLagMs + gap - NOMINAL_TICK_MS : accumulatedLagMs;
    }
}
