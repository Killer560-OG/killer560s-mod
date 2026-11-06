package com.killer560.hub.util;

import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * A server-tick clock driven by the server's pings where they are one a tick. Every server-tick countdown (Tick Timers,
 * mask cooldowns, Blood Camp, ...) and Split Timers' lag run on it, so a server stall pauses them as it pauses the server.
 * <p>
 * <b>hypixel.net (tickfix3-1104).</b> Hypixel sends one ping a tick whose id steps by one (-642, -643, -644, ...) and,
 * between them, pings with random ids (his 2026-11-04 log, "first ping ids": [-642, -643, -26967, -380, -9038, -644]):
 * about 1.13 non-zero pings per 50 ms of wall time over a 48 s Goldor phase. Odin and NoammAddons count every non-zero ping;
 * here only the CHAIN counts ({@link #chain}), from the first ping after a join, with no trust to earn: the clock is trusted
 * at once. The chain is not one a tick either (goldor2-1104): his census lines read 0.99 to 1.18 CHAIN pings per 50 ms (the
 * extra transactions step the same counter), and his mask procs fell on exact 60-tick multiples of WALL time while the chain
 * ran 5% ahead (Goldor's line drifted 6, 10, 12, -13 ticks off them). So a chain ping counts only while the count stays within
 * {@link #HYPIXEL_SLACK} of the wall clock ({@link #withinWall}); a stall banks credit for its catch-up burst, at most
 * {@link #HYPIXEL_CREDIT} ticks (a server more than 2 s behind skips the rest, it does not catch them up). The wall clock
 * is not the server's clock, though (masktimer-1105, his 2026-11-05 run): after a 1.6 s stall that the server did not catch
 * up, the extras filled the banked credit and the count ran 12 ticks ahead of the server, so Goldor's line read 12 off his
 * next mask proc, and Storm's Purple counted its 62 ticks in 59.3 ticks of wall time after a 3.5 s stall. So the server's
 * own tick count, the game time of its time packet (once a second), holds the count ({@link #onServerTime}): a count
 * behind it by more than {@link #TIME_SLACK} moves up to it at once (ticks the cap or a frozen client held back), a count
 * ahead of it counts no chain ping until the wall clock has paid the lead back (never down). goldfix-1104's pace rule (pings ahead of the wall clock end the trust) read the random ids as a server pinging
 * faster than it ticks and untrusted real Hypixel, so Split Timers hid every lag figure and every timer ran on client ticks.
 * "Real" hypixel.net is the address AND a non-local remote: the testkit labels its loopback GrimAC server mc.hypixel.net,
 * and Grim's transaction ids form a chain too (-(counter &amp; 0x7FFF), a hit adds several), so they must never count.
 * <p>
 * <b>Any other server</b> is trusted only after two 20-client-tick windows in a row in which at least
 * {@link #TRUST_TICKS_WITH_PING} ticks saw a ping and no more than {@link #MAX_WINDOW_PINGS} came in all (GrimAC's ~15
 * transactions a second fail it; testkit 177, 792), loses it after two failing windows, and loses it for the rest of the
 * world once its pings run more than {@link #MAX_EXCESS} ahead of the wall clock (goldfix-1104: a quiet Grim server pings
 * about once a tick, then every hit adds transactions; 1102-hx-goldor-tick read the hits 5, 3, 7, 11 ticks late). A loaded
 * one banks lag below the wall clock and so never trips that rule: while trusted its pings count only within
 * {@link #HYPIXEL_SLACK} of the wall clock too ({@link #withinWall}, gold-flake-1105).
 * <p>
 * The clock is one count: each counted ping advances it while trusted; while not, it follows the client's game time (a
 * tick a tick, plus forward set-time corrections; never down), so it is continuous across a change of source.
 * {@link #deficitTicksSince} is how far it is behind the client's ticks (a catch-up burst pays it back);
 * {@link #stallTicksSince} counts every stall, paid back or not. Once per world, 60 s after its first ping, one INFO line
 * gives the ping census (ids 0 and non-zero, chain and off-chain, per 50 ms of wall time, chain direction).
 */
public final class PerTickServerClock {

    private static final int WINDOW_TICKS = 20;
    /** A good window: at least this many of its 20 client ticks saw a ping (a per-tick pinger: all of them)... */
    static final int TRUST_TICKS_WITH_PING = 19;
    /** ...and no more than this many pings in all (an anticheat's bursts of transactions are not ticks). */
    static final int MAX_WINDOW_PINGS = 24;
    /** More pings than this ahead of the wall clock is not network jitter (300 ms of pings). */
    static final double MAX_EXCESS = 6.0;
    /** A slow server or a stall banks at most this many ticks of credit (a 10 s hold, then the burst that pays it back). */
    static final double EXCESS_FLOOR = 200.0;
    /** hypixel.net: a chain ping that would put the count more than this ahead of the wall clock is an extra transaction. */
    static final double HYPIXEL_SLACK = 1.5;
    /** hypixel.net: credit a stall banks for its catch-up burst (vanilla catches up at most 2 s of ticks, then skips). */
    static final double HYPIXEL_CREDIT = 40.0;
    /** A rise of the trusted deficit (client ticks minus pings) of more than this many ticks over its low point is a stall;
     *  one or two is arrival jitter (a late ping lands in the next tick). Split Timers' 150 ms rule, in ticks. */
    static final int STALL_TICKS = 2;
    private static final long CENSUS_NANOS = 60_000_000_000L;
    /** hypixel.net: the count may differ from the server's game time by this much (whether a tick's ping or its time packet
     *  comes first) before {@link #onServerTime} moves it. */
    static final long TIME_SLACK = 1L;
    /** A game time this far past the last one is not the next second of the same world: map the count to it afresh. */
    private static final long TIME_GAP = 400L;

    private static volatile long clock;
    private static volatile boolean trusted;
    private static volatile int trustChanges;
    private static volatile long rawPings;
    private static volatile int lastWindowPings = -1;
    private static volatile boolean notPerTick;
    private static boolean registered;
    private static boolean hypixel;
    private static Boolean forcedHypixel;
    private static Object lastLevel;
    private static long lastGameTime;
    private static int windowTicks;
    private static int windowPings;
    private static int windowTicksWithPing;
    private static int pingsThisTick;
    private static int goodWindows;
    private static int shortWindows;
    /** Client ticks and pings counted while trusted: the stall is the ticks the pings did not keep up with. */
    private static long trustedTicks;
    private static long trustedPings;
    /** The trusted deficit's low point since the last counted stall, every stall tick counted, and whether one is rising. */
    private static long deficitLow;
    private static long stallTicksTotal;
    private static boolean inStall;
    /** Pings minus elapsed 50 ms steps in this world (non-Hypixel only), floored at -{@link #EXCESS_FLOOR}. */
    private static double excess;
    /** How far the counted ticks are ahead of the wall clock ({@link #withinWall}); negative is banked catch-up credit. */
    private static double wallExcess;
    private static long lastPingNanos = Long.MIN_VALUE;
    /** The per-tick chain's last id, and the last id off it (two in a row there means the chain was that one). */
    private static int chainId;
    private static int otherId;
    private static boolean chainSet;
    private static boolean otherSet;
    /** This world's ping census: {non-zero, in the chain, id 0, chain steps down, chain steps up, time packets, ticks the
     *  time packets moved the count up, ticks they held it back}. */
    private static final long[] CENSUS = new long[8];
    /** hypixel.net: the count minus the server's game time, fixed at this world's first time packet, and the last one. */
    private static boolean timeSet;
    private static long timeOffset;
    private static long lastServerTime;
    private static long censusStart = Long.MIN_VALUE;
    private static boolean censusLogged;
    /** The clock and the client's (server-set) game time when the census began. */
    private static long censusClock;
    private static long censusGameTime;
    private static int testChainId = -100;
    private static long testNanos;

    private static final java.util.List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-servertick");

    private PerTickServerClock() {
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ServerTickClock.register(); // its ping mixin calls onPingId
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("PerTickServerClock", client -> {
            long gameTime = client.level == null ? lastGameTime : client.level.getGameTime();
            long delta = gameTime - lastGameTime;
            if (client.level != lastLevel) {
                lastLevel = client.level;
                untrust();
                delta = 1L; // a new world's game time says nothing about the old one's
            }
            lastGameTime = gameTime;
            // A wild jump (a /time command) counts as one tick, a correction BACKWARDS as none (2026-11-03, timers2).
            onClientTick(Math.abs(delta) > 100L ? 1L : Math.max(0L, delta));
        }));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            untrust();
            setHypixel(forcedHypixel != null ? forcedHypixel : AttackCooldown.onHypixel(client)
                    && !isLocal(handler.getConnection().getRemoteAddress()));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            untrust();
            setHypixel(forcedHypixel != null && forcedHypixel);
        });
    }

    private static boolean isLocal(java.net.SocketAddress address) {
        if (!(address instanceof java.net.InetSocketAddress inet) || inet.getAddress() == null) {
            return true; // an in-memory (singleplayer) connection or an unresolved one
        }
        java.net.InetAddress a = inet.getAddress();
        return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress();
    }

    private static synchronized void setHypixel(boolean on) {
        hypixel = on;
    }

    /** Testkit (logic suite): act as if connected to the real hypixel.net (true), not (false), or by the connection (null).
     *  While true every ping is timed on the test's own wall clock ({@link #tickForTest} moves it 50 ms), so hand-driven
     *  ticks run at 20 a second and the wall cap ({@link #withinWall}) judges them as it would a real stream. */
    public static synchronized void hypixelForTest(Boolean on) {
        forcedHypixel = on;
        hypixel = on != null && on;
    }

    private static synchronized void untrust() {
        trusted = false;
        shortWindows = 0;
        windowPings = 0;
        windowTicks = 0;
        windowTicksWithPing = 0;
        pingsThisTick = 0;
        goodWindows = 0;
        inStall = false;
        deficitLow = trustedTicks - trustedPings;
        excess = 0.0;
        wallExcess = 0.0;
        lastPingNanos = Long.MIN_VALUE;
        notPerTick = false;
        chainSet = false;
        otherSet = false;
        java.util.Arrays.fill(CENSUS, 0L);
        censusStart = Long.MIN_VALUE;
        censusLogged = false;
        timeSet = false;
    }

    /** From the ping mixin (through {@link ServerTickClock#onPing}), on the client thread, for every ping id. */
    public static void onPingId(int id) {
        onPing(id, Boolean.TRUE.equals(forcedHypixel) ? testNanos : System.nanoTime());
    }

    private static void onPing(int id, long nanos) {
        boolean advanced;
        synchronized (PerTickServerClock.class) {
            boolean inChain = id != 0 && chain(id);
            census(id, inChain, nanos);
            if (id == 0) {
                return; // not a tick anywhere (Odin, NoammAddons)
            }
            rawPings++;
            windowPings++;
            pingsThisTick++;
            offChain = hypixel && !inChain;
            double since = since(nanos);
            if (hypixel) {
                if (!trusted) {
                    trusted = true;
                    trustChanges++;
                }
                advanced = inChain && withinWall(since);
            } else {
                checkPace(since);
                advanced = trusted && withinWall(since);
            }
            if (advanced) {
                clock++;
                trustedPings++;
            }
        }
        if (advanced) {
            fire(1);
        }
    }

    /** Whether {@code id} continues the per-tick chain: a step of 1-3 from its last id (either way; past +-32767 it wraps
     *  to 1). The first ping starts it; an off-chain id followed by its own next step means the chain was that one. */
    private static boolean chain(int id) {
        if (!chainSet || next(id, chainId)) {
            if (chainSet) {
                CENSUS[id < chainId ? 3 : 4]++;
            }
            chainId = id;
            chainSet = true;
            return true;
        }
        if (otherSet && next(id, otherId)) {
            chainId = id;
            otherSet = false;
            return true;
        }
        otherId = id;
        otherSet = true;
        return false;
    }

    private static boolean next(int id, int last) {
        long d = Math.abs((long) id - last);
        return d >= 1 && d <= 3 || Math.abs((long) last) >= 0x7FFF && Math.abs((long) id) <= 3;
    }

    private static void census(int id, boolean inChain, long nanos) {
        CENSUS[id == 0 ? 2 : 0]++;
        if (inChain) {
            CENSUS[1]++;
        }
        if (censusLogged || nanos == Long.MIN_VALUE) {
            return;
        }
        if (censusStart == Long.MIN_VALUE) {
            censusStart = nanos;
            censusClock = clock;
            censusGameTime = lastGameTime;
        } else if (nanos - censusStart >= CENSUS_NANOS) {
            censusLogged = true;
            double steps = (nanos - censusStart) / 50_000_000.0;
            LOGGER.info("[PerTickServerClock] ping census, first 60 s of this world ({}): {} non-zero ids ({} in the per-tick"
                    + " chain, {} off it), {} with id 0; {} pings and {} chain pings per 50 ms; chain steps {} down, {} up;"
                    + " {} ticks counted and the server-set game time +{} in {} ticks of wall time; {} time packets moved"
                    + " the count up {} ticks and held it back {}",
                    hypixel ? "hypixel.net, chain counted" : "not hypixel.net", CENSUS[0], CENSUS[1], CENSUS[0] - CENSUS[1],
                    CENSUS[2], String.format(java.util.Locale.ROOT, "%.3f", CENSUS[0] / steps),
                    String.format(java.util.Locale.ROOT, "%.3f", CENSUS[1] / steps), CENSUS[3], CENSUS[4],
                    clock - censusClock, lastGameTime - censusGameTime, String.format(java.util.Locale.ROOT, "%.1f", steps),
                    CENSUS[5], CENSUS[6], CENSUS[7]);
        }
    }

    /** Ticks of wall time since the last paced ping; -1 for the first ping and for an unpaced test ping
     *  ({@link #pingForTest}), which move no pace. */
    private static double since(long nanos) {
        if (nanos == Long.MIN_VALUE) {
            return -1.0;
        }
        boolean first = lastPingNanos == Long.MIN_VALUE || nanos < lastPingNanos;
        double since = first ? -1.0 : (nanos - lastPingNanos) / 50_000_000.0;
        lastPingNanos = nanos;
        return since;
    }

    /** A ping the clock would count: whether it does, i.e. the count stays within {@link #HYPIXEL_SLACK} of the wall
     *  clock. Every server, not only hypixel.net (gold-flake-1105): a loaded GrimAC server earns the trust (a slow client
     *  sees a ping every tick) and its per-hit transactions then ran Goldor's line 4 ticks ahead a cycle. */
    private static boolean withinWall(double since) {
        if (since >= 0.0) {
            wallExcess = Math.max(-HYPIXEL_CREDIT, wallExcess - since);
            if (wallExcess + 1.0 > HYPIXEL_SLACK) {
                return false;
            }
            wallExcess += 1.0;
        }
        return true;
    }

    /** From the time packet's handler (once a second on a vanilla-style server), on the client thread: the server's game
     *  time, i.e. its own tick count. hypixel.net only (see the class doc). */
    public static void onServerTime(long gameTime) {
        onServerTime(gameTime, Boolean.TRUE.equals(forcedHypixel) ? testNanos : System.nanoTime());
    }

    private static void onServerTime(long gameTime, long nanos) {
        int up;
        synchronized (PerTickServerClock.class) {
            up = serverTimeLocked(gameTime, nanos);
        }
        if (up > 0) {
            fire(up);
        }
    }

    private static int serverTimeLocked(long gameTime, long nanos) {
        if (!hypixel || !trusted) {
            return 0;
        }
        if (!timeSet || gameTime < lastServerTime || gameTime - lastServerTime > TIME_GAP) {
            timeSet = true;
            timeOffset = clock - gameTime;
            lastServerTime = gameTime;
            return 0;
        }
        lastServerTime = gameTime;
        CENSUS[5]++;
        since(nanos); // the next chain ping's wall time starts here; the lead below replaces the bank
        long behind = gameTime + timeOffset - clock;
        int up = 0;
        if (behind > TIME_SLACK) {
            up = (int) Math.min(behind, 100L);
            clock += up;
            trustedPings += up;
            CENSUS[6] += up;
        } else if (behind < -TIME_SLACK) {
            CENSUS[7] -= behind;
        }
        if (Math.abs(behind) > 2L) {
            LOGGER.info("[PerTickServerClock] hypixel.net's game time: the count was {} ticks {} it ({})", Math.abs(behind),
                    behind > 0 ? "behind" : "ahead of", behind > 0 ? "moved up" : "held until the wall clock pays it back");
        }
        wallExcess = clock - (gameTime + timeOffset); // a lead holds the chain back (withinWall); a lag was just paid
        return up;
    }

    /** Non-Hypixel, every ping: {@link #excess}, and the end of trust for a server that pings faster than it ticks. */
    private static void checkPace(double since) {
        if (since < 0.0) {
            return;
        }
        excess = Math.max(-EXCESS_FLOOR, excess - since) + 1.0;
        if (excess > MAX_EXCESS && !notPerTick) {
            notPerTick = true;
            goodWindows = 0;
            boolean was = trusted;
            trusted = false;
            if (was) {
                trustChanges++;
            }
            LOGGER.info("[PerTickServerClock] server is not a per-tick pinger: its pings ran {} ahead of the wall clock{}",
                    String.format(java.util.Locale.ROOT, "%.1f", excess), was ? " (no longer trusted)" : "");
        }
    }

    /** At a trusted client tick's end: count the deficit's rise as a stall once it passes {@link #STALL_TICKS}, and keep
     *  counting while it goes on rising. A catch-up burst lowers the low point and pays nothing back (tickfix2-1104). */
    private static void countStall() {
        long deficit = trustedTicks - trustedPings;
        long rise = deficit - deficitLow;
        if (rise > STALL_TICKS || (inStall && rise > 0)) {
            stallTicksTotal += rise;
            deficitLow = deficit;
            inStall = true;
        } else if (rise <= 0) {
            deficitLow = Math.min(deficitLow, deficit);
            inStall = false;
        }
    }

    private static void onClientTick(long untrustedStep) {
        long advanced;
        synchronized (PerTickServerClock.class) {
            advanced = clientTickLocked(untrustedStep);
        }
        if (advanced > 0L) {
            fire((int) Math.min(advanced, 100L));
        }
    }

    /** Fires every {@link #subscribe} listener {@code times} times, outside the lock; one broken listener stops no other. */
    private static void fire(int times) {
        for (int i = 0; i < times; i++) {
            for (Runnable listener : LISTENERS) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    LOGGER.warn("[PerTickServerClock] listener failed", e);
                }
            }
        }
    }

    /**
     * Runs {@code listener} once per server tick of this clock, on the client thread: a counted ping while the server is
     * trusted, a tick of the client's game time otherwise, so a stalled server stops it and a catch-up burst runs it that
     * many times. Call {@link #register()} first.
     */
    public static void subscribe(Runnable listener) { LISTENERS.add(listener); }

    /**
     * For a loop that already runs once per client tick: {@link #take()} is how many server ticks the clock moved since
     * the last call (0 when the server stalled, 2 when a burst caught up), so {@code timer -= step.take()} replaces
     * {@code timer--}. Call it every client tick, even while idle, so the first step of a new timer is not a stale gap.
     */
    public static final class Step {
        private long last = Long.MIN_VALUE;

        public int take() {
            long now = clock;
            long delta = last == Long.MIN_VALUE ? 1L : now - last;
            last = now;
            return (int) Math.max(0L, Math.min(100L, delta));
        }

        /** Ticks the next {@link #take} will return so far: a timer set now adds them, so the ticks before it are not
         *  taken from it (a mask proc set mid-tick read 1 tick short, masktimer-1105). */
        public int pending() {
            return last == Long.MIN_VALUE ? 0 : (int) Math.max(0L, Math.min(100L, clock - last));
        }
    }

    private static synchronized long clientTickLocked(long untrustedStep) {
        long before = clock;
        if (!trusted) {
            clock += untrustedStep;
        } else {
            trustedTicks++;
            countStall();
        }
        if (pingsThisTick > 0) {
            windowTicksWithPing++;
        }
        pingsThisTick = 0;
        if (++windowTicks >= WINDOW_TICKS) {
            lastWindowPings = windowPings;
            if (!hypixel) {
                judgeWindow();
            }
            windowPings = 0;
            windowTicks = 0;
            windowTicksWithPing = 0;
        }
        return clock - before;
    }

    /** Non-Hypixel: two good windows in a row earn the trust, two failing ones end it. */
    private static void judgeWindow() {
        boolean was = trusted;
        if (windowTicksWithPing >= TRUST_TICKS_WITH_PING && windowPings <= MAX_WINDOW_PINGS) {
            shortWindows = 0;
            if (++goodWindows >= 2 && !notPerTick) {
                if (!trusted) {
                    wallExcess = 0.0; // the lag banked while untrusted is no credit for the trusted count
                }
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
    }

    /** The clock now (server ticks while trusted, client ticks otherwise). */
    public static long now() { return clock; }

    /** The counters at once, for {@link #stallTicksSince} and {@link #deficitTicksSince}: {pings while trusted, client
     *  ticks while trusted, stall ticks counted}. */
    public static synchronized long[] mark() {
        return new long[]{trustedPings, trustedTicks, stallTicksTotal};
    }

    /** The server's stalls since {@code mark}, in client ticks (0 while it is not trusted): every rise of more than
     *  {@link #STALL_TICKS} counts in full and stays counted when a burst of catch-up pings follows. For a lag READING. */
    public static synchronized long stallTicksSince(long[] mark) {
        if (mark == null || mark.length < 3) {
            return 0L;
        }
        return Math.max(0L, stallTicksTotal - mark[2]);
    }

    /** Client ticks since {@code mark} the server has not (yet) made up: client ticks minus pings while trusted, never
     *  negative, so a catch-up burst cancels the stall before it. For turning a server-tick target into a client tick. */
    public static synchronized long deficitTicksSince(long[] mark) {
        if (mark == null) {
            return 0L;
        }
        return Math.max(0L, (trustedTicks - mark[1]) - (trustedPings - mark[0]));
    }

    /** Every non-zero ping seen since launch, counted or not (Tick Timers' proc log compares it with the clock). */
    public static long rawPings() {
        return rawPings;
    }

    public static boolean trusted() {
        return trusted;
    }

    /** Whether this is the real hypixel.net (its chain counted, held to its game time), not a test or other server. */
    public static synchronized boolean onHypixel() {
        return hypixel;
    }

    /** Whether the ping being handled now is one of hypixel.net's random-id pings, not a tick (for ServerTickClock's raw
     *  ping listeners, which run right after {@link #onPingId}: ServerLag skips it, or it would split a stall's gap). */
    public static boolean offChain() {
        return offChain;
    }

    private static volatile boolean offChain;

    /** Testkit (logic suite, no server): a per-tick ping (the next id of a test chain), not paced. */
    public static void pingForTest() {
        onPing(testChainId--, Long.MIN_VALUE);
    }

    /** Testkit: a ping with this id (a Hypixel-style random id is off the chain), not paced. */
    public static void pingForTestId(int id) {
        onPing(id, Long.MIN_VALUE);
    }

    /** Testkit: a per-tick ping timed on the test's own wall clock ({@link #tickForTest} moves it 50 ms), so the pace check
     *  sees it. {@link #pingForTest} is not paced: cases push the clock ahead with hundreds of pings at once. */
    public static void pingForTestTimed() {
        onPing(testChainId--, testNanos);
    }

    /** Testkit: the server's time packet with this game time, on the test's own wall clock. */
    public static void serverTimeForTest(long gameTime) {
        onServerTime(gameTime, testNanos);
    }

    /** A client tick, and 50 ms of the test's own wall clock. */
    public static void tickForTest() {
        testNanos += 50_000_000L;
        onClientTick(1L);
    }

    /** Testkit: whether this world's server was caught pinging faster than it can tick. */
    public static boolean notPerTick() {
        return notPerTick;
    }

    public static void untrustForTest() {
        untrust();
    }

    /** Testkit: times the trust changed. */
    public static int trustChanges() { return trustChanges; }

    /** Testkit: pings in the last full window. */
    public static int lastWindowPings() { return lastWindowPings; }
}
