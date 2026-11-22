package com.killer560.hub.mapcycler;

/**
 * How likely a map not yet proven within the limit still is (mapcycler-history-1121; killer560 2026-11-21: "do some calcs to
 * figure out what the probability is. Then let the user use a probability slider"). Counted from his 454 real F7 floors,
 * rebuilt from his game logs by tools/mapcycler/layouts-from-logs.py ({@code --table} prints these rows): the rooms
 * before Blood each floor really had, by the wither and blood doors the map showed before the start (they all lie on the
 * path, so more of them means a longer one). The odds are those floors' counts kept to what this map proves (its least
 * and most). F7 only: no other floor has enough floors yet.
 */
public final class RushOdds {

    /** Floors behind an estimate: fewer and there is none (the map is judged as before, re-rolled when unproven). */
    static final int MIN_FLOORS = 15;

    // 454 F7 floors: [wither/blood doors seen before the start, 2..7+][rooms before Blood 0..10]
    private static final int[][] COUNTS = {
            {0, 0, 2, 16, 8, 11, 1, 1, 0, 0, 0}, // 2 or fewer
            {0, 0, 1, 47, 32, 15, 6, 0, 0, 0, 0}, // 3
            {0, 0, 0, 19, 61, 47, 17, 1, 0, 0, 0}, // 4
            {0, 0, 0, 0, 37, 42, 24, 2, 0, 0, 0}, // 5
            {0, 0, 0, 0, 0, 25, 11, 6, 0, 0, 0}, // 6
            {0, 0, 0, 0, 0, 0, 13, 9, 0, 0, 0}, // 7 or more
    };

    private RushOdds() {
    }

    /** Whether the floor has an estimate at all. */
    public static boolean covers(String floor) {
        return "F7".equals(floor);
    }

    /**
     * The chance in percent (0-99; 100 is only ever a proof) that the map has at most {@code limit} rooms before Blood,
     * or -1 when there is no estimate (another floor, unknown doors, too few such floors).
     */
    public static int percent(String floor, int least, int most, int keyDoors, int limit) {
        if (!covers(floor) || keyDoors < 0) {
            return -1;
        }
        int pct = percent(COUNTS[Math.min(Math.max(keyDoors, 2), 7) - 2], least, most, limit);
        if (pct < 0) {
            // Too few floors with that many doors in this range: all F7 floors.
            int[] all = new int[COUNTS[0].length];
            for (int[] row : COUNTS) {
                for (int t = 0; t < all.length; t++) {
                    all[t] += row[t];
                }
            }
            pct = percent(all, least, most, limit);
        }
        return pct;
    }

    private static int percent(int[] counts, int least, int most, int limit) {
        int in = 0;
        int ok = 0;
        for (int t = Math.max(0, least); t < counts.length && t <= most; t++) {
            in += counts[t];
            ok += t <= limit ? counts[t] : 0;
        }
        return in < MIN_FLOORS ? -1 : Math.min(99, ok * 100 / in);
    }
}
