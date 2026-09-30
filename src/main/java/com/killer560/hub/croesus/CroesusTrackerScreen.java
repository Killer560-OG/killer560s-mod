package com.killer560.hub.croesus;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.profit.ProfitPanels;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The dungeon profit tracker, opened with {@code /croesus}.
 *
 * <p>Laid out from the concept killer560 sent on 2026-09-28 - an Overview with three headline figures, a rare
 * drops list and a net-per-run graph, then By Floor and Loot Log - in this mod's amber rather than the blue and
 * purple of the picture. He also said the same shape is meant to serve every skill later, so the cards and the
 * graph live in {@link ProfitPanels} and know nothing about dungeons; only this screen does.
 *
 * <p>Everything is still read out of the existing {@code config/killer560smod-croesus-log.json}. The concept
 * needed no new tracking at all: {@link CroesusProfitLog} already kept per-floor totals, a per-item index and
 * every claim with its cost, value and profit, which is exactly what the three tabs want. There is no second
 * store and no new file.
 */
public class CroesusTrackerScreen extends Screen {

    private static final int ROW_H = 12;

    /** Which tab the body is showing. */
    public enum View {
        OVERVIEW("Overview"), BY_FLOOR("By Floor"), LOOT_LOG("Loot Log");

        final String label;

        View(String label) {
            this.label = label;
        }
    }

    private record Row(String left, int leftColor, String middle, String right, int rightColor) {
    }

    private final Screen parent;
    private View view;
    private List<Row> rows = List.of();

    /** Session or all-time, and which floor - the two switches on the concept's performance panel. */
    private boolean sessionOnly = false;
    private String floorFilter = CroesusProfitLog.ALL;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int scroll = 0;

