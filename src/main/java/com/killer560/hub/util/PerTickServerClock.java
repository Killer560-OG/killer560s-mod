package com.killer560.hub.util;

import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * A server-tick clock that is only driven by the server's pings when the server really pings once a tick (Hypixel does;
 * NoammAddons and Odin count its non-zero pings as server ticks). Blood Camp's countdowns and kill timer run on it, so a
 * server stall pauses them as it pauses the Watcher's mobs (doc-blood-1101, killer560 2026-11-01: lag and "the triggerbot
 * comes out really late").
 * <p>
 * Why not {@link ServerTickClock} or {@link ServerLag}: both take a server as ping-driven at 15 pings a second, and an
 * anticheat's transaction pings come close to that on their own. On the testkit's GrimAC server (about 15 a second)
 * ServerLag read the gaps between them as stalls, and a Blood Camp countdown built on it fell 11-15 ticks a second instead
 * of 20 (testkit 177 on doc-blood-1101's first jar: "74.8 -> 62.8 -> 47.8 -> 33.8 ticks left" a second apart). Here a
 * server is trusted only after a 20-client-tick window carrying at least {@link #TRUST_PINGS} pings, and stops being
 * trusted after two windows in a row short of it (one short window is what the stall itself looks like).
 * <p>
 * The clock is one count: each ping advances it while the server is trusted, each client tick while it is not. So it is
 * continuous across a change of source, a stall stops it, and a burst of pings after a network spike (the server kept
 * ticking) catches it up. {@link #stallTicksSince} is how far it fell behind the client's own ticks.
 */
public final class PerTickServerClock {

    private static final int WINDOW_TICKS = 20;
    /** Pings in a 20-tick window that make the server a per-tick pinger (Hypixel: 20; GrimAC alone: about 15). */
    static final int TRUST_PINGS = 18;

    private static volatile long clock;
    private static volatile long clientTicks;
    private static volatile boolean trusted;
    private static int windowPings;
    private static int windowTicks;
    private static int shortWindows;
    private static volatile int lastWindowPings = -1;
    private static boolean registered;
    private static Object lastLevel;

    private PerTickServerClock() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickClock.register();
        ServerTickClock.subscribeRawPing(PerTickServerClock::onPing);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("PerTickServerClock", client -> {
            if (client.level != lastLevel) {
                lastLevel = client.level;
                untrust();
            }
            onClientTick();
        }));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> untrust());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> untrust());
    }

    private static synchronized void untrust() {
        trusted = false;
        shortWindows = 0;
        windowPings = 0;
        windowTicks = 0;
    }

    private static synchronized void onPing() {
        windowPings++;
        if (trusted) {
            clock++;
        }
    }

    private static synchronized void onClientTick() {
        clientTicks++;
        if (!trusted) {
            clock++;
        }
        if (++windowTicks >= WINDOW_TICKS) {
            lastWindowPings = windowPings;
            if (windowPings >= TRUST_PINGS) {
                trusted = true;
                shortWindows = 0;
            } else if (trusted && ++shortWindows >= 2) {
                trusted = false;
                shortWindows = 0;
            }
            windowPings = 0;
            windowTicks = 0;
        }
    }

    /** The clock now (server ticks while trusted, client ticks otherwise). */
    public static long now() {
        return clock;
    }

    /** Both counters at once, for {@link #stallTicksSince}: {clock, clientTicks}. */
    public static synchronized long[] mark() {
        return new long[]{clock, clientTicks};
    }

    /** Client ticks since {@code mark} that the server did not tick (0 while it is not trusted), never negative. */
    public static synchronized long stallTicksSince(long[] mark) {
        if (mark == null) {
            return 0L;
        }
        return Math.max(0L, (clientTicks - mark[1]) - (clock - mark[0]));
    }

    public static boolean trusted() {
        return trusted;
    }

    /** Testkit: pings in the last full window. */
    public static int lastWindowPings() {
        return lastWindowPings;
    }
}
