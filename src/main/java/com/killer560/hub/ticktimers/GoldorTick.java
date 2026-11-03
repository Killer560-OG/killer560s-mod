package com.killer560.hub.ticktimers;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.PerTickServerClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Goldor's damage tick in F7/M7 Phase 3 (the "Tick:" line of Tick Timers): every {@link #PERIOD} server ticks Goldor hits
 * every player, and that hit is what pops a Spirit/Bonzo mask or a Phoenix.
 * <p>
 * <b>2026-11-02 (killer560: "it procced my mask at like 1.5s instead of at 0").</b> The old line was a counter set to 60 on
 * Goldor's line and decremented once per {@code ServerTickClock} event. That event counts CLIENT ticks in any second the
 * server sent fewer than 15 pings (a server under 15 TPS, or pings bunched by the network), and the decrement was skipped
 * whenever the sidebar did not read F7 for a tick, so the counter drifted off the server's real 60-tick cycle and nothing
 * ever pulled it back. Now:
 * <ul>
 *   <li>the line is worked out from one remembered server tick of a Goldor hit ({@link #anchor}) and the server-tick clock
 *       ({@link PerTickServerClock}: Hypixel's per-tick pings when it pings every tick, else the client's game time with the
 *       server's own time corrections), so a missed callback cannot move it and lag cannot run it ahead;</li>
 *   <li>it starts at Goldor's line + 60 (Odin's and NoammAddons' phase, which a real 2026-10-30 log confirms: the party's
 *       mask procs came 3 and 6 seconds after his line);</li>
 *   <li>every observed hit resyncs it: a drop in your own health, a mask/Phoenix proc line, or two or more players hurt in
 *       the same tick. A hit within {@link #SNAP} ticks of the predicted one moves the phase onto it; a hit that does not
 *       fit is remembered, and a second one a whole number of periods after it (within one tick) re-locks the phase there,
 *       as does a hit that lands on two or more players at once. A stray hit (a mob, a fall) moves nothing.</li>
 * </ul>
 * The line reads 0 at the hit's tick and 59 ticks (2.95 s) the tick after.
 */
public final class GoldorTick {

    private static final Logger LOGGER = ModLog.get("killer560smod-ticktimers");

    public static final int PERIOD = 60;
    /** A hit this close to the predicted tick is the tick: the phase moves onto it. */
    static final int SNAP = 3;
    /** Hits this soon after an accepted one are the same hit (a second health packet, the proc line). */
    static final int DEBOUNCE = 5;

    private static boolean active;
    /** Clock tick of a Goldor hit, real or (until the first one is seen) predicted. */
    private static long anchor;
    private static long lastAccepted = Long.MIN_VALUE;
    /** The last hit that did not fit the phase, or MIN. */
    private static long unexplained = Long.MIN_VALUE;
    private static float lastHealth = Float.NaN;
    private static final Map<Integer, Integer> HURT = new HashMap<>();

    // Test hooks: what the line read (signed ticks past the predicted tick) at each observed hit, and resync counts.
    private static final List<Integer> OFFSETS_AT_HITS = new ArrayList<>();
    private static int relocks;

    private GoldorTick() {
    }

    static long now() {
        return PerTickServerClock.now();
    }

    /** Goldor's opening line: his first hit is one period later. */
    static synchronized void start() {
        active = true;
        anchor = now() + PERIOD;
        lastAccepted = Long.MIN_VALUE;
        unexplained = Long.MIN_VALUE;
        lastHealth = Float.NaN;
        HURT.clear();
        OFFSETS_AT_HITS.clear();
        relocks = 0;
    }

    static synchronized void stop() {
        active = false;
        lastHealth = Float.NaN;
        HURT.clear();
    }

    public static boolean active() {
        return active;
    }

    /** Ticks until Goldor's next hit: 0 on the hit's own tick, 59 the tick after, and 60 on Goldor's line itself. */
    public static synchronized int remaining() {
        long d = anchor - now();
        if (d > 0) {
            return (int) ((d - 1) % PERIOD) + 1;
        }
        return (int) Math.floorMod(d, (long) PERIOD);
    }

    /** How far {@code t} is past the predicted hit, -30..29. */
    private static int offset(long t) {
        return (int) (Math.floorMod(t - anchor + PERIOD / 2, (long) PERIOD) - PERIOD / 2);
    }

    /** A hit seen at server tick {@code t}; {@code strong} = it landed on two or more players in the same tick. */
    static synchronized void onHit(long t, boolean strong, String why) {
        if (!active) {
            return;
        }
        if (lastAccepted != Long.MIN_VALUE && t - lastAccepted >= 0 && t - lastAccepted <= DEBOUNCE) {
            return;
        }
        int off = offset(t);
        OFFSETS_AT_HITS.add(off);
        if (OFFSETS_AT_HITS.size() > 32) {
            OFFSETS_AT_HITS.remove(0);
        }
        boolean fits = Math.abs(off) <= SNAP;
        boolean pair = false;
        if (unexplained != Long.MIN_VALUE) {
            long gap = t - unexplained;
            long rem = Math.floorMod(gap + 1, (long) PERIOD);
            pair = gap >= PERIOD - 1 && gap <= 3L * PERIOD + 1 && rem <= 2;
        }
        if (fits || strong || pair) {
            if (!fits) {
                relocks++;
                LOGGER.info("[TickTimers] Goldor tick re-locked by {} ({} ticks off the old phase{})", why, off,
                        pair ? ", a period after an earlier hit" : "");
            }
            anchor = t;
            lastAccepted = t;
            unexplained = Long.MIN_VALUE;
        } else {
            unexplained = t;
        }
    }

    /** Client tick: your own health dropping, and other players' hurt animations starting, are hits. */
    static void clientTick(Minecraft client) {
        if (!active || client.player == null || client.level == null) {
            lastHealth = Float.NaN;
            return;
        }
        float h = client.player.getHealth();
        boolean self = !Float.isNaN(lastHealth) && h < lastHealth - 0.01f;
        lastHealth = h;
        int others = 0;
        for (AbstractClientPlayer p : client.level.players()) {
            if (p == client.player) {
                continue;
            }
            Integer prev = HURT.put(p.getId(), p.hurtTime);
            if (prev != null && p.hurtTime > prev) {
                others++;
            }
        }
        int hit = (self ? 1 : 0) + others;
        // A lone teammate's hurt could be anything; your own health drop, or two players at once, is a hit.
        if (self || hit >= 2) {
            onHit(now(), hit >= 2, self ? (others > 0 ? "your health and " + others + " teammate(s)" : "your health")
                    : others + " players hurt at once");
        }
    }

    /** A mask or Phoenix proc line: you were just hit for lethal damage, in P3 that is Goldor's tick. */
    static void onProc() {
        onHit(now(), false, "a mask proc");
    }

    // ---- test hooks --------------------------------------------------------------------------------------------------

    public static synchronized List<Integer> testOffsetsAtHits() {
        return new ArrayList<>(OFFSETS_AT_HITS);
    }

    public static synchronized int testRelocks() {
        return relocks;
    }

    public static synchronized long testAnchor() {
        return anchor;
    }

    /** Testkit: a hit at the clock's current tick, as the client would observe it. */
    public static void testHitNow(boolean strong) {
        onHit(now(), strong, "the testkit");
    }
}
