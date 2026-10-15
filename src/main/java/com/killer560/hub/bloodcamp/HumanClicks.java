package com.killer560.hub.bloodcamp;

import java.util.concurrent.ThreadLocalRandom;

/**
 * When the next between-hits click may go: a spam-clicker's pace, never a metronome.
 * <p>
 * Each engagement (from the moment there is a mob to click until there is none) rolls its own pace, 7-11 clicks a
 * second; every gap is that pace's interval with a log-normal jitter (sigma 0.3), rounded to whole ticks, at least 2
 * (so never above 10 clicks a second from these alone, well under the ~14 a fast human manages), and about one gap in
 * eight is a short pause (4-9 ticks more) - a hand resetting its grip. The registering hits count as clicks too, so
 * the gap after one is measured from it.
 */
final class HumanClicks {

    private static final int MIN_GAP_TICKS = 2;

    private boolean engaged;
    private double intervalTicks;
    private long next = Long.MIN_VALUE;

    /** Nothing to click: the next engagement rolls a new pace. */
    void idle(long now) {
        engaged = false;
    }

    /** Whether a between-hits click may go on tick {@code now}. */
    boolean due(long now) {
        if (!engaged) {
            engaged = true;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            intervalTicks = 20.0 / (7.0 + 4.0 * r.nextDouble());
            next = Math.max(next, now + gap());
        }
        return now >= next;
    }

    /** A click (registering or not) went on {@code now}. */
    void clicked(long now) {
        next = now + gap();
    }

    private int gap() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double base = intervalTicks <= 0 ? 2.5 : intervalTicks;
        double g = base * Math.exp(r.nextGaussian() * 0.3);
        if (r.nextDouble() < 0.125) {
            g += 4 + r.nextInt(6);
        }
        return Math.max(MIN_GAP_TICKS, (int) Math.round(g));
    }
}
