package com.killer560.hub.gui.profit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/**
 * The pieces a profit screen is built from - headline cards and a net-per-run graph.
 *
 * <p>Separate from the Croesus screen on purpose. killer560 (2026-09-28): "I plan on using that concept for all
 * skills in the future but just dungeons for now" - so nothing in here knows what a dungeon is. Everything
 * arrives as plain numbers and strings, and a mining or fishing tracker can draw the same screen by handing over
 * its own.
 *
 * <p>Colors are the mod's amber, not the blue and purple of the mockup these were drawn from.
 */
public final class ProfitPanels {

    public static final int ACCENT = 0xFFCC6600;
    public static final int BORDER = 0xFF553311;
    public static final int PANEL_BG = 0xFF0D0D0D;
    public static final int INNER_BG = 0xFF080808;
    public static final int DIM = 0xFF7A7A7A;
    public static final int TEXT = 0xFFE8E8E8;
    public static final int GOOD = 0xFF4ADE80;
    public static final int BAD = 0xFFEF4444;

    private ProfitPanels() {
    }

    /**
     * One headline figure: a small label, a large value, and a quieter line underneath.
     *
     * <p>The left edge is a solid bar in {@code stripe}, which is what carries the good/bad read at a glance -
     * the mockup's cards are told apart by that stripe rather than by coloring the whole box, and a full box of
     * red over a losing session is a lot to look at.
     */
    public static void card(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                            String label, String value, int valueColor, String sub, int stripe) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.outline(x, y, w, h, BORDER);
        g.fill(x, y, x + 2, y + h, stripe);
        g.text(font, label.toUpperCase(Locale.ROOT), x + 10, y + 8, DIM, false);
        g.text(font, value, x + 10, y + 22, valueColor, false);
        if (sub != null && !sub.isEmpty()) {
            g.text(font, font.plainSubstrByWidth(sub, w - 16), x + 10, y + h - 14, DIM, false);
        }
    }

    /**
     * One headline figure in the menu's active theme: a raised card with the stripe on its left. Colors given as
     * {@link #GOOD} / {@link #BAD} / {@link #TEXT} / {@link #ACCENT} / {@link #DIM} are mapped to the theme's own.
     */
    public static void themedCard(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                                  String label, String value, int valueColor, String sub, int stripe) {
        com.killer560.hub.gui.ScreenChrome.card(g, x, y, w, h, false);
        com.killer560.hub.gui.ScreenChrome.stripe(g, x, y, h, themed(stripe));
        com.killer560.hub.gui.MenuStyle.text(g, label.toUpperCase(Locale.ROOT), x + 10, y + 8,
                com.killer560.hub.gui.MenuStyle.DIM);
        com.killer560.hub.gui.MenuStyle.text(g, value, x + 10, y + 22, themed(valueColor));
        if (sub != null && !sub.isEmpty()) {
            com.killer560.hub.gui.MenuStyle.text(g, font.plainSubstrByWidth(sub, w - 16), x + 10, y + h - 14,
                    com.killer560.hub.gui.MenuStyle.DIM2);
        }
    }

    /** One of this class's fixed colors as the active theme draws it (anything else made readable on the panel). */
    public static int themed(int c) {
        if (c == GOOD) {
            return com.killer560.hub.gui.ScreenChrome.good();
        }
        if (c == BAD) {
            return com.killer560.hub.gui.ScreenChrome.bad();
        }
        if (c == TEXT) {
            return com.killer560.hub.gui.MenuStyle.TXT;
        }
        if (c == ACCENT) {
            return com.killer560.hub.gui.MenuStyle.OR;
        }
        if (c == DIM) {
            return com.killer560.hub.gui.MenuStyle.DIM;
        }
        return com.killer560.hub.gui.ScreenChrome.readable(c);
    }

    /**
     * Net-per-run bars, newest on the right, with a zero line.
     *
     * <p>Drawn straight rather than averaged because the shape of this data is the point: dungeon profit is a
     * long flat run of small chests with occasional enormous spikes, and any smoothing turns the one thing worth
     * seeing into a gentle slope. Bars narrower than a pixel are merged by taking the largest magnitude in the
     * bucket, so a spike can never be averaged away by the runs either side of it.
     *
     * @param values net profit per run, oldest first; may be empty
     */
    public static void barGraph(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, long[] values) {
        g.fill(x, y, x + w, y + h, INNER_BG);
        g.outline(x, y, w, h, BORDER);
        bars(g, font, x, y, w, h, values, ACCENT, BAD, DIM, 0xFF2A2A2A, false);
    }

    /** {@link #barGraph} in the menu's active theme, in a recessed well (the themed profit screens). */
    public static void themedBarGraph(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, long[] values) {
        com.killer560.hub.gui.ScreenChrome.well(g, x, y, w, h);
        bars(g, font, x, y, w, h, values, com.killer560.hub.gui.MenuStyle.OR, com.killer560.hub.gui.ScreenChrome.bad(),
                com.killer560.hub.gui.MenuStyle.DIM, com.killer560.hub.gui.MenuStyle.TRACK,
                com.killer560.hub.gui.MenuStyle.TEXT_SHADOW);
    }

    private static void bars(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, long[] values, int up,
            int down, int dim, int zero, boolean shadow) {
        if (values.length == 0) {
            String none = "No runs logged yet";
            g.text(font, none, x + (w - font.width(none)) / 2, y + h / 2 - 4, dim, shadow);
            return;
        }
        long max = 0;
        long min = 0;
        for (long v : values) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        if (max == 0 && min == 0) {
            max = 1;
        }
        int plotX = x + 4;
        int plotW = w - 8;
        int plotY = y + 4;
        int plotH = h - 16;
        long span = Math.max(1L, max - min);
        int zeroY = plotY + (int) ((double) max / span * plotH);

        g.fill(plotX, zeroY, plotX + plotW, zeroY + 1, zero);

        int buckets = Math.min(plotW, values.length);
        for (int b = 0; b < buckets; b++) {
            int from = (int) ((long) b * values.length / buckets);
            int to = (int) ((long) (b + 1) * values.length / buckets);
            long peak = 0;
            for (int i = from; i < Math.max(to, from + 1) && i < values.length; i++) {
                if (Math.abs(values[i]) > Math.abs(peak)) {
                    peak = values[i];
                }
            }
            int barX = plotX + (int) ((long) b * plotW / buckets);
            int barW = Math.max(1, plotW / buckets);
            int px = (int) ((double) Math.abs(peak) / span * plotH);
            if (px < 1 && peak != 0) {
                px = 1;
            }
            if (peak >= 0) {
                g.fill(barX, zeroY - px, barX + barW, zeroY, up);
            } else {
                g.fill(barX, zeroY, barX + barW, zeroY + px, down);
            }
        }
        g.text(font, coins(max), plotX, plotY - 1, dim, shadow);
        String lo = coins(min);
        g.text(font, lo, plotX, y + h - 10, dim, shadow);
        String count = values.length + " runs";
        g.text(font, count, x + w - 4 - font.width(count), y + h - 10, dim, shadow);
    }

    /** Short coin formatting - 1.23B / 45.6M / 789K. The screens deal in billions and a raw figure is unreadable. */
    public static String coins(long v) {
        long a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (a >= 1_000_000_000L) {
            return String.format(Locale.US, "%s%.2fB", sign, a / 1_000_000_000.0);
        }
        if (a >= 1_000_000L) {
            return String.format(Locale.US, "%s%.2fM", sign, a / 1_000_000.0);
        }
        if (a >= 1_000L) {
            return String.format(Locale.US, "%s%.1fK", sign, a / 1_000.0);
        }
        return sign + a;
    }

    /** Same, but always carrying an explicit + so a profit reads as one at a glance. */
    public static String signedCoins(long v) {
        return (v >= 0 ? "+" : "") + coins(v);
    }
}
