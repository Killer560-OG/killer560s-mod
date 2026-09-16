package com.killer560.hub.witherdragons;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-tick clock - Odin's {@code TickEvent.Server}. Odin's {@code ConnectionMixin} posts one server tick per
 * {@code ClientboundPingPacket} whose id is non-zero (Hypixel sends one every server tick), source:
 * https://github.com/odtheking/Odin/blob/main/src/main/java/com/odtheking/mixin/mixins/ConnectionMixin.java
 * <p>
 * Here {@code WitherDragonsPingMixin} feeds {@link #onPing(int)} on the client thread (right after
 * {@code ensureRunningOnSameThread}, so listeners never run on the netty thread). Servers that don't send
 * per-tick pings (p3sim.net is unverified) fall back to client ticks: if no ping arrived in the last
 * {@link #PING_TIMEOUT_MS}, every END_CLIENT_TICK counts as a server tick instead, so timers never freeze.
 * Used by the Wither Dragons / King Relic countdowns and (since this batch) Tick Timers.
 */
public final class ServerTickClock {

    private static final long PING_TIMEOUT_MS = 1500L;

    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static long lastPingMs = 0L;
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
            if (System.currentTimeMillis() - lastPingMs > PING_TIMEOUT_MS) {
                fire();
            }
        });
    }

    public static synchronized void subscribe(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** Called from the ping mixin on the client thread. */
    public static void onPing(int id) {
        if (id == 0) {
            return;
        }
        lastPingMs = System.currentTimeMillis();
        fire();
    }

    /** Server ticks counted since launch (ping-driven or client-tick fallback). */
    public static long now() {
        return totalTicks;
    }

    /** True while server pings are driving the clock (false = client-tick fallback). */
    public static boolean isPingDriven() {
        return System.currentTimeMillis() - lastPingMs <= PING_TIMEOUT_MS;
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
