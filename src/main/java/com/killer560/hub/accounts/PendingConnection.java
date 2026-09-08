package com.killer560.hub.accounts;

/**
 * Tracks whether the player just initiated a connection to Hypixel, so the ban-check mixins know
 * whether a disconnect/join they're observing is actually relevant. Auto-clears after a timeout
 * so a missed signal doesn't leave this stuck "pending" forever.
 */
public final class PendingConnection {

    private static final long TIMEOUT_MILLIS = 25_000;

    private static volatile boolean pending = false;
    private static volatile long pendingSince = 0L;

    private PendingConnection() {
    }

    public static void markPending() {
        pending = true;
        pendingSince = System.currentTimeMillis();
    }

    public static void clear() {
        pending = false;
    }

    public static boolean isPending() {
        if (pending && System.currentTimeMillis() - pendingSince > TIMEOUT_MILLIS) {
            pending = false;
        }
        return pending;
    }
}
