package com.killer560.hub.powdermining;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Powder Tracker: this session's treasure chests and what they gave (gemstone, mithril and glacite powder, and the other
 * drops), with per-hour rates over the time actually spent grinding, and whether a 2x Powder event is running.
 *
 * <p>Chests and items come from {@link PowderChat}'s read of Hypixel's loot block ("CHEST LOCKPICKED" / "LOOT CHEST
 * COLLECTED", then "REWARDS" and one line per reward, "Gemstone Powder x537"), so every chest is counted once. Powder comes
 * from the tab list's "Powders:" widget ({@link com.killer560.hub.mining.MiningIsland}'s twice-a-second read): each rise of
 * a total is a gain, so every buff (2x Powder, Sky Mall, HotM perks, Fallen Star) and every source (ores, goblins, the
 * first ore of the day) counts as the profile really got it (powdertrk-1104: the loot lines alone missed all of that). A
 * drop is spending and only moves the baseline; the baseline is forgotten when the widget goes (off the island, a profile
 * switch); a jump over {@link #MAX_STEP} in one read (a Heart of the Mountain reset's refund) is not a gain. A type the
 * widget does not show falls back to the loot lines, so no powder is counted twice. Grinding time is the time between
 * chest events and powder gains, with any gap over three minutes counted as a break, so the rates do not fall while he is
 * away. The session lives in memory; Reset in the tab (or a restart) starts a new one.
 */
public final class PowderTracker {

    public static final String HUD_ID = "mining_powder_tracker";
    private static final long BREAK_MS = 180_000L;
    /** The most one half-second read can gain: a 2x first-gemstone-of-the-day bonus is ~25k, a HotM reset refunds millions. */
    static final long MAX_STEP = 100_000L;
    private static final String[] TYPES = {"Mithril", "Gemstone", "Glacite"};
    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-powdermining");

    private static int chests = 0;
    private static long gemstone = 0L;
    private static long mithril = 0L;
    private static long glacite = 0L;
    private static final Map<String, Long> ITEMS = new LinkedHashMap<>();
    private static long activeMs = 0L;
    private static long lastEvent = 0L;
    /** The widget's last totals by {@link #TYPES}, -1 while it does not show that type. */
    private static final long[] tabTotal = {-1L, -1L, -1L};
    private static List<String> lastWidget = List.of();

    private PowderTracker() {
    }

    private static void activity() {
        long now = System.currentTimeMillis();
        if (lastEvent > 0L) {
            long gap = now - lastEvent;
            if (gap > 0 && gap <= BREAK_MS) {
                activeMs += gap;
            }
        }
        lastEvent = now;
    }

    static void onUncovered() {
        if (PowderMiningConfig.getInstance().isPowderTracker()) {
            activity();
        }
    }

    /** One loot block, its rewards by name. */
    static void onChest(Map<String, Long> rewards) {
        if (!PowderMiningConfig.getInstance().isPowderTracker()) return;
        activity();
        chests++;
        for (Map.Entry<String, Long> e : rewards.entrySet()) {
            String name = e.getKey();
            long n = e.getValue();
            int type = PowderChat.isPowder(name) ? typeIndex(name.substring(0, name.indexOf(' '))) : -1;
            if (type < 0) {
                ITEMS.merge(name, n, Long::sum);
            } else if (tabTotal[type] < 0) {
                add(type, n); // the widget does not show it: the loot line is all there is
            }
        }
    }

    /** {@link com.killer560.hub.mining.MiningIsland}'s "Powders:" lines ("Gemstone: 3,082,734"), empty when not shown. */
    public static void onPowdersWidget(List<String> lines) {
        if (lines.equals(lastWidget)) return;
        lastWidget = lines;
        long[] now = {-1L, -1L, -1L};
        for (String line : lines) {
            java.util.regex.Matcher m = com.killer560.hub.scoreboard.ScoreboardPattern.TAB_POWDER_LINE.matcher(line);
            Double v = m.matches() ? com.killer560.hub.scoreboard.ScoreboardLine.parse(m.group("amount")) : null;
            if (v != null) now[typeIndex(m.group("type"))] = Math.round(v);
        }
        boolean on = PowderMiningConfig.getInstance().isPowderTracker();
        for (int i = 0; i < 3; i++) {
            long step = now[i] - tabTotal[i];
            if (on && tabTotal[i] >= 0 && now[i] >= 0 && step > 0) {
                if (step > MAX_STEP) {
                    LOGGER.info("[PowderTracker] {} rose {} in one read (a HotM reset?), not counted", TYPES[i], step);
                } else {
                    activity();
                    add(i, step);
                }
            }
            tabTotal[i] = now[i];
        }
    }

    private static int typeIndex(String type) {
        for (int i = 0; i < 3; i++) {
            if (TYPES[i].equals(type)) return i;
        }
        return -1;
    }

    private static void add(int type, long n) {
        switch (type) {
            case 0 -> mithril += n;
            case 1 -> gemstone += n;
            default -> glacite += n;
        }
    }

    public static void reset() {
        chests = 0;
        gemstone = 0L;
        mithril = 0L;
        glacite = 0L;
        ITEMS.clear();
        activeMs = 0L;
        lastEvent = 0L;
    }

    static String compact(long n) {
        if (n >= 1_000_000L) return String.format(Locale.US, "%.2fM", n / 1_000_000.0);
        return n >= 10_000L ? String.format(Locale.US, "%.1fk", n / 1000.0) : String.format(Locale.US, "%,d", n);
    }

    private static String perHour(long n) {
        return activeMs < 30_000L ? "" : " §7(" + compact(Math.round(n * 3_600_000.0 / activeMs)) + "/h)";
    }

    private static String uptime() {
        long s = activeMs / 1000L;
        long h = s / 3600L, m = (s / 60L) % 60L, sec = s % 60L;
        return h > 0 ? h + "h " + m + "m" : m + "m " + sec + "s";
    }

    /** The HUD lines now. */
    public static List<String> lines() {
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        List<String> out = new ArrayList<>();
        out.add("§d§lPowder Tracker" + (PowderChat.doublePowderActive() ? " §b§l2x" : ""));
        out.add("§7Chests: §e" + chests + perHour(chests));
        out.add("§7Gemstone: §d" + compact(gemstone) + perHour(gemstone));
        if (cfg.isPowderTrackerMithril()) {
            out.add("§7Mithril: §2" + compact(mithril) + perHour(mithril));
        }
        if (glacite > 0L) {
            out.add("§7Glacite: §b" + compact(glacite) + perHour(glacite));
        }
        out.add("§7Uptime: §e" + uptime());
        if (cfg.isPowderTrackerItems() && !ITEMS.isEmpty()) {
            List<Map.Entry<String, Long>> top = new ArrayList<>(ITEMS.entrySet());
            top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (int i = 0; i < Math.min(5, top.size()); i++) {
                out.add("§8 " + top.get(i).getKey() + " §7x" + compact(top.get(i).getValue()));
            }
        }
        return out;
    }


    public static int chests() { return chests; }

    public static long gemstone() { return gemstone; }

    public static long mithril() { return mithril; }

    public static long glacite() { return glacite; }

    /** Whether the tab widget shows this powder type ("Gemstone"), so the loot lines are not counted for it. */
    public static boolean widgetShows(String type) { return tabTotal[typeIndex(type)] >= 0; }

    public static long item(String name) { return ITEMS.getOrDefault(name, 0L); }

    public static long activeMs() { return activeMs; }

    /** The movable tracker. */
    public static final class Hud extends BasicHud {
        public Hud() {
            super(HUD_ID, "Powder Tracker", 10, 100);
        }

        @Override
        public int width() {
            int w = 90;
            for (String s : lines()) {
                w = Math.max(w, Minecraft.getInstance().font.width(s));
            }
            return w;
        }

        @Override
        public int height() { return lines().size() * 10; }

        @Override
        public boolean isEnabledInSettings() { return PowderMiningConfig.getInstance().isPowderTrackerRaw(); }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!PowderMiningConfig.getInstance().isPowderTracker() || HudVisibility.hidesHud()) return;
            // Shown on the mining islands (and in the HUD editor); hidden elsewhere.
            if (!com.killer560.hub.miningqol.MiningState.onMiningIsland() && !HudVisibility.editorOpen()) return;
            int yy = y;
            for (String s : lines()) {
                graphics.text(Minecraft.getInstance().font, s, x, yy, 0xFFFFFFFF, true);
                yy += 10;
            }
            HudSeen.markDrawn(HUD_ID);
        }
    }
}
