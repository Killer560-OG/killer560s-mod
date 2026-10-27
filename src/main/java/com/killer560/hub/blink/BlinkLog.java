package com.killer560.hub.blink;

import com.killer560.hub.util.ModLog;
import org.slf4j.Logger;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Blink's log (killer560, 2026-10-26: "make sure you have all the loggers you need" - he tests on Hypixel and sends his
 * latest.log). Every Blink event is one INFO line starting {@code [Blink]}, logger {@code killer560smod-blink}. A dev or
 * cheat jar (his own, deploy-to-instances.py) always writes them; a release jar (the GitHub builds his friends run) writes
 * them only while Blink's Debug Log setting is on (see {@link ModLog#keptWhen}). The last {@link #KEEP} lines are also
 * kept in memory for {@code /blink debug}, whichever jar it is. The Session Recorder copies them too.
 */
final class BlinkLog {

    static final Logger LOGGER = ModLog.keptWhen("killer560smod-blink", () -> BlinkConfig.getInstance().isDebugLog());

    static final int KEEP = 40;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();

    private BlinkLog() {
    }

    /** One event: the log line, and the in-memory list {@code /blink debug} shows. Any thread. */
    static void event(String message) {
        synchronized (RECENT) {
            RECENT.addLast(LocalTime.now().format(TIME) + " " + message);
            while (RECENT.size() > KEEP) {
                RECENT.removeFirst();
            }
        }
        LOGGER.info("[Blink] {}", message);
    }

    /** {@link String#format} with {@link Locale#ROOT}, then {@link #event(String)}. */
    static void event(String format, Object... args) {
        event(String.format(Locale.ROOT, format, args));
    }

    /** The last {@code n} events, oldest first. */
    static List<String> recent(int n) {
        synchronized (RECENT) {
            List<String> all = new ArrayList<>(RECENT);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    static int recentCount() {
        synchronized (RECENT) {
            return RECENT.size();
        }
    }

    static String keyName(int code) {
        return code == com.killer560.hub.util.KeyUtil.NONE ? "unbound" : com.killer560.hub.util.KeyUtil.bindDisplayName(code);
    }

    /** The settings in force, for the line that starts a hold or a charge. */
    static String settings() {
        BlinkConfig c = BlinkConfig.getInstance();
        return String.format(Locale.ROOT, "Charge Ticks %d (%s, key %s), Hold Ticks %d (key %s), HUD %s, box %s, chat %s",
                c.getChargeTicks(), c.getChargeMode().label, keyName(c.getChargeKeyCode()), c.getHoldTicks(),
                keyName(c.getHoldKeyCode()), c.isShowHud() ? "on" : "off", c.isShowServerBox() ? "on" : "off",
                c.isChatMessages() ? "on" : "off");
    }

    static String fmt(net.minecraft.world.phys.Vec3 v) {
        return v == null ? "-" : String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
