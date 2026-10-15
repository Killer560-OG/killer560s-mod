package com.killer560.hub.croesus;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.profit.ProfitPanels;
import com.killer560.hub.gui.profit.ProfitScopeBar;
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
import com.killer560.hub.compat.McCompat;

/**
 * The dungeon profit tracker, opened with {@code /croesus}.
 *
 * <p>Laid out from the concept killer560 sent on 2026-09-28 - an Overview with three headline figures, a rare
 * drops list and a net-per-run graph, then By Floor and Loot Log - in this mod's amber rather than the blue and
 * purple of the picture. He also said the same shape is meant to serve every skill later, so the cards and the
 * graph live in {@link ProfitPanels} and know nothing about dungeons; only this screen does.
 *
 * <p>Everything is still read out of the existing {@code config/killer560/dungeons/runs/killer560smod-croesus-log.json}. The concept
 * needed no new tracking at all: {@link CroesusProfitLog} already kept per-floor totals, a per-item index and
 * every claim with its cost, value and profit, which is exactly what the three tabs want. There is no second
 * store and no new file.
 */
public class CroesusTrackerScreen extends Screen implements MenuThemedScreen {

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
    /** When the header's Reset Totals was first clicked (0 = not armed). */
    private final long[] resetArmedAt = {0L};
    /** Top of the cards / list, below the tab row and the account / profile switches. */
    private static final int BODY_TOP = 58 + ProfitScopeBar.HEIGHT + 4;

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

