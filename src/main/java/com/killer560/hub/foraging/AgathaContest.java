package com.killer560.hub.foraging;

import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.islands.RiftFishingHud;
import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.killer560.hub.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agatha Contest Overlay: while the sidebar shows a Garden-style contest block ("Agatha's Contest 2m35s", " EPIC with 2k",
 * " Legendary requires +999", also under "Starlyn Contests / Ends in: 2m 37s"), a HUD shows time left, the score and tier, the
 * next tier's need, the gain rate (from the score changes the sidebar shows) and the projection at that rate. Reads only the
 * game's own sidebar lines, one parse per sidebar change; the HUD reads a list rebuilt then. The line shapes come from a
 * screenshot (the sidebar is not in his logs), so a shape that differs shows nothing rather than a wrong number.
 */
public final class AgathaContest {

    public static final IslandConfig CFG = IslandConfig.of("foraging", "agathacontest").flag("final", true);

    private static final Pattern ACTIVE = Pattern.compile("^(?:Agatha's|Starlyn's?) Contest\\s+(?:(\\d+)m)?\\s*(?:(\\d+)s)?$");
    private static final Pattern ENDS = Pattern.compile("^Ends in:\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?$");
    private static final Pattern TIER = Pattern.compile("^([A-Za-z]+) with ([\\d.,]+[kKmM]?)$");
    private static final Pattern NEXT = Pattern.compile("^([A-Za-z]+) requires \\+([\\d.,]+[kKmM]?)$");
    private static final long FINAL_MS = 15_000L;
    private static final long MIN_RATE_MS = 10_000L;

    private static List<String> seen;
    private static Object level;
    private static volatile List<String> lines = List.of();
    private static long firstAt;
    private static double firstScore;
    private static long lastSeconds;
    private static long endedAt;
    private static List<String> finalLines = List.of();
    private static int ticks;

    private AgathaContest() {
    }

    public static void register() {
        int bit = FeatureBits.register("Agatha Contest Overlay", CFG::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("AgathaContest.tick", bit, AgathaContest::tick));
        HudElementRegistry.register(new RiftFishingHud("foraging_agatha_contest", "Agatha Contest Overlay", 10, 120,
                CFG::isEnabledRaw, AgathaContest::lines));
    }

    public static List<String> lines() {
        return lines;
    }

    private static void tick(Minecraft mc) {
        if (ticks++ % 10 != 0) {
            return;
        }
        if (!CFG.isEnabled() || mc.level == null) {
            reset();
            return;
        }
        if (level != mc.level) {
            level = mc.level;
            reset();
        }
        List<String> sb = ScoreboardData.sidebar();
        if (sb != seen) {
            seen = sb;
            onSidebar(sb, System.currentTimeMillis());
        } else if (!finalLines.isEmpty() && System.currentTimeMillis() - endedAt > FINAL_MS) {
            finalLines = List.of();
            lines = List.of();
        }
    }

    private static void reset() {
        lines = List.of();
        finalLines = List.of();
        seen = null;
        firstAt = 0;
        lastSeconds = 0;
    }

    /** One sidebar read (testkit entry point too); {@code now} in milliseconds. */
    public static synchronized void onSidebar(List<String> sidebar, long now) {
        long seconds = -1;
        String tier = null;
        String next = null;
        double score = -1;
        double need = -1;
        for (String raw : sidebar) {
            String t = ChatObserver.stripCodes(raw);
            if (t == null) {
                continue;
            }
            t = t.trim();
            Matcher m;
            if ((m = ACTIVE.matcher(t)).matches() && (m.group(1) != null || m.group(2) != null)) {
                seconds = secs(m);
            } else if (seconds < 0 && (m = ENDS.matcher(t)).matches() && (m.group(1) != null || m.group(2) != null)) {
                seconds = secs(m);
            } else if ((m = TIER.matcher(t)).matches()) {
                tier = m.group(1).toUpperCase(Locale.ROOT);
                score = number(m.group(2));
            } else if ((m = NEXT.matcher(t)).matches()) {
                next = m.group(1).toUpperCase(Locale.ROOT);
                need = number(m.group(2));
            }
        }
        if (seconds < 0 && tier == null) {
            if (!lines.isEmpty() && finalLines.isEmpty() && CFG.on("final")) {
                // The contest block went away: keep the last numbers for a few seconds.
                endedAt = now;
                finalLines = new ArrayList<>(lines);
                finalLines.set(0, "§6§lContest over");
                lines = finalLines;
            } else if (finalLines.isEmpty()) {
                lines = List.of();
            }
            firstAt = 0;
            return;
        }
        finalLines = List.of();
        if (firstAt == 0 || seconds > lastSeconds + 5 || (score >= 0 && score < firstScore)) {
            firstAt = now;
            firstScore = Math.max(score, 0);
        }
        lastSeconds = seconds;
        List<String> out = new ArrayList<>(6);
        out.add("§6§lAgatha's Contest §f" + (seconds >= 0 ? clock(seconds) : "?"));
        if (score >= 0) {
            out.add("§7Score: §f" + abbreviate(score) + (tier == null ? "" : " §7(§e" + tier + "§7)"));
        }
        double rate = 0;
        if (score >= 0 && now - firstAt >= MIN_RATE_MS && score > firstScore) {
            rate = (score - firstScore) / ((now - firstAt) / 60_000.0);
            out.add("§7Rate: §f" + abbreviate(rate) + "/min");
        }
        if (score >= 0 && seconds >= 0 && rate > 0) {
            double proj = score + rate * seconds / 60.0;
            boolean reaches = next != null && need >= 0 && proj >= score + need;
            out.add("§7Projected: §f" + abbreviate(proj) + " §7(§e" + (reaches ? next : tier) + "§7)");
        }
        if (next != null && need >= 0) {
            String needRate = seconds > 0 ? abbreviate(need / (seconds / 60.0)) + "/min" : "now";
            out.add("§7" + next + ": §f+" + abbreviate(need) + " §7needs §f" + needRate);
        }
        lines = out;
    }

    private static long secs(Matcher m) {
        return (m.group(1) == null ? 0 : Long.parseLong(m.group(1)) * 60) + (m.group(2) == null ? 0 : Long.parseLong(m.group(2)));
    }

    /** "2k" -> 2000, "10.8k" -> 10800, "1.2m" -> 1200000, "2,300" -> 2300. */
    static double number(String s) {
        String t = s.replace(",", "").toLowerCase(Locale.ROOT);
        double mul = 1;
        if (t.endsWith("k")) {
            mul = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mul = 1_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            return Double.parseDouble(t) * mul;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String abbreviate(double v) {
        if (v >= 1_000_000) {
            return trim(v / 1_000_000) + "m";
        }
        return v >= 1_000 ? trim(v / 1_000) + "k" : String.valueOf(Math.round(v));
    }

    private static String trim(double v) {
        String t = String.format(Locale.ROOT, "%.2f", v);
        return t.replaceAll("\\.?0+$", "");
    }

    private static String clock(long s) {
        return s / 60 + "m" + String.format(Locale.ROOT, "%02d", s % 60) + "s";
    }
}
