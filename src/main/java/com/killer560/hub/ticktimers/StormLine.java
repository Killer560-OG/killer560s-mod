package com.killer560.hub.ticktimers;

import com.killer560.hub.util.PerTickServerClock;

/**
 * Storm's one Tick Timers line in F7/M7 Phase 2 (killer560, 2026-11-02: "for lightning and pad have lightning as the tick
 * timer countdown. once it goes off then count off the pad timer for purple, then keep it going for each second for
 * yellow but it should only be 1 line"). It replaces the old Pad, Lightning, PY, Storm and Crush lines.
 * <ol>
 *   <li><b>Lightning</b>: from Storm's opening line ("Pathetic Maxor, just like expected.") it counts down
 *       {@link #LIGHTNING_TICKS} server ticks to his Giga Lightning, and holds at 0 until the lightning really goes off.
 *       555: six of killer560's 2026-11-04 Hypixel runs put the lightning line 549-559 wall ticks after the opening line
 *       (median 555; a seventh lagged, 607), and Hypixel ticks at 20/s (goldor2-1104); 560 was read off whole seconds of a 2026-10-30 log.</li>
 *   <li><b>Purple</b>: Storm's lightning line ("ENERGY HEED MY CALL!" / "THUNDER LET ME BE YOUR CATALYST!") switches the
 *       same line to ONE countdown of {@link #PURPLE_TICKS} server ticks to 0 (NoammAddons 1.2.9 TickTimers.kt "PY": 62 ticks
 *       from that line, read 2026-11-03), then the line goes away ({@link #PURPLE_HOLD} ticks after 0). killer560, 2026-11-03:
 *       "for purple pad timer it should be 1 countdown after lightning not a constant 1s during storm".</li>
 *   <li><b>Yellow</b>: Storm's first crush line ("Oof" / "Ouch, that hurt!") keeps the same cycle going for the yellow pad.</li>
 * </ol>
 * Storm's death line ends it. Everything is counted on {@link PerTickServerClock} from the remembered server tick of the
 * opening line, so a lag second neither runs it ahead nor drops a tick.
 */
public final class StormLine {

    public static final int LIGHTNING_TICKS = 555;
    public static final int PAD_PERIOD = 20;
    public static final int PURPLE_TICKS = 62;
    /** Ticks the purple line stays on 0 before it goes away. */
    public static final int PURPLE_HOLD = 10;

    /** WAITING: the purple countdown is over; nothing is shown until the crush starts the yellow cycle. */
    public enum Phase { NONE, LIGHTNING, PURPLE, WAITING, YELLOW }

    private static Phase phase = Phase.NONE;
    private static long startTick;
    private static long lightningAt;
    private static long purpleAt;
    private static long purpleReadAt = Long.MIN_VALUE;

    private StormLine() {
    }

    static void start() {
        long now = PerTickServerClock.now();
        phase = Phase.LIGHTNING;
        startTick = now;
        lightningAt = now + LIGHTNING_TICKS;
        startReadAt = now;
        startMark = TimerMark.now();
        lightningLogged = false;
    }

    private static TimerMark startMark;
    private static TimerMark purpleMark;
    private static boolean lightningLogged;
    private static boolean purpleLogged;

    /** Once per server tick: one log line when the lightning countdown and the purple countdown each reach 0. */
    static void serverTick() {
        if (phase == Phase.LIGHTNING && !lightningLogged && remaining() == 0) {
            lightningLogged = true;
            TimerMark.proc("Storm Lightning", startMark);
        } else if (phase == Phase.PURPLE && !purpleLogged && remaining() == 0) {
            purpleLogged = true;
            TimerMark.proc("Storm Purple", purpleMark);
        }
    }

    /** As in {@link GoldorTick#endOfTick}: the opening line was read before the clock's end-of-tick step. */
    private static long startReadAt = Long.MIN_VALUE;

    /** End of every client tick: puts the opening line on the end-of-tick clock. */
    public static void endOfTick() {
        if (startReadAt != Long.MIN_VALUE) {
            long shift = PerTickServerClock.now() - startReadAt;
            if (shift < 0 || shift > 3) {
                shift = 0; // not a same-tick read (see GoldorTick.endOfTick)
            }
            startTick += shift;
            lightningAt += shift;
            startReadAt = Long.MIN_VALUE;
        }
        if (purpleReadAt != Long.MIN_VALUE) {
            long shift = PerTickServerClock.now() - purpleReadAt;
            purpleAt += (shift < 0 || shift > 3) ? 0 : shift;
            purpleReadAt = Long.MIN_VALUE;
        }
    }

    /** Storm's Giga Lightning went off (his lightning line). */
    static void onLightning() {
        if (phase == Phase.LIGHTNING) {
            TimerMark.proc("Storm lightning line (" + remaining() + " ticks left on Lightning)", startMark);
            phase = Phase.PURPLE;
            purpleReadAt = PerTickServerClock.now();
            purpleAt = purpleReadAt + PURPLE_TICKS;
            purpleMark = TimerMark.now();
            purpleLogged = false;
        }
    }

    /** Storm was crushed. A crush can only follow the lightning, so a missed lightning line is caught up here too. */
    static void onCrush() {
        if (phase == Phase.LIGHTNING || phase == Phase.PURPLE || phase == Phase.WAITING) {
            phase = Phase.YELLOW;
        }
    }

    static void stop() {
        phase = Phase.NONE;
        startReadAt = Long.MIN_VALUE;
        purpleReadAt = Long.MIN_VALUE;
    }

    public static Phase phase() {
        if (phase == Phase.PURPLE && PerTickServerClock.now() - purpleAt > PURPLE_HOLD) {
            phase = Phase.WAITING;
        }
        return phase;
    }

    /** Whether the line is drawn now. */
    public static boolean visible() {
        Phase p = phase();
        return p != Phase.NONE && p != Phase.WAITING;
    }

    /** Ticks left on the line now (lightning: to the lightning, never below 0; a pad: to the next pad tick). */
    public static int remaining() {
        long now = PerTickServerClock.now();
        return switch (phase) {
            case LIGHTNING -> (int) Math.max(0L, Math.min(LIGHTNING_TICKS, lightningAt - now));
            case PURPLE -> (int) Math.max(0L, Math.min(PURPLE_TICKS, purpleAt - now));
            case YELLOW -> (int) Math.floorMod(startTick - now, (long) PAD_PERIOD);
            case NONE, WAITING -> -1;
        };
    }

    /** The line's full length, for the countdown color. */
    static int max() { return phase == Phase.LIGHTNING ? LIGHTNING_TICKS : phase == Phase.PURPLE ? PURPLE_TICKS : PAD_PERIOD; }

    static String prefix() {
        return switch (phase) {
            case LIGHTNING -> "§bLightning:";
            case PURPLE -> "§5Purple:";
            case YELLOW -> "§eYellow:";
            case NONE, WAITING -> "";
        };
    }
}
