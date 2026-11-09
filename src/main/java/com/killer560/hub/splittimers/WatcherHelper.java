package com.killer560.hub.splittimers;

import com.killer560.hub.dungeonalerts.DungeonAlertsFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;

import java.util.Locale;

/**
 * Watcher Helper (Athen): how fast the Watcher speaks and what the blood room cost. The clock starts on the blood door's
 * greeting and stops the Watcher speaking at "Let's see how you can handle this." (the speak time, graded by a title:
 * Vroom under 22 s, Watcher under 23, Yawn under 25, Zzz after); "You have proven yourself" ends it and one chat line splits
 * the room into speak, move (the Watcher's first step, {@link WatcherMoveTracker}), kill and total. It reads the same
 * three lines as Split Timers ({@link SplitTimersFeature}) with its own subscription, so it works with the splits off.
 */
public final class WatcherHelper {

    private static long doorMs;
    private static long speakMs;

    private WatcherHelper() {
    }

    public static void register() {
        WatcherHelperConfig.getInstance();
        ChatObserver.subscribe(m -> onLine(ChatObserver.strip(m), System.currentTimeMillis()));
    }

    /** The grade for a speak time, color code included. */
    static String grade(long speakMs) {
        return speakMs < 22_000L ? "§aVroom" : speakMs < 23_000L ? "§eWatcher" : speakMs < 25_000L ? "§6Yawn" : "§cZzz";
    }

    private static String sec(long ms) {
        return String.format(Locale.ROOT, "%.2fs", ms / 1000.0);
    }

    /** One chat line (stripped) at {@code now}; public so the testkit can drive a blood room with exact times. */
    public static synchronized void onLine(String plain, long now) {
        WatcherHelperConfig cfg = WatcherHelperConfig.getInstance();
        if (!cfg.isEnabled() || !DungeonState.isInDungeon()) {
            return;
        }
        if ("Starting in 1 second.".equals(plain)) {
            doorMs = 0L;
            speakMs = 0L;
            WatcherMoveTracker.reset();
        } else if (SplitTimersFeature.BLOOD_OPENED.pattern().matcher(plain).matches()) {
            if (doorMs == 0L) {
                doorMs = now;
                WatcherMoveTracker.onBloodDoorOpened();
            }
        } else if (SplitTimersFeature.WATCHER_FIGHT.matcher(plain).matches()) {
            if (doorMs != 0L && speakMs == 0L) {
                speakMs = now - doorMs;
                WatcherMoveTracker.onDialogueEnd();
                if (cfg.isSpeedTitle()) {
                    DungeonAlertsFeature.showTitle(grade(speakMs), "§7Watcher spoke in " + sec(speakMs), 0, 40, 10);
                }
            }
        } else if (SplitTimersFeature.WATCHER_DONE.pattern().matcher(plain).matches()) {
            if (doorMs != 0L && speakMs != 0L && cfg.isBreakdownChat()) {
                long total = now - doorMs;
                long move = WatcherMoveTracker.getMoveMs();
                String line = "Speak " + sec(speakMs) + (move > speakMs ? ", Move " + sec(move - speakMs) + ", Kill " + sec(total - move) : "")
                        + ", Total " + sec(total);
                ModChat.send("Watcher Helper", ModChat.text(line));
            }
            doorMs = 0L;
            speakMs = 0L;
        }
    }

    /** The last speak time in ms (0 until the Watcher has spoken), for the testkit. */
    public static synchronized long speakMsForTest() {
        return speakMs;
    }
}
