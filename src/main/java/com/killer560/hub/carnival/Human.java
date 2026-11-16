package com.killer560.hub.carnival;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Rolled timing and aim spread for the Carnival autos, so no two reactions, taps or aim points are the same: a reaction
 * is log-normal (most short, a few long, never periodic) and an aim point sits somewhere inside the target, not on its
 * exact middle. Pure numbers; nothing here touches the game.
 */
final class Human {

    private Human() {
    }

    /** A reaction of about {@code median} ticks: log-normal (spread 0.45), at least {@code min}, at most {@code max}. */
    static int ticks(double median, int min, int max) {
        double v = median * Math.exp(0.45 * ThreadLocalRandom.current().nextGaussian());
        return (int) Math.max(min, Math.min(max, Math.round(v)));
    }

    /** A normal offset with spread {@code sd}, cut at {@code limit} either way. */
    static double spread(double sd, double limit) {
        return Math.max(-limit, Math.min(limit, sd * ThreadLocalRandom.current().nextGaussian()));
    }

    /** A value between {@code lo} and {@code hi}. */
    static double between(double lo, double hi) {
        return lo + (hi - lo) * ThreadLocalRandom.current().nextDouble();
    }
}
