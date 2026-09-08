package com.killer560.hub.notify;

/**
 * A replacement for vanilla's {@code Gui.setOverlayMessage} (the text that pops up above the
 * hotbar), timed off real wall-clock time instead of client ticks. Vanilla's own version decays a
 * tick counter (60 ticks = 3s at the normal 20 ticks/sec) inside {@code Gui}'s private
 * {@code tick()} - on killer560's heavily modded client that counter was draining far faster than 3
 * real seconds (observed: barely visible at all), which only makes sense if something in that
 * modlist is calling client ticks far more often than 20/sec, e.g. tied to render framerate
 * instead of the fixed logical tick rate. Rendered by {@link com.killer560.hub.notify.mixin.GuiMixin}
 * at the exact same screen position vanilla uses for its own overlay message
 * ({@code (guiWidth/2, guiHeight - 68)}, confirmed via {@code javap} on the real 26.1.2 jar).
 */
public final class ModOverlayMessage {

    private static volatile String text;
    private static volatile long expiresAtMs;

    public static void show(String message, long durationMs) {
        text = message;
        expiresAtMs = System.currentTimeMillis() + durationMs;
    }

    /** @return the message to draw this frame, or null if nothing is currently showing. */
    public static String current() {
        String t = text;
        if (t == null || System.currentTimeMillis() >= expiresAtMs) {
            return null;
        }
        return t;
    }

    private ModOverlayMessage() {
    }
}
