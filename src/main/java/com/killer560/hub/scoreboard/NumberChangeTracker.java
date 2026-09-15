package com.killer560.hub.scoreboard;

import java.util.HashMap;
import java.util.Map;

/**
 * "Show earned/lost" - SkyHanni's {@code CustomScoreboardNumberTrackingElement} / SkyBlock Custom Scoreboard's
 * {@code NumberTrackingElement}: when a tracked number (purse, bits, ...) changes, a " (+N)" is drawn after its line
 * for 5 seconds (changes inside that window add up, like the standalone mod), fading out over the last second. A value
 * not seen for {@link #FORGET_MS} (island without that line, profile switch) is re-baselined instead of producing one
 * huge jump.
 */
final class NumberChangeTracker {

    static final long SHOW_MS = 5000L;
    static final long FADE_MS = 1000L;
    private static final long FORGET_MS = 15_000L;

    private static final class State {
        double last;
        long lastSeenMs;
        double pending;
        long untilMs;
    }

    private static final Map<String, State> STATES = new HashMap<>();

    private NumberChangeTracker() {
    }

    /** Records {@code raw} for {@code key} and returns {@code line} with its popup attached while one is active. */
    static ScoreboardLine track(CustomScoreboardConfig cfg, ScoreboardLine line, String key, String raw, String color) {
        Double value = raw == null ? null : ScoreboardLine.parse(raw);
        if (value == null) {
            return line;
        }
        long now = System.currentTimeMillis();
        State state = STATES.get(key);
        if (state == null || now - state.lastSeenMs > FORGET_MS) {
            state = new State();
            state.last = value;
            state.lastSeenMs = now;
            STATES.put(key, state);
            return line;
        }
        state.lastSeenMs = now;
        double delta = value - state.last;
        state.last = value;
        if (!cfg.isShowNumberDifference()) {
            state.pending = 0;
            state.untilMs = 0;
            return line;
        }
        if (Math.abs(delta) > 1e-9) {
            state.pending = now < state.untilMs ? state.pending + delta : delta;
            state.untilMs = now + SHOW_MS;
        }
        if (now >= state.untilMs || Math.abs(state.pending) < 1e-9) {
            return line;
        }
        String amount = ScoreboardLine.formatNumber(state.pending);
        String popup = " §7(" + color + (state.pending > 0 ? "+" : "") + amount + "§7)";
        return line.withPopup(popup, state.untilMs);
    }

    /** Text alpha (0-255) for a popup expiring at {@code untilMs}. */
    static int alpha(long untilMs, long nowMs) {
        long left = untilMs - nowMs;
        if (left >= FADE_MS) {
            return 255;
        }
        return Math.max(16, (int) (255 * Math.max(0, left) / FADE_MS));
    }

    static void clear() {
        STATES.clear();
    }
}
