package com.killer560.hub.profileviewer.screen;

import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.profileviewer.data.SkyblockWeight;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.ACCENT;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.BORDER;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.DIM;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.MAXED;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.TEXT;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.VALUE;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.inside;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.style;

/**
 * The "Weight" page: Senither and Lily weight side by side, computed locally by {@link SkyblockWeight} from the
 * profile JSON already fetched (no extra requests). Totals, a skills / slayers / dungeons summary with overflow in
 * dim text, and a scrollable per-component breakdown; every number has a hover tooltip.
 */
final class WeightPage {

    private static final int ROW_H = 10;

    private final ProfileViewerScreen s;
    private SbProfile cachedProfile;
    private SkyblockWeight.Both cached;

    WeightPage(ProfileViewerScreen screen) {
        this.s = screen;
    }

    void reset() {
        cachedProfile = null;
        cached = null;
    }

    private SkyblockWeight.Both weights(SbProfile p) {
        if (p != cachedProfile || cached == null) {
            cached = SkyblockWeight.compute(p);
            cachedProfile = p;
        }
        return cached;
    }

    void draw(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        SkyblockWeight.Both w = weights(p);
        int gap = 6;
        int colW = (s.contentW - gap) / 2;
        int top = s.contentY + 84;
        int visible = Math.max(1, (s.contentY + s.contentH - 4 - top) / ROW_H);
        int rowsSen = rows(w.senither()).size();
        int rowsLily = rows(w.lily()).size();
        int maxScroll = Math.max(0, Math.max(rowsSen, rowsLily) - visible);
        s.pageScroll = Math.max(0, Math.min(s.pageScroll, maxScroll));

        column(g, w.senither(), s.contentX, colW, top, visible, mx, my,
                "Senither's weight (hypixel-skyblock-facade formulas).",
                "Overflow: skill XP past level 50/60, slayer XP past 1M, dungeon XP past level 50.");
        column(g, w.lily(), s.contentX + colW + gap, s.contentW - colW - gap, top, visible, mx, my,
                "LilyWeight by Antonio32A (lilyweight library formulas).",
                "Overflow: skill XP past level 60. Dungeons = catacombs XP + floor completions.");
    }

    private Font f() {
        return s.font();
    }

    private static Component line(String str, int color) {
        return Component.literal(str).withStyle(style(color));
    }

    private boolean hover(int mx, int my, int x, int y, int w, int h) {
        return inside(mx, my, x, y, w, h) && !s.dropdownOpen;
    }

    private void tip(List<Component> lines) {
        if (!s.dropdownOpen) {
            s.pendingTooltip = lines;
        }
    }

    static String num(double v) {
        return String.format(Locale.US, "%,.1f", v);
    }

    static String exact(double v) {
        return String.format(Locale.US, "%,.2f", v);
    }

    /** Draws "value" (VALUE) with "+overflow" (DIM) right-aligned at {@code right}. */
    private void valueWithOverflow(GuiGraphicsExtractor g, double weight, double overflow, int right, int y, int valueColor) {
        String ovf = overflow > 0 ? " +" + num(overflow) : "";
        int ovfW = f().width(ovf);
        if (!ovf.isEmpty()) {
            g.text(f(), ovf, right - ovfW, y, DIM, false);
        }
        String val = num(weight);
        g.text(f(), val, right - ovfW - f().width(val), y, valueColor, false);
    }