    public CroesusTrackerScreen(Screen parent, View view) {
        super(Component.literal("Dungeon Profit"));
        this.parent = parent;
        this.view = view == null ? View.OVERVIEW : view;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, Math.max(360, Math.min((int) (this.width * 0.8), 620)));
        panelH = Math.min(this.height - 20, Math.max(220, Math.min((int) (this.height * 0.85), 460)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int bw = Math.min(90, (panelW - 24) / 4);
        int x = panelX + 6;
        for (View candidate : View.values()) {
            final View target = candidate;
            addRenderableWidget(SettingsButtonWidget.builder(
                    Component.literal((candidate == view ? "§6" : "§f") + candidate.label), btn -> {
                        view = target;
                        scroll = 0;
                        rebuildWidgets();
                    }).bounds(x, panelY + 34, bw, 18).build());
            x += bw + 4;
        }

        if (view == View.OVERVIEW) {
            // Session / Overall and the floor filter, as in the concept's performance panel.
            addRenderableWidget(SettingsButtonWidget.builder(
                    Component.literal(sessionOnly ? "§6Session" : "§fOverall"), btn -> {
                        sessionOnly = !sessionOnly;
                        rebuildWidgets();
                    }).bounds(panelX + panelW - bw * 2 - 10, panelY + 34, bw, 18).build());
            addRenderableWidget(SettingsButtonWidget.builder(
                    Component.literal("§f" + floorFilter), btn -> {
                        floorFilter = nextFloor();
                        rebuildWidgets();
                    }).bounds(panelX + panelW - bw - 6, panelY + 34, bw, 18).build());
        } else {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset totals"), btn -> {
                CroesusProfitLog.resetTotals();
                rebuildWidgets();
            }).bounds(panelX + panelW - bw - 6, panelY + 34, bw, 18).build());
        }

        // The Overview draws its own panels; the other two are plain scrolling lists.
        listX = panelX + 6;
        listY = panelY + 58;
        listW = panelW - 12;
        listH = panelH - 58 - 8;
        rows = view == View.OVERVIEW ? List.of() : buildRows();
    }

    /** Floors seen in the log, so the filter only ever offers something there is data for. */
    private String nextFloor() {
        List<String> floors = new ArrayList<>(floorsSeen());
        int i = floors.indexOf(floorFilter);
        return floors.get((i + 1) % floors.size());
    }

    private Set<String> floorsSeen() {
        Set<String> floors = new LinkedHashSet<>();
        floors.add(CroesusProfitLog.ALL);
        for (String key : CroesusProfitLog.allTime().keySet()) {
            if (!CroesusProfitLog.ALL.equals(key)) {
                floors.add(key);
            }
        }
        return floors;
    }

    /** The totals behind the three cards, honouring both switches. */
    private CroesusProfitLog.Totals selectedTotals() {
        Map<String, CroesusProfitLog.Totals> map =
                sessionOnly ? CroesusProfitLog.session() : CroesusProfitLog.allTime();
        CroesusProfitLog.Totals out = new CroesusProfitLog.Totals();
        for (Map.Entry<String, CroesusProfitLog.Totals> e : map.entrySet()) {
            if (CroesusProfitLog.ALL.equals(e.getKey())) {
                continue; // it is a roll-up of the rest; adding it would double every figure
            }
            if (!CroesusProfitLog.ALL.equals(floorFilter) && !floorFilter.equals(e.getKey())) {
                continue;
            }
            CroesusProfitLog.Totals t = e.getValue();
            out.chests += t.chests;
            out.cost += t.cost;
            out.value += t.value;
            out.profit += t.profit;
        }
        return out;
    }

    /** Net profit per claim, oldest first, for the graph. */
    private long[] netPerRun() {
        List<CroesusProfitLog.Claim> claims = CroesusProfitLog.claims();
        List<Long> out = new ArrayList<>();
        for (CroesusProfitLog.Claim c : claims) {
            if (CroesusProfitLog.ALL.equals(floorFilter) || floorFilter.equals(c.floor())) {
                out.add(c.profit());
            }
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private List<Row> buildRows() {
        List<Row> out = new ArrayList<>();
        switch (view) {
            case BY_FLOOR -> {
                totalsBlock(out, "Session", CroesusProfitLog.session());
                out.add(new Row("", 0, null, null, 0));
                totalsBlock(out, "All-time", CroesusProfitLog.allTime());
            }
            case LOOT_LOG -> {
                List<CroesusProfitLog.ItemTotals> items = CroesusProfitLog.itemIndex();
                if (items.isEmpty()) {
                    out.add(new Row("Nothing claimed yet.", ProfitPanels.DIM, null, null, 0));
                    break;
                }
                for (CroesusProfitLog.ItemTotals item : items) {
                    out.add(new Row("x" + item.amount() + " " + item.name(), ProfitPanels.TEXT,
                            floorsText(item.byFloor()) + (item.notableRuns().isEmpty() ? ""
                                    : "  runs " + runsText(item.notableRuns())),
                            DungeonChestValuer.formatCoins(item.value()), ProfitPanels.ACCENT));
                }
            }
            default -> { }
        }
        return out;
    }

    private static void totalsBlock(List<Row> out, String heading, Map<String, CroesusProfitLog.Totals> totals) {
        out.add(new Row(heading + (totals.isEmpty() ? "  (nothing claimed yet)" : ""), ProfitPanels.ACCENT,
                null, null, 0));
        for (Map.Entry<String, CroesusProfitLog.Totals> e : totals.entrySet()) {
            CroesusProfitLog.Totals t = e.getValue();
            out.add(new Row("  " + e.getKey(), ProfitPanels.TEXT,
                    t.chests + " chests  cost " + DungeonChestValuer.formatCoins(t.cost)
                            + "  value " + DungeonChestValuer.formatCoins(t.value),
                    (t.profit >= 0 ? "+" : "") + DungeonChestValuer.formatCoins(t.profit),
                    t.profit >= 0 ? ProfitPanels.GOOD : ProfitPanels.BAD));
        }
    }

    private static String floorsText(Map<String, Integer> byFloor) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : byFloor.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append(" x").append(e.getValue());
        }
        return sb.toString();
    }

    private static String runsText(List<Integer> runs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.size() && i < 6; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('#').append(runs.get(i));
        }
        return sb.toString();
    }

    private int maxScroll() {
        return Math.max(0, rows.size() * ROW_H - listH);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (view != View.OVERVIEW && mouseX >= listX && mouseX <= listX + listW
                && mouseY >= listY && mouseY <= listY + listH) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(scrollY * ROW_H * 2)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, ProfitPanels.PANEL_BG);
        graphics.outline(panelX, panelY, panelW, panelH, ProfitPanels.BORDER);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 30, 0xFF000000);
        graphics.outline(panelX, panelY, panelW, 30, ProfitPanels.BORDER);
        graphics.fill(panelX, panelY + 29, panelX + panelW, panelY + 30, ProfitPanels.ACCENT);
        graphics.text(this.font, "DUNGEON PROFIT", panelX + 10, panelY + 11, ProfitPanels.ACCENT, false);
        String claims = CroesusProfitLog.entryCount() + " claims logged";
        graphics.text(this.font, claims, panelX + panelW - 10 - this.font.width(claims), panelY + 11,
                ProfitPanels.DIM, false);

        if (view == View.OVERVIEW) {
            renderOverview(graphics);
        } else {
            renderList(graphics);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderOverview(GuiGraphicsExtractor g) {
        CroesusProfitLog.Totals t = selectedTotals();
        int cardsY = panelY + 58;
        int cardH = 46;
        int gap = 6;
        int cardW = (panelW - 12 - gap * 2) / 3;
        int cx = panelX + 6;

        ProfitPanels.card(g, this.font, cx, cardsY, cardW, cardH, "Net Profit",
                ProfitPanels.signedCoins(t.profit), t.profit >= 0 ? ProfitPanels.GOOD : ProfitPanels.BAD,
                t.chests + " chests", t.profit >= 0 ? ProfitPanels.GOOD : ProfitPanels.BAD);
        cx += cardW + gap;
        ProfitPanels.card(g, this.font, cx, cardsY, cardW, cardH, "Gross Value",
                ProfitPanels.coins(t.value), ProfitPanels.TEXT, "Before chest costs", ProfitPanels.ACCENT);
        cx += cardW + gap;
        ProfitPanels.card(g, this.font, cx, cardsY, cardW, cardH, "Chest Cost",
                ProfitPanels.coins(t.cost), ProfitPanels.BAD, "Coins spent opening", ProfitPanels.BAD);

        int bodyY = cardsY + cardH + gap;
        int bodyH = panelY + panelH - 8 - bodyY;
        int leftW = (panelW - 12 - gap) * 2 / 5;
        int rightW = panelW - 12 - gap - leftW;

        renderRareDrops(g, panelX + 6, bodyY, leftW, bodyH);
        renderPerformance(g, panelX + 6 + leftW + gap, bodyY, rightW, bodyH, t);
    }

    private void renderRareDrops(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, ProfitPanels.INNER_BG);
        g.outline(x, y, w, h, ProfitPanels.BORDER);
        g.text(this.font, "RARE DROPS", x + 8, y + 8, ProfitPanels.DIM, false);
        List<CroesusProfitLog.ItemTotals> items = CroesusProfitLog.itemIndex();
        int rowY = y + 22;
        int shown = 0;
        for (CroesusProfitLog.ItemTotals item : items) {
            if (rowY > y + h - 14) {
                break;
            }
            if (!CroesusProfitLog.isNotable(item.id(), item.value())) {
                continue;
            }
            String name = this.font.plainSubstrByWidth(item.name(), w - 70);
            g.text(this.font, name, x + 8, rowY, ProfitPanels.TEXT, false);
            String count = "x" + item.amount();
            g.text(this.font, count, x + 8, rowY + 10, ProfitPanels.DIM, false);
            String val = ProfitPanels.coins(item.value());
            g.text(this.font, val, x + w - 8 - this.font.width(val), rowY + 5, ProfitPanels.ACCENT, false);
            rowY += 22;
            shown++;
        }
        if (shown == 0) {
            g.text(this.font, "No notable drops yet", x + 8, y + 24, ProfitPanels.DIM, false);
        }
    }

    private void renderPerformance(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                   CroesusProfitLog.Totals t) {
        g.fill(x, y, x + w, y + h, ProfitPanels.INNER_BG);
        g.outline(x, y, w, h, ProfitPanels.BORDER);
        g.text(this.font, (sessionOnly ? "SESSION" : "OVERALL") + " PERFORMANCE", x + 8, y + 8,
                ProfitPanels.DIM, false);
        g.text(this.font, ProfitPanels.signedCoins(t.profit), x + 8, y + 20,
                t.profit >= 0 ? ProfitPanels.GOOD : ProfitPanels.BAD, false);
        String per = t.chests > 0 ? ProfitPanels.coins(t.profit / t.chests) + " avg" : "no chests yet";
        g.text(this.font, t.chests + " chests  •  " + per, x + 8, y + 32, ProfitPanels.DIM, false);

        int graphY = y + 46;
        int graphH = h - 46 - 18;
        ProfitPanels.barGraph(g, this.font, x + 6, graphY, w - 12, graphH, netPerRun());

        List<CroesusProfitLog.Claim> claims = CroesusProfitLog.claims();
        if (!claims.isEmpty()) {
            CroesusProfitLog.Claim last = claims.get(claims.size() - 1);
            String recent = "RECENT  " + last.floor() + " " + last.chest();
            g.text(this.font, this.font.plainSubstrByWidth(recent, w - 80), x + 8, y + h - 12,
                    ProfitPanels.DIM, false);
            String v = ProfitPanels.signedCoins(last.profit());
            g.text(this.font, v, x + w - 8 - this.font.width(v), y + h - 12,
                    last.profit() >= 0 ? ProfitPanels.GOOD : ProfitPanels.BAD, false);
        }
    }

    private void renderList(GuiGraphicsExtractor graphics) {
        graphics.fill(listX, listY, listX + listW, listY + listH, ProfitPanels.INNER_BG);
        graphics.outline(listX - 1, listY - 1, listW + 2, listH + 2, ProfitPanels.BORDER);
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        try {
            int first = Math.max(0, scroll / ROW_H);
            for (int i = first; i < rows.size(); i++) {
                int rowY = listY + i * ROW_H - scroll + 2;
                if (rowY > listY + listH) {
                    break;
                }
                Row row = rows.get(i);
                if (row.left().isEmpty() && row.middle() == null) {
                    continue;
                }
                int rightW = 0;
                if (row.right() != null) {
                    rightW = this.font.width(row.right());
                    graphics.text(this.font, row.right(), listX + listW - 6 - rightW, rowY, row.rightColor(), false);
                }
                int leftW = this.font.width(row.left());
                graphics.text(this.font, this.font.plainSubstrByWidth(row.left(), listW - 12 - rightW),
                        listX + 4, rowY, row.leftColor(), false);
                if (row.middle() != null && !row.middle().isEmpty()) {
                    int space = listW - 16 - rightW - leftW;
                    if (space > 20) {
                        graphics.text(this.font, this.font.plainSubstrByWidth(row.middle(), space),
                                listX + 8 + leftW, rowY, ProfitPanels.DIM, false);
                    }
                }
            }
        } finally {
            graphics.disableScissor();
        }
        if (maxScroll() > 0) {
            int trackX = listX + listW - 3;
            graphics.fill(trackX, listY, trackX + 3, listY + listH, 0xFF1A1A1A);
            int contentH = rows.size() * ROW_H;
            int thumbH = Math.max(10, listH * listH / contentH);
            int thumbY = listY + (listH - thumbH) * scroll / maxScroll();
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, ProfitPanels.ACCENT);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
