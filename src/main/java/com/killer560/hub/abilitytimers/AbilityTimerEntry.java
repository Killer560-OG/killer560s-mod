package com.killer560.hub.abilitytimers;

import java.util.UUID;

/** One named ability/cooldown countdown - killer560's "tick timers from Odin/noamm" and "mask
 *  invulnerability cooldown timers" requests, folded into one generic system rather than two separate
 *  ones, since both are really the same thing: press a key when you use an ability, see a countdown
 *  until it's up. Duration is user-set (not hardcoded to a real Hypixel cooldown value this session
 *  couldn't verify against a live game - see the seeded "Mask" preset's own comment in
 *  {@link AbilityTimersConfig}), so it's correct for whatever the real number turns out to be. */
public final class AbilityTimerEntry {

    /** Duration slider range (ms). Was a "+5s, wraps at 300s" button - 48 clicks to dial in a 4-minute
     *  timer (2026-09-20 tab sweep) - now a drag-to-set slider, so the range is just its min/max. */
    public static final int MIN_DURATION_MS = 5_000;
    public static final int MAX_DURATION_MS = 300_000;

    public String id = UUID.randomUUID().toString();
    public String name = "Timer";
    public int durationMs = 60_000;
    /** GLFW key code that starts (or restarts) this countdown, or -1 if unbound. */
    public int keyCode = -1;
    public String colorHex = "CC6600";
    public boolean enabled = true;
    /** Not persisted - 0 means not currently running. */
    public transient long expiresAtMs = 0;

    public int color() {
        try {
            return 0xFF000000 | Integer.parseInt(colorHex, 16);
        } catch (Exception e) {
            return 0xFFCC6600;
        }
    }

    public boolean isRunning(long now) {
        return expiresAtMs > now;
    }

    public void start(long now) {
        expiresAtMs = now + durationMs;
    }
}
