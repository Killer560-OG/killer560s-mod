package com.killer560.hub.witherdragons;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-tick clock - Odin's {@code TickEvent.Server}. Odin's {@code ConnectionMixin} posts one server tick per
 * {@code ClientboundPingPacket} whose id is non-zero (Hypixel sends one every server tick), source:
 * https://github.com/odtheking/Odin/blob/main/src/main/java/com/odtheking/mixin/mixins/ConnectionMixin.java
 * <p>
 * Here {@code WitherDragonsPingMixin} feeds {@link #onPing(int)} on the client thread (right after
 * {@code ensureRunningOnSameThread}, so listeners never run on the netty thread).
 * <p>
 * The two sources are EXCLUSIVE, never additive: every second the client tick closes a window and decides which
 * one drives the clock. Ping-driven only while the last window carried at least {@link #MIN_PINGS_PER_WINDOW}
 * pings (i.e. the server really does ping ~20x/s); anything slower - a server that pings once a second, or a
 * Hypixel lag spike - falls back to counting client ticks, so the clock keeps running at ~20/s and the existing
 * Simon Says / Goldor terminal timing can neither stall nor be double-counted when a backlog of pings lands at
 * once. Used by the Wither Dragons / King Relic countdowns and (since this batch) Tick Timers.
 */
public final class ServerTickClock {

    /** Length of the ping-rate sample window. */
    private static final long WINDOW_MS = 1000L;
    /** Pings needed inside one window to trust the server's ping cadence as one-per-server-tick. */
    private static final int MIN_PINGS_PER_WINDOW = 15;

    // CopyOnWriteArrayList: fire() iterates while a late subscribe() could still add a listener.
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static long windowStartMs = 0L;
    private static int pingsInWindow = 0;
    private static boolean pingDriven = false;
    private static long totalTicks = 0L;
    private static boolean registered = false;

    private ServerTickClock() {
    }

    /** Idempotent - both Wither Dragons and Tick Timers call this from their own register(). */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            rollWindow();
            if (!pingDriven) {
                fire();
            }
        });
    }

    public static void subscribe(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** Called from the ping mixin on the client thread. */
    public static void onPing(int id) {
        if (id == 0) {
            return;
        }
        pingsInWindow++;
        if (pingDriven) {
            fire();
        }
    }

    /** Server ticks counted since launch (ping-driven or client-tick fallback). */
    public static long now() {
        return totalTicks;
    }

    /** True while server pings are driving the clock (false = client-tick fallback). */
    public static boolean isPingDriven() {
        return pingDriven;
    }

    /** Client thread only: closes the sample window once a second and picks the clock source for the next one. */
    private static void rollWindow() {
        long now = System.currentTimeMillis();
        if (windowStartMs == 0L) {
            windowStartMs = now;
            pingsInWindow = 0;
            return;
        }
        if (now - windowStartMs >= WINDOW_MS) {
            pingDriven = pingsInWindow >= MIN_PINGS_PER_WINDOW;
            windowStartMs = now;
            pingsInWindow = 0;
        }
    }

    private static void fire() {
        totalTicks++;
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // one broken listener must never stop the others (or packet handling)
            }
        }
    }
}
