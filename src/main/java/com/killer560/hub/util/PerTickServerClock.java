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
 * server is trusted only after two 20-client-tick windows in a row in which at least {@link #TRUST_TICKS_WITH_PING}
 * ticks saw a ping and no more than {@link #MAX_WINDOW_PINGS} came in all, and stops being trusted after two windows in a
 * row that fail it (one failing window is what a stall itself looks like). The first rule, at 18 pings a window, still
 * trusted GrimAC now and then and ran testkit 792's first trip 31 ticks slow.
 * <p>
 * The clock is one count: each ping advances it while the server is trusted; while it is not, it follows the client's
 * game time (a tick a tick, plus the server's set-time corrections), exactly the clock Blood Camp used before - testkit 392
 * (singleplayer, whose game time outran the client's ticks by 12 in a trip) failed on plain client ticks. So it is
 * continuous across a change of source, a stall stops it, and a burst of pings after a network spike (the server kept
 * ticking) catches it up. {@link #stallTicksSince} is how far it fell behind the client's own ticks.
 */
public final class PerTickServerClock {

    private static final int WINDOW_TICKS = 20;
    /** A good window: at least this many of its 20 client ticks saw a ping (a per-tick pinger: all of them)... */
    static final int TRUST_TICKS_WITH_PING = 19;
    /** ...and no more than this many pings in all (an anticheat's bursts of transactions are not ticks). */
    static final int MAX_WINDOW_PINGS = 24;
    private static int windowTicksWithPing;
    private static int pingsThisTick;
    private static int goodWindows;
    private static volatile int trustChanges;

    private static volatile long clock;
    private static volatile long clientTicks;
    private static volatile boolean trusted;
    private static int windowPings;
    private static int windowTicks;
    private static int shortWindows;
    private static volatile int lastWindowPings = -1;
    private static boolean registered;
    private static Object lastLevel;
    /** Client ticks and pings counted while trusted: the stall is the ticks the pings did not keep up with. */
    private static long trustedTicks;
    private static long trustedPings;
    private static long lastGameTime;

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
            long gameTime = client.level == null ? lastGameTime : client.level.getGameTime();
            long delta = gameTime - lastGameTime;
            if (client.level != lastLevel) {
                lastLevel = client.level;
                untrust();
                delta = 1L; // a new world's game time says nothing about the old one's
            }
            lastGameTime = gameTime;
            // Untrusted, the clock follows the client's game time, as Blood Camp always did: a tick a tick, plus the
            // server's own time corrections (set-time packets). A wild jump (a /time command) counts as one tick.
            onClientTick(Math.abs(delta) > 100L ? 1L : delta);
        }));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> untrust());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> untrust());
    }

    private static synchronized void untrust() {
        trusted = false;
        shortWindows = 0;
        windowPings = 0;
        windowTicks = 0;
        windowTicksWithPing = 0;
        pingsThisTick = 0;
        goodWindows = 0;
    }

    private static synchronized void onPing() {
        windowPings++;
        pingsThisTick++;
        if (trusted) {
            clock++;
            trustedPings++;
        }
    }

    private static synchronized void onClientTick(long untrustedStep) {
        clientTicks++;
        if (!trusted) {
            clock += untrustedStep;
        } else {
            trustedTicks++;
        }
        if (pingsThisTick > 0) {
            windowTicksWithPing++;
        }
        pingsThisTick = 0;
        if (++windowTicks >= WINDOW_TICKS) {
            lastWindowPings = windowPings;
            boolean good = windowTicksWithPing >= TRUST_TICKS_WITH_PING && windowPings <= MAX_WINDOW_PINGS;
            boolean was = trusted;
            if (good) {
                shortWindows = 0;
                if (++goodWindows >= 2) {
                    trusted = true;
                }
            } else {
                goodWindows = 0;
                if (trusted && ++shortWindows >= 2) {
                    trusted = false;
                    shortWindows = 0;
                }
            }
            if (was != trusted) {
                trustChanges++;
                LOGGER.info("[PerTickServerClock] server {} per-tick pinger: {} pings, {} of {} ticks with a ping", trusted
                        ? "is a" : "is no longer a", windowPings, windowTicksWithPing, WINDOW_TICKS);
            }
            windowPings = 0;
            windowTicks = 0;
            windowTicksWithPing = 0;
        }
    }

    /** The clock now (server ticks while trusted, client ticks otherwise). */
    public static long now() {
        return clock;
    }

    /** Both counters at once, for {@link #stallTicksSince}: {pings while trusted, client ticks while trusted}. */
    public static synchronized long[] mark() {
        return new long[]{trustedPings, trustedTicks};
    }

    /** Client ticks since {@code mark} that the server did not tick (0 while it is not trusted), never negative. */
    public static synchronized long stallTicksSince(long[] mark) {
        if (mark == null) {
            return 0L;
        }
        return Math.max(0L, (trustedTicks - mark[1]) - (trustedPings - mark[0]));
    }

    public static boolean trusted() {
        return trusted;
    }

    /** Testkit (logic suite, no server): drive the clock by hand - a server ping, a client tick, a fresh world. */
    public static void pingForTest() {
        onPing();
    }

    public static void tickForTest() {
        onClientTick(1L);
    }

    public static void untrustForTest() {
        untrust();
    }

    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-servertick");

    /** Testkit: times the trust changed. */
    public static int trustChanges() {
        return trustChanges;
    }

    /** Testkit: pings in the last full window. */
    public static int lastWindowPings() {
        return lastWindowPings;
    }
}
