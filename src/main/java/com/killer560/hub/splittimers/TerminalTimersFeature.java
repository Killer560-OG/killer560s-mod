package com.killer560.hub.splittimers;

import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

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
 * Replaces this mod's older, differently-worded "Device/Lever Times" suffix (deleted 2026-09-29, with the
 * P3 progress diagnostics that were all it had left) and the Terminal Solver's own "Announce Completion
 * Time" toggle.
 */
public final class TerminalTimersFeature {

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

    // --- breakdown (Devonian): per player {terminals, devices, levers} since Goldor's opening line, posted when the core opens ---
    private static final java.util.Map<String, int[]> breakdown = new java.util.LinkedHashMap<>();
    private static final int BREAKDOWN_MAX_PLAYERS = 16;

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
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("TerminalTimersFeature", client -> {
            if (client.level != lastLevel) {
                lastLevel = client.level;
                breakdown.clear();
                resetSection(true); // Odin: LevelEvent.Load
            }
        }));
    }

    // Splits

    private static Component rewrite(Component message, String plain) {
        if (plain == null || !TerminalTimersConfig.getInstance().isSplits()) return message;
        Matcher m = COMPLETE_REGEX.matcher(plain);
        if (!m.find()) return message;
        long now = System.currentTimeMillis();
        // The same real line can be offered twice (e.g. a mod re-adding its own copy) - only time it once.
        if (plain.equals(lastRewrittenPlain) && now - lastRewrittenAtMs < 250L) return message;
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
        // Orange-themed (2026-09-14): our parts - the player name, the (n/m) count and the appended times - use
        // the mod's orange palette; the "activated/completed a X!" verb stays green (it means "done"). Same
        // plain text as before, so every regex on the stripped line still matches. Any click/hover event the
        // original line carried (e.g. Click Translate / Copy Chat's wrap) is carried over.
        MutableComponent rewritten = Component.empty()
                .append(ModChat.colored(name, ThemeAccent.onDark(ThemeAccent.accent(ModChat.ORANGE))))
                .append(ModChat.good(" " + verb + " a " + type + "! "))
                .append(ModChat.dim("("))
                .append(ModChat.value(String.valueOf(current)))
                .append(ModChat.dim("/"))
                .append(ModChat.value(String.valueOf(total)))
                .append(ModChat.dim(") ("))
                .append(ModChat.value(fmt(section) + "s"))
                .append(ModChat.dim(" | "))
                .append(ModChat.value(fmt(phase) + "s"))
                .append(ModChat.dim(")"));
        var originalStyle = message.getStyle();
        if (originalStyle.getClickEvent() != null) {
            rewritten.withStyle(style -> style.withClickEvent(originalStyle.getClickEvent()));
        }
        if (originalStyle.getHoverEvent() != null) {
            rewritten.withStyle(style -> style.withHoverEvent(originalStyle.getHoverEvent()));
        }
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
        String plain = ChatObserver.strip(message);
        if (plain == null) return;
        Minecraft client = Minecraft.getInstance();
        Matcher m = plain.contains("d a ") ? COMPLETE_REGEX.matcher(plain) : null; // "activated a"/"completed a"
        if (m != null && m.find()) {
            if (TerminalTimersConfig.getInstance().isBreakdown()) {
                tally(m.group(1), m.group(3));
            }
            if (m.group(3).equals("terminal") && client.player != null
                    && m.group(1).equals(client.player.getGameProfile().name())) {
                ownTerminalLineAtMs = System.currentTimeMillis();
                com.killer560.hub.terminals.TerminalExtras.onOwnTerminalDone();
                tryPostSolve();
            }
            return;
        }
        TerminalTimersConfig cfg = TerminalTimersConfig.getInstance();
        boolean splits = cfg.isSplits();
        if (plain.equals(GOLDOR_START)) {
            breakdown.clear();
            if (splits) resetSection(true);
        } else if (plain.equals(GATE_DESTROYED)) {
            if (!splits) return;
            if (completedCurrent == completedTotal) {
                resetSection(false);
            } else {
                gateBlown = true;
            }
        } else if (plain.equals(CORE_OPENING)) {
            if (splits) {
                resetSection(false);
                MutableComponent times = ModChat.text("Times: ");
                for (int i = 0; i < sectionTimes.size(); i++) {
                    if (i > 0) {
                        times.append(ModChat.dim(" | "));
                    }
                    times.append(ModChat.value(fmt(sectionTimes.get(i)) + "s"));
                }
                ModChat.send("Terminal Timers", times, ModChat.dim(", "), ModChat.text("Total: "),
                        ModChat.value(fmt(seconds(phaseTimerMs)) + "s"));
            }
            if (cfg.isBreakdown()) postBreakdown();
        }
    }

    /** Counts one objective line for {@code name}: slot 0 terminals, 1 devices, 2 levers. Bounded: a run has at most five players. */
    private static void tally(String name, String kind) {
        int[] counts = breakdown.get(name);
        if (counts == null) {
            if (breakdown.size() >= BREAKDOWN_MAX_PLAYERS) return;
            counts = new int[3];
            breakdown.put(name, counts);
        }
        counts[kind.equals("terminal") ? 0 : kind.equals("device") ? 1 : 2]++;
    }

    /** One chat line per player, most objectives first, then the tally starts over for the next run. */
    private static void postBreakdown() {
        if (breakdown.isEmpty()) return;
        List<java.util.Map.Entry<String, int[]>> rows = new ArrayList<>(breakdown.entrySet());
        rows.sort((a, b) -> Integer.compare(b.getValue()[0] + b.getValue()[1] + b.getValue()[2],
                a.getValue()[0] + a.getValue()[1] + a.getValue()[2]));
        for (var row : rows) {
            int[] c = row.getValue();
            ModChat.send("Terminal Breakdown", ModChat.colored(row.getKey(), ThemeAccent.onDark(ThemeAccent.accent(ModChat.ORANGE))),
                    ModChat.text(": "), ModChat.value(String.valueOf(c[0])), ModChat.text(" terminals, "),
                    ModChat.value(String.valueOf(c[1])), ModChat.text(" devices, "),
                    ModChat.value(String.valueOf(c[2])), ModChat.text(" levers"));
        }
        breakdown.clear();
    }

    /** What {@link #postBreakdown} would say, per player as name, terminals, devices, levers (testkit). */
    static List<String> breakdownForTest() {
        List<String> out = new ArrayList<>();
        breakdown.forEach((k, c) -> out.add(k + " " + c[0] + " " + c[1] + " " + c[2]));
        return out;
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

    // Solve times

    /** Called by {@code TerminalSolverFeature} when it stops tracking a terminal (closed/replaced). */
    public static void onTerminalClosed(String terminalName, double seconds) {
        pendingSolveName = terminalName;
        pendingSolveSeconds = seconds;
        pendingSolveAtMs = System.currentTimeMillis();
        tryPostSolve();
    }

    private static void tryPostSolve() {
        if (pendingSolveName == null || ownTerminalLineAtMs == 0L) return;
        if (Math.abs(pendingSolveAtMs - ownTerminalLineAtMs) > SOLVE_MATCH_WINDOW_MS) {
            // Only the older of the two is stale - keep the newer one waiting for its partner.
            if (pendingSolveAtMs < ownTerminalLineAtMs) {
                pendingSolveName = null;
            } else {
                ownTerminalLineAtMs = 0L;
            }
            return;
        }
        if (TerminalTimersConfig.getInstance().isSolveTimes() && DungeonState.isInDungeon()) {
            ModChat.send("Terminal Timers", ModChat.value(pendingSolveName), ModChat.text(" solved in "),
                    ModChat.value(String.format(Locale.US, "%.2fs", pendingSolveSeconds)), ModChat.text("!"));
        }
        pendingSolveName = null;
        ownTerminalLineAtMs = 0L;
    }


    private static float seconds(long sinceMs) { return sinceMs <= 0L ? 0f : (System.currentTimeMillis() - sinceMs) / 1000f; }

    private static String fmt(float seconds) { return String.format(Locale.US, "%.2f", seconds); }
}