        // Opened from the /profit hub: a Back button in the header returns to it (Escape does too).
        if (parent != null) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("< Back"), btn -> onClose())
                    .bounds(panelX + panelW - 62, panelY + 6, 56, 18).build());
        }
        // killer560, 2026-10-11: the reset turns red while it asks, and resets only what the switches select.
        addRenderableWidget(ProfitScopeBar.resetButton(resetX(), panelY + 6, RESET_W, 18, resetArmedAt,
                CroesusProfitLog.view(), CroesusProfitLog::resetView, () -> {
                    if (!floorsSeen().contains(floorFilter)) {
                        floorFilter = CroesusProfitLog.ALL;
                    }
                    rebuildWidgets();
                }));
        ProfitScopeBar.add(this::addRenderableWidget, panelX + 6, panelY + 56, panelW - 12, CroesusProfitLog.view(),
                CroesusProfitLog::keys, () -> {
                    CroesusProfitLog.saveView();
                    resetArmedAt[0] = 0L;
                    if (!floorsSeen().contains(floorFilter)) {
                        floorFilter = CroesusProfitLog.ALL;
                    }
                    scroll = 0;
                    rebuildWidgets();
                });

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
            addRenderableWidget(SettingsButtonWidget.cycling(
                    Component.literal("§f" + floorFilter), (btn, dir) -> {
                        floorFilter = nextFloor(dir);
                        rebuildWidgets();
                    }).bounds(panelX + panelW - bw - 6, panelY + 34, bw, 18).build());
        }

        // The Overview draws its own panels; the other two are plain scrolling lists.
        listX = panelX + 6;
        listY = panelY + BODY_TOP;
        listW = panelW - 12;
        listH = panelH - BODY_TOP - 8;
        rows = view == View.OVERVIEW ? List.of() : buildRows();
    }

    /** Floors seen in the log, so the filter only ever offers something there is data for. */
    private String nextFloor(int dir) {
        List<String> floors = new ArrayList<>(floorsSeen());
        int i = floors.indexOf(floorFilter);
        return floors.get(i < 0 ? 0 : SettingsButtonWidget.step(i, dir, floors.size()));
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
        // In the menu's theme (sweep-b, 2026-10-13); every widget keeps its place.
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.header(graphics, panelX, panelY, panelW, "Dungeon Profit");
        String claims = CroesusProfitLog.entryCount() + " claims";
        MenuStyle.text(graphics, claims, resetX() - 8 - this.font.width(claims), panelY + 12, MenuStyle.DIM);

        if (view == View.OVERVIEW) {
            renderOverview(graphics);
        } else {
            renderList(graphics);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderOverview(GuiGraphicsExtractor g) {
        CroesusProfitLog.Totals t = selectedTotals();
        int cardsY = panelY + BODY_TOP;
        int cardH = 46;
        int gap = 6;
        int cardW = (panelW - 12 - gap * 2) / 3;
        int cx = panelX + 6;

        ProfitPanels.themedCard(g, this.font, cx, cardsY, cardW, cardH, "Net Profit",
                ProfitPanels.signedCoins(t.profit), t.profit >= 0 ? ProfitPanels.GOOD : ProfitPanels.BAD,
                t.chests + " chests", t.profit >= 0 ? ProfitPanels.GOOD : ProfitPanels.BAD);
        cx += cardW + gap;
        ProfitPanels.themedCard(g, this.font, cx, cardsY, cardW, cardH, "Gross Value",
                ProfitPanels.coins(t.value), ProfitPanels.TEXT, "Before chest costs", ProfitPanels.ACCENT);
        cx += cardW + gap;
        ProfitPanels.themedCard(g, this.font, cx, cardsY, cardW, cardH, "Chest Cost",
                ProfitPanels.coins(t.cost), ProfitPanels.BAD, "Coins spent opening", ProfitPanels.BAD);

        int bodyY = cardsY + cardH + gap;
        int bodyH = panelY + panelH - 8 - bodyY;
        int leftW = (panelW - 12 - gap) * 2 / 5;
        int rightW = panelW - 12 - gap - leftW;

        renderRareDrops(g, panelX + 6, bodyY, leftW, bodyH);
        renderPerformance(g, panelX + 6 + leftW + gap, bodyY, rightW, bodyH, t);
    }

    private void renderRareDrops(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        ScreenChrome.well(g, x, y, w, h);
        MenuStyle.text(g, "RARE DROPS", x + 8, y + 8, MenuStyle.DIM);
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
            MenuStyle.text(g, ScreenChrome.readable(name), x + 8, rowY, MenuStyle.TXT);
            String count = "x" + item.amount();
            MenuStyle.text(g, count, x + 8, rowY + 10, MenuStyle.DIM);
            String val = ProfitPanels.coins(item.value());
            MenuStyle.text(g, val, x + w - 8 - this.font.width(val), rowY + 5, MenuStyle.OR);
            rowY += 22;
            shown++;
        }
        if (shown == 0) {
            MenuStyle.text(g, "No notable drops yet", x + 8, y + 24, MenuStyle.DIM2);
        }
    }

    private void renderPerformance(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                   CroesusProfitLog.Totals t) {
        ScreenChrome.well(g, x, y, w, h);
        MenuStyle.text(g, (sessionOnly ? "SESSION" : "OVERALL") + " PERFORMANCE", x + 8, y + 8, MenuStyle.DIM);
        MenuStyle.text(g, ProfitPanels.signedCoins(t.profit), x + 8, y + 20,
                t.profit >= 0 ? ScreenChrome.good() : ScreenChrome.bad());
        String per = t.chests > 0 ? ProfitPanels.coins(t.profit / t.chests) + " avg" : "no chests yet";
        MenuStyle.text(g, t.chests + " chests  •  " + per, x + 8, y + 32, MenuStyle.DIM);

        int graphY = y + 46;
        int graphH = h - 46 - 18;
        ProfitPanels.themedBarGraph(g, this.font, x + 6, graphY, w - 12, graphH, netPerRun());

        List<CroesusProfitLog.Claim> claims = CroesusProfitLog.claims();
        if (!claims.isEmpty()) {
            CroesusProfitLog.Claim last = claims.get(claims.size() - 1);
            String recent = "RECENT  " + last.floor() + " " + last.chest();
            MenuStyle.text(g, ScreenChrome.readable(this.font.plainSubstrByWidth(recent, w - 80)), x + 8, y + h - 12,
                    MenuStyle.DIM);
            String v = ProfitPanels.signedCoins(last.profit());
            MenuStyle.text(g, v, x + w - 8 - this.font.width(v), y + h - 12,
                    last.profit() >= 0 ? ScreenChrome.good() : ScreenChrome.bad());
        }
    }

    private void renderList(GuiGraphicsExtractor graphics) {
        ScreenChrome.well(graphics, listX - 1, listY - 1, listW + 2, listH + 2);
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
                if (row.right() != null) {
                    ScreenChrome.row(graphics, listX + 1, rowY - 2, listW - 7, ROW_H, false, false, (i) % 2 == 1);
                }
                int rightW = 0;
                if (row.right() != null) {
                    rightW = this.font.width(row.right());
                    MenuStyle.text(graphics, row.right(), listX + listW - 10 - rightW, rowY,
                            ProfitPanels.themed(row.rightColor()));
                }
                int leftW = this.font.width(row.left());
                MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(row.left(), listW - 16 - rightW)),
                        listX + 4, rowY, ProfitPanels.themed(row.leftColor()));
                if (row.middle() != null && !row.middle().isEmpty()) {
                    int space = listW - 20 - rightW - leftW;
                    if (space > 20) {
                        MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(row.middle(), space)),
                                listX + 8 + leftW, rowY, MenuStyle.DIM);
                    }
                }
            }
        } finally {
            graphics.disableScissor();
        }
        if (maxScroll() > 0) {
            ScreenChrome.scrollbar(graphics, listX + listW - 5, listY + 1, listH - 2, scroll, maxScroll(), listH);
        }
    }

    private static final int RESET_W = 110;

    /** The header's reset sits left of Back (or at the right edge without one). */
    private int resetX() {
        return panelX + panelW - (parent != null ? 66 : 6) - RESET_W;
    }

    @Override
    public void tick() {
        super.tick();
        if (ProfitScopeBar.expired(resetArmedAt)) {
            rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }

    @Override
    public String themeId() {
        return "croesus";
    }
}
