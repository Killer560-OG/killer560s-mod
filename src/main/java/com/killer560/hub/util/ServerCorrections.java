package com.killer560.hub.util;

import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The one way an automation reacts to a server position correction (killer560, 2026-10-06: "Nothing in this mod should
 * stop from server corrections ever - just have it send a chat message and make noises"). A feature that sees the server
 * put him somewhere it did not plan calls {@link #report}, then CARRIES ON from where the server put him - replanning if
 * it plans paths. Nothing here stops, pauses or disables anything, and nothing ever moves the player back: the corrected
 * position is honoured.
 *
 * <p>Per feature, the chat line goes out at most once every 2 s (a burst says how many it folded in); the alarm has its
 * own 3 s limit in {@link ModSounds#playCorrectionAlarm()}. Every report is logged.
 */
public final class ServerCorrections {

    private static final Logger LOGGER = ModLog.get("killer560smod-corrections");
    private static final long CHAT_MIN_GAP_MS = 2000L;

    private static final Map<String, long[]> chatState = new HashMap<>();
    private static int reports;

    private ServerCorrections() {
    }

    /**
     * @param feature  the feature's chat name ("AP3", "Auto Routes", ...)
     * @param what     what it was doing / what happens next, lower case ("while walking - carrying on")
     * @param distance how far the server moved him, in blocks
     */
    public static void report(String feature, String what, double distance) {
        String dist = String.format(Locale.US, "%.2f", distance);
        say(feature, "server moved you " + dist + " blocks " + what, "moved you " + dist + " blocks " + what + ".");
    }

    /**
     * The same chat line + alarm for something that is not a measured move but is handled by the same rule - the server
     * never putting him where an action was sent to (an etherwarp that did not land).
     *
     * @param what the whole sentence after "Server correction: ", without the full stop
     */
    public static void reportEvent(String feature, String what) {
        say(feature, what, what + ".");
    }

    private static void say(String feature, String logText, String chatText) {
        int folded;
        synchronized (ServerCorrections.class) {
            reports++;
            long now = System.currentTimeMillis();
            long[] st = chatState.computeIfAbsent(feature, k -> new long[]{0L, 0L});
            if (st[0] != 0L && now - st[0] < CHAT_MIN_GAP_MS) {
                st[1]++;
                LOGGER.info("[Correction] {}: {} (chat folded)", feature, logText);
                ModSounds.playCorrectionAlarm();
                return;
            }
            folded = (int) st[1];
            st[0] = now;
            st[1] = 0L;
        }
        LOGGER.info("[Correction] {}: {}", feature, logText);
        ModChat.send(feature, ModChat.bad("Server correction: "), ModChat.text(chatText),
                folded > 0 ? ModChat.dim(" (+" + folded + " more)") : ModChat.text(""));
        ModSounds.playCorrectionAlarm();
    }

    private static volatile double lastMoveDistance;
    private static volatile int packetCount;

    /**
     * Every server position packet, from {@code ap3/mixin/Ap3PositionPacketMixin} BEFORE vanilla applies it: how far it
     * moves him from where the client had him. Features whose own hook carries no delta read it back with
     * {@link #lastMoveDistance()} on their next tick.
     */
    public static void noteServerMove(double dx, double dy, double dz) {
        lastMoveDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        packetCount++;
    }

    /** Distance of the last server position packet's move, in blocks (0 before the first). */
    public static double lastMoveDistance() {
        return lastMoveDistance;
    }

    /** Server position packets seen since launch: a feature keeps its own copy and compares each tick. */
    public static int packetCount() {
        return packetCount;
    }

    /** Total reports since launch (the testkit reads this). */
    public static int reports() {
        return reports;
    }
}
