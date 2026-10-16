package com.killer560.hub.util;

import org.slf4j.Logger;

import java.util.function.BooleanSupplier;

/**
 * One boolean per switched feature, read by {@link FeatureGuard}'s gated wrappers instead of calling into the feature
 * (fps-idle-2-1016, 2026-10-16).
 *
 * <p>A tick or render listener that returns at once still costs 1-2 us, nearly all of it cache misses on its own code,
 * its config class and its config object (testkit {@code EventSpan} and a no-probe JFR, every switch off). A gated
 * listener is skipped with one read of {@link #ON}, an array every gated listener shares, so it stays in cache.
 *
 * <p>Each bit is a CONSERVATIVE "might do something": its gate reads only the stored setting fields (never
 * {@code SkyblockGate.allows()}, the dungeon state or anything else that changes without a setter), so a false bit means
 * the listener's own first check would return. A true bit runs the listener exactly as before. The gates are evaluated
 * only after {@link #changed()} - which every gated config calls from the setters of those fields and at the end of its
 * {@code load()} (so a profile apply, which reloads every config, refreshes them too) - never per tick.
 *
 * <p>Safety net: every {@link #RECHECK_EVERY}th time a listener is skipped, its gate is asked again; a bit that should
 * have been true (a setter that forgot {@link #changed()}) is logged at ERROR and corrected, so a missed path costs at
 * most a few seconds and is found by the testkit's "no killer560smod ERROR" check rather than staying silent.
 */
public final class FeatureBits {

    private static final Logger LOGGER = ModLog.get("killer560smod-featurebits");

    /** How often a skip re-asks its gate: 1 in 64 (about every 3 s for a tick listener, 0.4 s for a frame one). */
    static final int RECHECK_EVERY = 64;

    private static BooleanSupplier[] gates = new BooleanSupplier[0];
    private static String[] names = new String[0];
    /** Read on every gated call (render thread). Rebuilt in place by {@link #refresh}. */
    static boolean[] ON = new boolean[0];
    private static int[] skips = new int[0];
    private static volatile boolean dirty = true;

    private FeatureBits() {
    }

    /** A new bit for {@code name}, whose value is {@code gate} (stored settings only). Call at registration. */
    public static synchronized int register(String name, BooleanSupplier gate) {
        int id = gates.length;
        gates = java.util.Arrays.copyOf(gates, id + 1);
        names = java.util.Arrays.copyOf(names, id + 1);
        boolean[] on = java.util.Arrays.copyOf(ON, id + 1);
        on[id] = true; // until the first refresh: run, as before
        ON = on;
        skips = java.util.Arrays.copyOf(skips, id + 1);
        gates[id] = gate;
        names[id] = name;
        dirty = true;
        return id;
    }

    /** A gated setting changed (or a config was reloaded): the bits are recomputed before the next gated call. */
    public static void changed() {
        dirty = true;
    }

    /** Whether the listener behind bit {@code id} may do anything. One field read and one array read when clean. */
    public static boolean on(int id) {
        if (dirty) {
            refresh();
        }
        return ON[id];
    }

    /** Called by a wrapper that skipped bit {@code id}: every {@link #RECHECK_EVERY}th skip asks the gate again. */
    static void skipped(int id) {
        int[] s = skips;
        if (++s[id] < RECHECK_EVERY) {
            return;
        }
        s[id] = 0;
        if (evaluate(id)) {
            LOGGER.error("[FeatureBits] '{}' was switched on without FeatureBits.changed() - its listener was skipped"
                    + " until now; a setter or loader is missing the call", names[id]);
            ON[id] = true;
        }
    }

    private static synchronized void refresh() {
        dirty = false; // first: a change made while this runs sets it again
        boolean[] on = ON;
        for (int i = 0; i < gates.length; i++) {
            on[i] = evaluate(i);
        }
    }

    private static boolean evaluate(int id) {
        try {
            return gates[id].getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return true; // cannot tell: run the listener, as it always did
        }
    }

    /** Testkit: {@code name=on} for every bit, after a refresh. */
    public static synchronized String snapshot() {
        refresh();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gates.length; i++) {
            sb.append(names[i]).append('=').append(ON[i]).append('\n');
        }
        return sb.toString();
    }

    /** Testkit: how many bits are registered. */
    public static int count() {
        return gates.length;
    }
}
