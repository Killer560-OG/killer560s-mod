package com.killer560.hub.splittimers;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Terminal Timers" - killer560's own request (2026-09-14): "a setting called terminal timers that after each
 * completed terminal sends a client side message about how fast it was and it should include devices. Bundle
 * the simon says one into this as well... messages like [Killer560 completed a device! (0/7)] should have times
 * next to them, reference odin." Ported from Odin's own {@code TerminalTimes.kt} (reference clone under the
 * odin-ref scratchpad), both halves:
 * <ul>
 * <li><b>Solve times</b> (Odin "Terminal Times"): "§a&lt;Terminal&gt; §7solved in §6X.XXs§7!" for each terminal YOU
 * complete. The duration is {@code TerminalSolverFeature}'s GUI open-to-close measurement; it's only posted once
 * your own "completed a terminal!" line confirms the terminal was actually solved (not just closed), in
 * whichever order the close and the chat line arrive.</li>
 * <li><b>Splits</b> (Odin "Terminal Splits"): every "&lt;name&gt; activated/completed a terminal/device/lever!
 * (n/m)" line is rewritten to Odin's exact format with "§8(§7&lt;section&gt;s §8| §7&lt;phase&gt;s§8)" appended -
 * section time since the current P3 section started, phase time since Goldor's "Who dares trespass" line - and
 * "Times: a | b | c | d, Total: X" is posted when the core opens. Section bookkeeping (gate blown, n/m wrap) is
 * Odin's, line for line. Real time, like Odin's default "Use Real Time".</li>
 * <li><b>Simon Says time</b>: gates {@code SimonSaysFeature}'s own "Whole device solved" message.</li>
 * </ul>
 * Replaces this mod's older, differently-worded "Device/Lever Times" suffix ({@link DeviceTimesFeature} keeps
 * only its P3 diagnostics) and the Terminal Solver's own "Announce Completion Time" toggle.
 */
