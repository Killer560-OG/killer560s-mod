package com.killer560.hub.ticktimers;

import com.killer560.hub.util.PerTickServerClock;

/**
 * Storm's one Tick Timers line in F7/M7 Phase 2 (killer560, 2026-11-02: "for lightning and pad have lightning as the tick
 * timer countdown. once it goes off then count off the pad timer for purple, then keep it going for each second for
 * yellow but it should only be 1 line"). It replaces the old Pad, Lightning, PY, Storm and Crush lines.
 * <ol>
 *   <li><b>Lightning</b>: from Storm's opening line ("Pathetic Maxor, just like expected.") it counts down
 *       {@link #LIGHTNING_TICKS} server ticks to his Giga Lightning, and holds at 0 until the lightning really goes off.
 *       560 is the gap in killer560's real runs (2026-10-30 log: 15:37:27 -&gt; 15:37:55 and 15:41:29 -&gt; 15:41:57).</li>
 *   <li><b>Purple</b>: Storm's lightning line ("ENERGY HEED MY CALL!" / "THUNDER LET ME BE YOUR CATALYST!") switches the
 *       same line to the pad timer: the 20-tick pad cycle (Odin's and NoammAddons' Storm pad timer, phased on Storm's
 *       opening line), 0 on each pad tick.</li>
 *   <li><b>Yellow</b>: Storm's first crush line ("Oof" / "Ouch, that hurt!") keeps the same cycle going for the yellow pad.</li>
 * </ol>
 * Storm's death line ends it. Everything is counted on {@link PerTickServerClock} from the remembered server tick of the
 * opening line, so a lag second neither runs it ahead nor drops a tick.
 */
public final class StormLine {

    public static final int LIGHTNING_TICKS = 560;
    public static final int PAD_PERIOD = 20;

    public enum Phase { NONE, LIGHTNING, PURPLE, YELLOW }

    private static Phase phase = Phase.NONE;
    private static long startTick;
    private static long lightningAt;

    private StormLine() {
    }

    static void start() {
        long now = PerTickServerClock.now();
        phase = Phase.LIGHTNING;
        startTick = now;
        lightningAt = now + LIGHTNING_TICKS;
        startReadAt = now;
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
    }

    /** Storm's Giga Lightning went off (his lightning line). */
    static void onLightning() {
        if (phase == Phase.LIGHTNING) {
            phase = Phase.PURPLE;
        }
    }

    /** Storm was crushed. A crush can only follow the lightning, so a missed lightning line is caught up here too. */
    static void onCrush() {
        if (phase == Phase.LIGHTNING || phase == Phase.PURPLE) {
            phase = Phase.YELLOW;
        }
    }

    static void stop() {
        phase = Phase.NONE;
        startReadAt = Long.MIN_VALUE;
    }

    public static Phase phase() {
        return phase;
    }

    /** Ticks left on the line now (lightning: to the lightning, never below 0; a pad: to the next pad tick). */
    public static int remaining() {
        long now = PerTickServerClock.now();
        return switch (phase) {
            case LIGHTNING -> (int) Math.max(0L, Math.min(LIGHTNING_TICKS, lightningAt - now));
            case PURPLE, YELLOW -> (int) Math.floorMod(startTick - now, (long) PAD_PERIOD);
            case NONE -> -1;
        };
    }

    /** The line's full length, for the countdown color. */
    static int max() {
        return phase == Phase.LIGHTNING ? LIGHTNING_TICKS : PAD_PERIOD;
    }

    static String prefix() {
        return switch (phase) {
            case LIGHTNING -> "§bLightning:";
            case PURPLE -> "§5Purple:";
            case YELLOW -> "§eYellow:";
            case NONE -> "";
        };
    }
}