    private void column(GuiGraphicsExtractor g, SkyblockWeight.Result r, int x, int w, int listTop, int visible,
                        int mx, int my, String about1, String about2) {
        s.box(g, x, s.contentY, w, s.contentH);
        int lx = x + 6;
        int right = x + w - 8;
        int y = s.contentY + 5;

        // Title
        String title = r.system() + " Weight";
        g.text(f(), title, lx, y, ACCENT, false);
        if (hover(mx, my, lx, y - 1, f().width(title), 10)) {
            tip(List.of(line(title, VALUE), line(about1, DIM), line(about2, DIM),
                    line("Calculated locally from this profile's API data.", DIM)));
        }
        y += 13;

        // Big total
        String total = num(r.total());
        g.pose().pushMatrix();
        g.pose().translate(lx, y);
        g.pose().scale(1.5f, 1.5f);
        g.text(f(), total, 0, 0, MAXED, false);
        g.pose().popMatrix();
        int totalW = (int) Math.ceil(f().width(total) * 1.5f);
        if (hover(mx, my, lx, y - 1, Math.max(totalW, w - 12), 14)) {
            tip(summaryTooltip(r));
        }
        y += 15;
        g.text(f(), f().plainSubstrByWidth(num(r.weight()) + " + " + num(r.overflow()) + " overflow", w - 12), lx, y, DIM, false);
        y += 13;

        // Category summary
        for (SkyblockWeight.Group grp : List.of(r.skills(), r.slayers(), r.dungeons())) {
            g.text(f(), grp.name(), lx, y, TEXT, false);
            boolean apiOff = grp == r.skills() && !r.skillsApi();
            if (apiOff) {
                String off = "API off";
                g.text(f(), off, right - f().width(off), y, DIM, false);
            } else {
                valueWithOverflow(g, grp.weight(), grp.overflow(), right, y, VALUE);
            }
            if (hover(mx, my, lx - 2, y - 1, w - 8, ROW_H + 1)) {
                tip(groupTooltip(r, grp));
            }
            y += 11;
        }

        // Breakdown list
        int sepY = listTop - 4;
        g.fill(x + 4, sepY, x + w - 4, sepY + 1, BORDER);
        List<Row> rows = rows(r);
        int start = s.pageScroll;
        for (int i = start; i < rows.size() && i - start < visible; i++) {
            Row row = rows.get(i);
            int ry = listTop + (i - start) * ROW_H;
            if (row.part() == null) {
                g.text(f(), row.group().name(), lx, ry, ACCENT, false);
                if (row.group() == r.skills() && !r.skillsApi()) {
                    String off = "Skills API off";
                    g.text(f(), off, right - f().width(off), ry, DIM, false);
                } else {
                    valueWithOverflow(g, row.group().weight(), row.group().overflow(), right, ry, VALUE);
                }
                if (hover(mx, my, lx - 2, ry - 1, w - 8, ROW_H)) {
                    tip(groupTooltip(r, row.group()));
                }
            } else {
                SkyblockWeight.Part part = row.part();
                g.text(f(), f().plainSubstrByWidth(part.name(), w / 2), lx + 6, ry, part.total() > 0 ? TEXT : DIM, false);
                valueWithOverflow(g, part.weight(), part.overflow(), right, ry, part.total() > 0 ? VALUE : DIM);
                if (hover(mx, my, lx - 2, ry - 1, w - 8, ROW_H)) {
                    List<Component> t = new ArrayList<>();
                    t.add(line(r.system() + " · " + part.name(), VALUE));
                    t.add(line("Weight: " + exact(part.weight()), TEXT));
                    if (part.overflow() > 0) {
                        t.add(line("Overflow: " + exact(part.overflow()), DIM));
                        t.add(line("Total: " + exact(part.total()), TEXT));
                    }
                    for (String d : part.detail()) {
                        t.add(line(d, DIM));
                    }
                    tip(t);
                }
            }
        }
        if (rows.size() > visible) {
            int trackX = x + w - 4;
            int trackH = visible * ROW_H;
            g.fill(trackX, listTop, trackX + 2, listTop + trackH, 0xFF1A1A1A);
            int thumbH = Math.max(8, trackH * visible / rows.size());
            int maxScroll = Math.max(1, rows.size() - visible);
            int thumbY = listTop + (trackH - thumbH) * Math.min(start, maxScroll) / maxScroll;
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, ACCENT);
        }
    }

    private record Row(SkyblockWeight.Group group, SkyblockWeight.Part part) {
    }

    private static List<Row> rows(SkyblockWeight.Result r) {
        List<Row> out = new ArrayList<>();
        for (SkyblockWeight.Group grp : List.of(r.skills(), r.slayers(), r.dungeons())) {
            out.add(new Row(grp, null));
            for (SkyblockWeight.Part p : grp.parts()) {
                out.add(new Row(grp, p));
            }
        }
        return out;
    }

    private static List<Component> summaryTooltip(SkyblockWeight.Result r) {
        List<Component> t = new ArrayList<>();
        t.add(line(r.system() + " Weight: " + exact(r.total()), VALUE));
        t.add(line("Base: " + exact(r.weight()) + "   Overflow: " + exact(r.overflow()), DIM));
        for (SkyblockWeight.Group grp : List.of(r.skills(), r.slayers(), r.dungeons())) {
            if (grp == r.skills() && !r.skillsApi()) {
                t.add(line(grp.name() + ": Skills API off", DIM));
                continue;
            }
            t.add(line(grp.name() + ": " + exact(grp.weight())
                    + (grp.overflow() > 0 ? "  (+" + exact(grp.overflow()) + " overflow)" : ""), TEXT));
        }
        return t;
    }

    private static List<Component> groupTooltip(SkyblockWeight.Result r, SkyblockWeight.Group grp) {
        List<Component> t = new ArrayList<>();
        t.add(line(r.system() + " · " + grp.name() + ": " + exact(grp.total()), VALUE));
        if (grp == r.skills() && !r.skillsApi()) {
            t.add(line("The Skills API is off for this profile.", DIM));
            return t;
        }
        if (grp.overflow() > 0) {
            t.add(line("Base " + exact(grp.weight()) + " + overflow " + exact(grp.overflow()), DIM));
        }
        for (SkyblockWeight.Part p : grp.parts()) {
            t.add(line("  " + p.name() + ": " + exact(p.weight())
                    + (p.overflow() > 0 ? " (+" + exact(p.overflow()) + ")" : ""), p.total() > 0 ? TEXT : DIM));
        }
        return t;
    }
}