public final class TerminalTimersFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-terminaltimers");

    // Odin's regex, plus an optional trailing suffix so a line another mod already annotated still matches.
    private static final Pattern COMPLETE_REGEX =
            Pattern.compile("^(\\w{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)(?:\\s.*)?$");
    private static final String GATE_DESTROYED = "The gate has been destroyed!";
    private static final String GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?";
    private static final String CORE_OPENING = "The Core entrance is opening!";
    private static final long SOLVE_MATCH_WINDOW_MS = 3000L;

    // --- splits (Odin's own state) ---
    private static int completedCurrent = 0;
    private static int completedTotal = 7;
    private static final List<Float> sectionTimes = new ArrayList<>();
    private static boolean gateBlown = false;
    private static long sectionTimerMs = 0L;
    private static long phaseTimerMs = 0L;
    private static Object lastLevel = null;
    private static String lastRewrittenPlain = null;
    private static long lastRewrittenAtMs = 0L;

    // --- solve times: pair the GUI close with the player's own completion line, in either order ---
    private static String pendingSolveName = null;
    private static double pendingSolveSeconds = 0;
    private static long pendingSolveAtMs = 0L;
    private static long ownTerminalLineAtMs = 0L;

    private TerminalTimersFeature() {
    }

    public static void register() {
        ChatObserver.addRewriter(TerminalTimersFeature::rewrite);
        ChatObserver.subscribe(TerminalTimersFeature::onChatLine);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != lastLevel) {
                lastLevel = client.level;
                resetSection(true); // Odin: LevelEvent.Load
            }
        });
    }

    // ------------------------------------------------------------------
    // Splits
    // ------------------------------------------------------------------

    private static Component rewrite(Component message, String plain) {
        if (plain == null || !TerminalTimersConfig.getInstance().isSplits()) {
            return message;
        }
        Matcher m = COMPLETE_REGEX.matcher(plain);
        if (!m.find()) {
            return message;
        }
        long now = System.currentTimeMillis();
        // The same real line can be offered twice (e.g. a mod re-adding its own copy) - only time it once.
        if (plain.equals(lastRewrittenPlain) && now - lastRewrittenAtMs < 250L) {
            return message;
        }
        lastRewrittenPlain = plain;
        lastRewrittenAtMs = now;
        String name = m.group(1);
        String verb = m.group(2);
        String type = m.group(3);
        int current = Integer.parseInt(m.group(4));
        int total = Integer.parseInt(m.group(5));
        if (phaseTimerMs == 0L) {
            // No Goldor line seen (joined mid-phase, or p3sim skipped it) - start both timers here rather than
            // printing time since the epoch.
            resetSection(true);
        }
        float section = seconds(sectionTimerMs);
        float phase = seconds(phaseTimerMs);
        Component rewritten = Component.literal(String.format(Locale.US,
                "§6%s §a%s a %s! (§c%d§a/%d) §8(§7%ss §8| §7%ss§8)", name, verb, type, current, total, fmt(section), fmt(phase)));
        LOGGER.info("[TerminalTimers] {} {} a {} ({}/{}) section={}s phase={}s gateBlown={}", name, verb, type, current,
                total, fmt(section), fmt(phase), gateBlown);
        // Odin's section bookkeeping, verbatim.
        if ((current == total && gateBlown) || current < completedCurrent) {
            resetSection(false);
        } else {
            completedCurrent = current;
            completedTotal = total;
        }
        return rewritten;
    }

    private static void onChatLine(Component message) {
        String plain = net.minecraft.ChatFormatting.stripFormatting(message.getString());
        if (plain == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Matcher m = COMPLETE_REGEX.matcher(plain);
        if (m.find()) {
            if (m.group(3).equals("terminal") && client.player != null
                    && m.group(1).equals(client.player.getGameProfile().name())) {
                ownTerminalLineAtMs = System.currentTimeMillis();
                tryPostSolve();
            }
            return;
        }
        if (!TerminalTimersConfig.getInstance().isSplits()) {
            return;
        }
        if (plain.equals(GATE_DESTROYED)) {
            if (completedCurrent == completedTotal) {
                resetSection(false);
            } else {
                gateBlown = true;
            }
        } else if (plain.equals(GOLDOR_START)) {
            resetSection(true);
        } else if (plain.equals(CORE_OPENING)) {
            resetSection(false);
            StringBuilder times = new StringBuilder();
            for (int i = 0; i < sectionTimes.size(); i++) {
                times.append(i == 0 ? "" : " §8| ").append("§a").append(fmt(sectionTimes.get(i))).append('s');
            }
            send(String.format(Locale.US, "§bTimes: %s§8, §bTotal: §a%ss", times, fmt(seconds(phaseTimerMs))));
            LOGGER.info("[TerminalTimers] Core opening - section times {} total {}s", sectionTimes, fmt(seconds(phaseTimerMs)));
        }
    }

    private static void resetSection(boolean full) {
        long now = System.currentTimeMillis();
        if (full) {
            sectionTimes.clear();
            phaseTimerMs = now;
        } else {
            sectionTimes.add(seconds(sectionTimerMs));
        }
        completedCurrent = 0;
        completedTotal = 7;
        sectionTimerMs = now;
        gateBlown = false;
    }

    // ------------------------------------------------------------------
    // Solve times
    // ------------------------------------------------------------------

    /** Called by {@code TerminalSolverFeature} when it stops tracking a terminal (closed/replaced). */
    public static void onTerminalClosed(String terminalName, double seconds) {
        pendingSolveName = terminalName;
        pendingSolveSeconds = seconds;
        pendingSolveAtMs = System.currentTimeMillis();
        tryPostSolve();
    }

    private static void tryPostSolve() {
        if (pendingSolveName == null || ownTerminalLineAtMs == 0L) {
            return;
        }
        if (Math.abs(pendingSolveAtMs - ownTerminalLineAtMs) > SOLVE_MATCH_WINDOW_MS) {
            // Only the older of the two is stale - keep the newer one waiting for its partner.
            if (pendingSolveAtMs < ownTerminalLineAtMs) {
                pendingSolveName = null;
            } else {
                ownTerminalLineAtMs = 0L;
            }
            return;
        }
        LOGGER.info("[TerminalTimers] {} solved in {}s", pendingSolveName, String.format(Locale.US, "%.2f", pendingSolveSeconds));
        if (TerminalTimersConfig.getInstance().isSolveTimes() && DungeonState.isInDungeon()) {
            send(String.format(Locale.US, "§a%s §7solved in §6%.2fs§7!", pendingSolveName, pendingSolveSeconds));
        }
        pendingSolveName = null;
        ownTerminalLineAtMs = 0L;
    }

    // ------------------------------------------------------------------

    private static float seconds(long sinceMs) {
        return sinceMs <= 0L ? 0f : (System.currentTimeMillis() - sinceMs) / 1000f;
    }

    private static String fmt(float seconds) {
        return String.format(Locale.US, "%.2f", seconds);
    }

    private static void send(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("§6[Terminal Timers] §r" + text));
        }
    }
}
