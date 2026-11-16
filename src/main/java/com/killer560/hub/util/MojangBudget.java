package com.killer560.hub.util;

/**
 * Mojang's session server rate-limits per account and IP, and the game's own server join draws on the same budget
 * (2026-11-12: a first Hypixel join failed with "Status: 429" after the mod spent it at launch). The mod therefore
 * never spends it near a server connect or after a 429: callers ask {@link #mayCallSessionServer()} first.
 */
public final class MojangBudget {
    /** No session-server call within this long of a connect starting or a world join. */
    private static final long QUIET_MS = 30_000L;
    private static volatile long connectAtMs;
    private static volatile long backoffUntilMs;

    private MojangBudget() {
    }

    /** A server connect has started (ConnectScreen mixin) or a world was joined. */
    public static void noteConnect() {
        connectAtMs = System.currentTimeMillis();
    }

    /** Mojang answered 429: stay away for {@code retryAfterMs} (at least a minute). */
    public static void note429(long retryAfterMs) {
        backoffUntilMs = Math.max(backoffUntilMs, System.currentTimeMillis() + Math.max(60_000L, retryAfterMs));
    }

    /** Test hook: forgets the connect time and any back-off. */
    public static void resetForTest() {
        connectAtMs = 0;
        backoffUntilMs = 0;
    }

    public static boolean mayCallSessionServer() {
        long now = System.currentTimeMillis();
        return now >= backoffUntilMs && now - connectAtMs >= QUIET_MS;
    }

    /** True when {@code e} (or a cause) is a 429 from Mojang; records the back-off. */
    public static boolean noteIf429(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.contains("429")) {
                note429(0);
                return true;
            }
        }
        return false;
    }
}
