package com.killer560.hub.ticktimers;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.PerTickServerClock;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * A tick timer's start (its chat line): the server-tick clock, the wall clock and the raw ping count at that moment, so each
 * proc can log one line saying how far each of them moved (killer560, 2026-11-04: "the timers are still going off wrong
 * for tick timers ... i mainly notice it during goldor", and his log had no line about any timer). A proc whose server
 * ticks and wall time disagree is lag; one whose ticks and pings disagree is the clock running off the pings.
 */
record TimerMark(long tick, long wallMs, long pings) {

    private static final Logger LOGGER = ModLog.get("killer560smod-ticktimers");

    static TimerMark now() {
        return new TimerMark(PerTickServerClock.now(), System.currentTimeMillis(), PerTickServerClock.rawPings());
    }

    /** "83 server ticks, 4160 ms (83.2 ticks of wall time), 83 pings, trusted" since this mark. */
    String since() {
        long ticks = PerTickServerClock.now() - tick;
        long wall = System.currentTimeMillis() - wallMs;
        return String.format(Locale.US, "%d server ticks, %d ms (%.1f ticks of wall time), %d pings, %s", ticks, wall,
                wall / 50.0, PerTickServerClock.rawPings() - pings, PerTickServerClock.trusted() ? "trusted" : "untrusted");
    }

    /** One compact INFO line for a timer reaching its end. */
    static void proc(String name, TimerMark from) {
        if (from != null) {
            LOGGER.info("[TickTimers] {} proc: {} since its line", name, from.since());
        }
    }
}
