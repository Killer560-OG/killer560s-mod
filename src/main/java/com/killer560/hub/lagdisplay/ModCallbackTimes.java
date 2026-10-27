package com.killer560.hub.lagdisplay;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The mod's own share of one frame, per callback (stutter-1026): an always-on, fixed-array cousin of the debug-only
 * {@code perfprobe/PerfProbe}. Only while {@link FrameStats} is measuring ({@link #active}), every tick handler wrapped by
 * {@code util/FeatureGuard} and every HUD element drawn by {@code HudElementRegistry.drawAt} adds its wall time to a slot;
 * {@link FrameStats} reads the top three on a stutter frame and clears the slots that were touched at every frame start.
 *
 * <p>Cost while active: two {@code System.nanoTime()} calls and an array add per call, no allocation (testkit
 * 95-perf-stutter). While inactive: one static boolean read per call. Render thread only: a call from any other thread is
 * not counted (FeatureGuard's handlers and the HUD both run on it).
 *
 * <p>Not covered: the mod's mixins into vanilla code, Fabric listeners registered without FeatureGuard (level render,
 * screen and chat events), and work a feature hands to another thread.
 */
public final class ModCallbackTimes {

    /** Set by {@link FrameStats} at each frame start; read by every wrapper first. */
    public static boolean active;

    private static final int MAX_SLOTS = 1024;
    private static final String[] NAMES = new String[MAX_SLOTS];
    private static final long[] NS = new long[MAX_SLOTS];
    private static final int[] CALLS = new int[MAX_SLOTS];
    private static final int[] TOUCHED = new int[MAX_SLOTS];
    private static int touchedCount;
    private static int slotCount;
    private static final Map<String, Integer> BY_NAME = new java.util.HashMap<>();
    private static final Map<Object, Integer> HUD_SLOTS = new IdentityHashMap<>();
    private static volatile Thread renderThread;

    private ModCallbackTimes() {
    }

    /** The slot for a callback name (shared by every callback registered under that name). Any thread, at registration. */
    public static synchronized int slot(String name) {
        Integer s = BY_NAME.get(name);
        if (s != null) {
            return s;
        }
        if (slotCount >= MAX_SLOTS) {
            return -1;
        }
        int n = slotCount++;
        NAMES[n] = name;
        BY_NAME.put(name, n);
        return n;
    }

    /** Adds {@code System.nanoTime() - t0} to {@code slot}. Called only when {@link #active} was true at {@code t0}. */
    public static void add(int slot, long t0) {
        long dt = System.nanoTime() - t0;
        if (slot < 0 || Thread.currentThread() != renderThread) {
            return;
        }
        if (CALLS[slot] == 0 && NS[slot] == 0) {
            TOUCHED[touchedCount++] = slot;
        }
        NS[slot] += dt;
        CALLS[slot]++;
    }

    /** The slot for a HUD element (render thread). Allocates only the first time an element is seen. */
    public static int hudSlot(Object element, String id) {
        Integer s = HUD_SLOTS.get(element);
        if (s == null) {
            s = slot("HUD " + id);
            HUD_SLOTS.put(element, s);
        }
        return s;
    }

    /** Frame start (render thread): forget the last frame's slots. */
    static void beginFrame(boolean on) {
        renderThread = Thread.currentThread();
        for (int i = 0; i < touchedCount; i++) {
            int s = TOUCHED[i];
            NS[s] = 0;
            CALLS[s] = 0;
        }
        touchedCount = 0;
        active = on;
    }

    /** The {@code n} slowest callbacks of the frame now ending, as "name 12.3 ms" (allocates; stutter frames only). */
    static String top(int n) {
        StringBuilder sb = new StringBuilder();
        long total = 0;
        boolean[] used = new boolean[touchedCount];
        for (int i = 0; i < touchedCount; i++) {
            total += NS[TOUCHED[i]];
        }
        for (int k = 0; k < n; k++) {
            int best = -1;
            for (int i = 0; i < touchedCount; i++) {
                if (!used[i] && (best < 0 || NS[TOUCHED[i]] > NS[TOUCHED[best]])) {
                    best = i;
                }
            }
            if (best < 0 || NS[TOUCHED[best]] < 100_000L) { // under 0.1 ms is not worth naming
                break;
            }
            used[best] = true;
            int s = TOUCHED[best];
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(NAMES[s]).append(' ').append(FrameStats.ms(NS[s]));
            if (CALLS[s] > 1) {
                sb.append(" (").append(CALLS[s]).append("x)");
            }
        }
        return "all " + FrameStats.ms(total) + (sb.length() > 0 ? "; " + sb : "");
    }

    /** Total mod callback time in the frame now ending (ns). */
    static long totalNs() {
        long total = 0;
        for (int i = 0; i < touchedCount; i++) {
            total += NS[TOUCHED[i]];
        }
        return total;
    }
}
