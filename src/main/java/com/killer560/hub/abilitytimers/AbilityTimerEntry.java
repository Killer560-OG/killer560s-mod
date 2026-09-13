package com.killer560.hub.abilitytimers;

import java.util.UUID;

/** One named ability/cooldown countdown - killer560's "tick timers from Odin/noamm" and "mask
 *  invulnerability cooldown timers" requests, folded into one generic system rather than two separate
 *  ones, since both are really the same thing: press a key when you use an ability, see a countdown
 *  until it's up. Duration is user-set (not hardcoded to a real Hypixel cooldown value this session
 *  couldn't verify against a live game - see the seeded "Mask" preset's own comment in
 *  {@link AbilityTimersConfig}), so it's correct for whatever the real number turns out to be. */
public final class AbilityTimerEntry {

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
