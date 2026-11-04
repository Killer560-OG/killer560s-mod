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
 *   <li>it starts at Goldor's line + {@link #FIRST_HIT} ticks, and the FIRST hit seen after it snaps the phase at once
 *       (see below). <b>2026-11-03 (killer560: "i will enter and it procs a mask at like 1s instead of at 0").</b> The
 *       line + 60 start (Odin's and NoammAddons') left the line 18 ticks late on his real run (26.3 log, jar fa0bb087b):
 *       the mod re-locked "-18 ticks off the old phase" at the first confirmed pair of hits, 27 s in, and his mask had
 *       popped at about 1.0 s on the old line each time before that. So the line now starts 18 ticks earlier, and one
 *       hit of his own health (or a mask proc) at least {@link #MIN_FIRST_HIT} ticks after the line is enough to snap
 *       it, wherever it lands; the two-hit and two-player protections below apply from the second hit on;</li>
 *   <li>every observed hit resyncs it: a drop in your own health, a mask/Phoenix proc line, or two or more players hurt in
 *       the same tick. A hit within {@link #SNAP} ticks of the predicted one moves the phase onto it; a hit that does not
 *       fit is remembered, and a second one a whole number of periods after it (within six ticks) re-locks the phase there,
 *       as does a hit that lands on two or more players at once. A stray hit (a mob, a fall) moves nothing.</li>
 * </ul>
 * The line reads 0 at the hit's tick and 59 ticks (2.95 s) the tick after.
 * <p>
 * <b>2026-11-04 (killer560: "the timers are still going off wrong for tick timers ... i mainly notice it during goldor").</b>
 * On Hypixel (a trusted per-tick clock: one tick per ping, as Odin and NoammAddons count) the period is exact in clock
 * ticks, so once the phase is locked an observed hit has nothing to correct: the 8-tick snap above (sized for the testkit's
 * GrimAC server, whose clock wobbles) instead let every stray hit near the prediction walk the phase. His 2026-11-03 log
 * (23:55:05-11) shows it: Spirit Mask, Phoenix and Bonzo procs exactly 3 s apart, the first locked the phase, and the third
 * read 16 ticks off it. Odin and NoammAddons never resync at all. So, while trusted: a hit within {@link #TRUSTED_FIT}
 * ticks confirms and moves nothing, a hit outside it moves nothing by itself (two players hurt at once included), and only
 * hits a whole number of periods apart within {@link #TRUSTED_PAIR} ticks re-lock: a pair if one of them is a mask proc or
 * a multi-player hit, three in a row if they are all your own health. Untrusted (no per-tick pings) keeps the old rules.
 * Every hit and every predicted hit is logged ({@code [TickTimers] Goldor ...}) with the ticks since Storm's death and
 * Goldor's line, the wall time and the pings, so the next real run shows which of the clock, the start phase or a hit
 * is off: his four 2026-11-03/04 runs locked their first mask proc 9, 2, 21 and 25 ticks (mod 60) after Goldor's line,
 * not at one fixed place.
 */
public final class GoldorTick {

    private static final Logger LOGGER = ModLog.get("killer560smod-ticktimers");

    public static final int PERIOD = 60;
    /** A hit this close to the predicted tick is the tick: the phase moves onto it. 8, not less: off Hypixel (no per-tick
     *  pings) the clock follows the client's game time and the server's once-a-second time packets, and wobbles by up to
     *  that much on a loaded server (1102-hx-goldor-tick with GrimAC: hits read 4, 8, -5); a tighter snap lost the lock. */
    static final int SNAP = 8;
    /** Hits this soon after an accepted one are the same hit (a second health packet, the proc line). */
    static final int DEBOUNCE = 5;
    /** A hit this close to the prediction, once the phase is locked, moves nothing (observation noise). */
    static final int NOISE = 1;
    /** Ticks from Goldor's line to his first hit on the real run: 42, i.e. {@code PERIOD - 18} (2026-11-03, see above). */
    static final int FIRST_HIT = PERIOD - 18;
    /** Before the phase has been locked, a hit sooner than this after the line is not Goldor's first (fall damage, a mob). */
    static final int MIN_FIRST_HIT = 20;
    /** Trusted clock: a hit this close to the prediction is the predicted hit (a client tick can carry two or three server
     *  ticks' packets, and a health drop is read at the end of it). */
    static final int TRUSTED_FIT = 3;
    /** Trusted clock: two unexplained hits this close to a whole number of periods apart are in phase with each other. */
    static final int TRUSTED_PAIR = 2;

    /** What showed the hit: a mask/Phoenix proc line, two or more players hurt in one tick, or your own health only. */
    enum Kind { PROC, MULTI, SELF }

    private record Unexplained(long t, Kind kind) {
    }

    private static final List<Unexplained> UNEXPLAINED = new ArrayList<>();
    private static TimerMark lineMark;
    private static long stormDeathTick = Long.MIN_VALUE;
    private static long lastLoggedZero = Long.MIN_VALUE;

    private static boolean active;
    /** Clock tick of a Goldor hit, real or (until the first one is seen) predicted. */
    private static long anchor;
    private static long lastAccepted = Long.MIN_VALUE;
    /** Clock tick of Goldor's line, and whether a hit has fixed the phase since. */
    private static long startTick;
    private static boolean locked;
    /** The last hit that did not fit the phase, or MIN. */
    private static long unexplained = Long.MIN_VALUE;
    private static float lastHealth = Float.NaN;
    private static final Map<Integer, Integer> HURT = new HashMap<>();

    // Test hooks: what the line read (signed ticks past the predicted tick) at each observed hit, and resync counts.
    private static final List<Integer> OFFSETS_AT_HITS = new ArrayList<>();
    private static int relocks;

    private GoldorTick() {
    }

    static long now() { return PerTickServerClock.now(); }

    /** Clock read when a chat line was handled this client tick (MIN = none): chat arrives while the client tick runs its
     *  packets, before the clock's own end-of-tick step, while a health drop is seen at the end of the tick. Everything is
     *  put on the end-of-tick reading ({@link #endOfTick}), or a line and a hit in the same server tick read one apart
     *  (1102-hx-goldor-tick's first run: the first hit read 2). */
    private static long startReadAt = Long.MIN_VALUE;
    private static boolean procPending;

    /** Goldor's opening line: his first hit is one period later. */
    static synchronized void start() {
        active = true;
        startReadAt = now();
        procPending = false;
        startTick = startReadAt;
        locked = false;
        anchor = startReadAt + FIRST_HIT;
        lastAccepted = Long.MIN_VALUE;
        unexplained = Long.MIN_VALUE;
        lastHealth = Float.NaN;
        HURT.clear();
        OFFSETS_AT_HITS.clear();
        UNEXPLAINED.clear();
        relocks = 0;
        lineMark = TimerMark.now();
        lastLoggedZero = Long.MIN_VALUE;
        LOGGER.info("[TickTimers] Goldor line: {} ticks after Storm's death line, first hit predicted at line+{}, clock {}",
                stormDeathTick == Long.MIN_VALUE ? "?" : String.valueOf(startReadAt - stormDeathTick), FIRST_HIT,
                PerTickServerClock.trusted() ? "trusted" : "untrusted");
    }

    /** Storm's death line: remembered only for the log (is Goldor's phase tied to it rather than to his own line?). */
    static synchronized void stormDeath() { stormDeathTick = now(); }

    /** Once per clock tick: one log line on every tick the line reads 0 (the predicted hit). */
    static synchronized void serverTick() {
        if (!active) return;
        long t = now();
        if (remaining() == 0 && t != lastLoggedZero) {
            lastLoggedZero = t;
            LOGGER.info("[TickTimers] Goldor tick 0 (predicted{}): line+{} / storm+{}, {}", locked ? "" : ", not yet locked",
                    t - startTick, stormDeathTick == Long.MIN_VALUE ? "?" : String.valueOf(t - stormDeathTick),
                    lineMark == null ? "" : lineMark.since());
        }
    }

    static synchronized void stop() {
        active = false;
        UNEXPLAINED.clear();
        startReadAt = Long.MIN_VALUE;
        procPending = false;
        lastHealth = Float.NaN;
        HURT.clear();
    }

    public static boolean active() { return active; }

    public static synchronized boolean testLocked() { return locked; }

    /** Ticks until Goldor's next hit: 0 on the hit's own tick, 59 the tick after, and 60 on Goldor's line itself. */
    public static synchronized int remaining() {
        long d = anchor - now();
        return d > 0 ? (int) ((d - 1) % PERIOD) + 1 : (int) Math.floorMod(d, (long) PERIOD);
    }

    /** How far {@code t} is past the predicted hit, -30..29. */
    private static int offset(long t) { return (int) (Math.floorMod(t - anchor + PERIOD / 2, (long) PERIOD) - PERIOD / 2); }

    /** A hit seen at server tick {@code t}; {@code strong} = it landed on two or more players in the same tick. */
    static synchronized void onHit(long t, boolean strong, String why) { onHit(t, strong ? Kind.MULTI : Kind.SELF, why); }

    static synchronized void onHit(long t, Kind kind, String why) {
        if (!active) return;
        if (lastAccepted != Long.MIN_VALUE && t - lastAccepted >= 0 && t - lastAccepted <= DEBOUNCE) return;
        int off = offset(t);
        OFFSETS_AT_HITS.add(off);
        if (OFFSETS_AT_HITS.size() > 32) {
            OFFSETS_AT_HITS.remove(0);
        }
        if (locked && PerTickServerClock.trusted()) {
            String result = trustedLockedHit(t, kind, off);
            logHit(t, why, off, result);
            return;
        }
        String result = untrustedOrFirstHit(t, kind == Kind.MULTI, off, why);
        logHit(t, why, off, result);
    }

    private static void logHit(long t, String why, int off, String result) {
        LOGGER.info("[TickTimers] Goldor hit ({}): {} off -> {}; line+{} / storm+{}, {}", why, off, result, t - startTick,
                stormDeathTick == Long.MIN_VALUE ? "?" : String.valueOf(t - stormDeathTick),
                lineMark == null ? "" : lineMark.since());
    }

    /** Trusted clock, phase locked: confirm, or remember; re-lock only on hits in phase with each other (see class doc). */
    private static String trustedLockedHit(long t, Kind kind, int off) {
        if (Math.abs(off) <= TRUSTED_FIT) {
            lastAccepted = t;
            UNEXPLAINED.clear();
            return "confirmed";
        }
        UNEXPLAINED.removeIf(u -> t - u.t() > 3L * PERIOD + TRUSTED_PAIR || t - u.t() < 0);
        List<Unexplained> inPhase = new ArrayList<>();
        for (Unexplained u : UNEXPLAINED) {
            if (inPhase(t - u.t())) {
                inPhase.add(u);
            }
        }
        boolean reliable = kind != Kind.SELF || inPhase.stream().anyMatch(u -> u.kind() != Kind.SELF);
        boolean relock = !inPhase.isEmpty() && (reliable || inPhase.size() >= 2);
        if (relock) {
            relocks++;
            anchor = t;
            lastAccepted = t;
            UNEXPLAINED.clear();
            return "re-locked (" + (inPhase.size() + 1) + " hits a whole number of periods apart)";
        }
        UNEXPLAINED.add(new Unexplained(t, kind));
        if (UNEXPLAINED.size() > 12) {
            UNEXPLAINED.remove(0);
        }
        return "stray (remembered)";
    }

    private static boolean inPhase(long gap) {
        if (gap < PERIOD - TRUSTED_PAIR) return false;
        return Math.floorMod(gap + TRUSTED_PAIR, (long) PERIOD) <= 2L * TRUSTED_PAIR;
    }

    /** The rules before the phase is locked, and every rule off a trusted clock (unchanged since 2026-11-03). */
    private static String untrustedOrFirstHit(long t, boolean strong, int off, String why) {
        boolean fits = Math.abs(off) <= SNAP;
        // The first hit after the line: Goldor's, wherever it lands (the start phase is only a prediction).
        boolean first = !locked && t - startTick >= MIN_FIRST_HIT;
        boolean pair = false;
        if (unexplained != Long.MIN_VALUE) {
            long gap = t - unexplained;
            // Within 6: off Hypixel the clock can also RUN at the wrong rate between time packets (26.2 Hx: hits 64 apart).
            long rem = Math.floorMod(gap + 6, (long) PERIOD);
            pair = gap >= PERIOD - 6 && gap <= 3L * PERIOD + 6 && rem <= 12;
        }
        if (fits || strong || pair || first) {
            boolean wasLockedBefore = locked;
            String result = fits ? (wasLockedBefore ? "fits" : "first hit, fits the start phase") : "re-locked";
            if (!fits) {
                relocks++;
                LOGGER.info("[TickTimers] Goldor tick re-locked by {} ({} ticks off the old phase{})", why, off,
                        first ? ", the first hit" : pair ? ", a period after an earlier hit" : "");
            }
            // Once locked, a hit within NOISE ticks of the prediction is the same phase seen a tick late (a health drop is
            // read at the end of the client tick, after that tick's pings): the anchor stays, so the line never steps
            // back up on an ordinary hit. Only a real discrepancy moves it (2026-11-03, timers2: the line "jumps back up").
            boolean wasLocked = wasLockedBefore && !first;
            if (!(wasLocked && fits && Math.abs(off) <= NOISE)) {
                anchor = t;
            }
            locked = true;
            lastAccepted = t;
            unexplained = Long.MIN_VALUE;
            return result;
        }
        unexplained = t;
        return "stray";
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
            if (p == client.player) continue;
            Integer prev = HURT.put(p.getId(), p.hurtTime);
            if (prev != null && p.hurtTime > prev) {
                others++;
            }
        }
        int hit = (self ? 1 : 0) + others;
        // A lone teammate's hurt could be anything; your own health drop, or two players at once, is a hit.
        if (self || hit >= 2) {
            onHit(now(), hit >= 2 ? Kind.MULTI : Kind.SELF, self ? (others > 0 ? "your health and " + others
                    + " teammate(s)" : "your health") : others + " players hurt at once");
        }
    }

    /** A mask or Phoenix proc line: you were just hit for lethal damage, in P3 that is Goldor's tick. Counted at the end
     *  of this client tick, with the health drops. */
    static synchronized void onProc() { procPending = active; }

    /** End of every client tick, before {@link #clientTick}: puts this tick's chat-time events on the end-of-tick clock. */
    public static synchronized void endOfTick() {
        if (startReadAt != Long.MIN_VALUE) {
            long shift = now() - startReadAt;
            // Read and moved within one client tick, so a few ticks at most (pings handled after the line); anything
            // else is not a same-tick read and moves nothing.
            if (shift >= 0 && shift <= 3) {
                anchor += shift;
                startTick += shift;
            }
            startReadAt = Long.MIN_VALUE;
        }
        if (procPending) {
            procPending = false;
            onHit(now(), Kind.PROC, "a mask proc");
        }
    }


    public static synchronized List<Integer> testOffsetsAtHits() { return new ArrayList<>(OFFSETS_AT_HITS); }

    public static synchronized int testRelocks() { return relocks; }

    /** Testkit: a hit at the clock's current tick, as the client would observe it. */
    public static void testHitNow(boolean strong) { onHit(now(), strong, "the testkit"); }

    /** Testkit: a hit of one kind ("PROC", "MULTI", "SELF") at the clock's current tick. */
    public static void testHitKindNow(String kind) { onHit(now(), Kind.valueOf(kind), "the testkit (" + kind + ")"); }
}
