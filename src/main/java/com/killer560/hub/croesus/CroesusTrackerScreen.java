package com.killer560.hub.croesus;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Croesus profit tracker, opened with {@code /croesus} (killer560, 2026-09-20: "Remove the session and
 * all time text inside the mod menu, instead i should type /croesus profit session or all to see it", plus
 * "I should be able to open a menu that shows how many of each item from how many floors and you can log
 * things like the run number as well for bigger drops").
 * <p>
 * Everything shown here is read out of the existing {@code config/killer560smod-croesus-log.json} - the
 * totals it already kept, and the per-item / big-drop views {@link CroesusProfitLog} now derives from the
 * claim entries that file has been storing all along. There is no second store and no new file.
 * <p>
 * Same black + amber chrome as {@code StorageSearchScreen} / {@code ModScreen}.
 */
public class CroesusTrackerScreen extends Screen {

    private static final int ACCENT = 0xFFCC6600;
    private static final int BORDER = 0xFF553311;
    private static final int PANEL_BG = 0xFF0D0D0D;
    private static final int ROW_H = 12;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withLocale(Locale.US).withZone(ZoneId.systemDefault());

    /** Which list the body is showing. */
    public enum View {
        TOTALS("Totals"), ITEMS("Items"), DROPS("Big Drops");

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
        super(Component.literal("Croesus Profit"));
        this.parent = parent;
        this.view = view == null ? View.TOTALS : view;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, Math.max(340, Math.min((int) (this.width * 0.75), 560)));
        panelH = Math.min(this.height - 20, Math.max(200, Math.min((int) (this.height * 0.82), 420)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        listX = panelX + 6;
        listY = panelY + 58;
        listW = panelW - 12;
        listH = panelH - 58 - 8;

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
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset totals"), btn -> {
            CroesusProfitLog.resetTotals();
            rebuildWidgets();
        }).bounds(panelX + panelW - bw - 6, panelY + 34, bw, 18).build());

        rows = buildRows();
    }

    private List<Row> buildRows() {
        List<Row> out = new ArrayList<>();
        switch (view) {
            case TOTALS -> {
                totalsBlock(out, "Session", CroesusProfitLog.session());
                out.add(new Row("", 0, null, null, 0));
                totalsBlock(out, "All-time", CroesusProfitLog.allTime());
            }
            case ITEMS -> {
                List<CroesusProfitLog.ItemTotals> items = CroesusProfitLog.itemIndex();
                if (items.isEmpty()) {
                    out.add(new Row("Nothing claimed yet.", 0xFF000000 | ModChat.DIM, null, null, 0));
                    break;
                }
                for (CroesusProfitLog.ItemTotals item : items) {
                    out.add(new Row("x" + item.amount() + " " + item.name(), 0xFF000000 | ModChat.TEXT,
                            floorsText(item.byFloor()) + (item.notableRuns().isEmpty() ? ""
                                    : "  runs " + runsText(item.notableRuns())),
                            DungeonChestValuer.formatCoins(item.value()), 0xFF000000 | ModChat.LIGHT_ORANGE));
                }
            }
            case DROPS -> {
                List<Object[]> drops = CroesusProfitLog.notableDrops();
                if (drops.isEmpty()) {
                    out.add(new Row("No big drops logged yet.", 0xFF000000 | ModChat.DIM, null, null, 0));
                    break;
                }
                for (Object[] drop : drops) {
                    long when = (Long) drop[4];
                    out.add(new Row("#" + drop[0] + "  " + drop[2], 0xFF000000 | ModChat.TEXT,
                            drop[1] + (when > 0 ? "  " + DATE.format(Instant.ofEpochMilli(when)) : ""),
                            DungeonChestValuer.formatCoins((Long) drop[3]), 0xFF000000 | ModChat.LIGHT_ORANGE));
                }
            }
        }
        return out;
    }

    private static void totalsBlock(List<Row> out, String heading, Map<String, CroesusProfitLog.Totals> totals) {
        out.add(new Row(heading + (totals.isEmpty() ? "  (nothing claimed yet)" : ""), ACCENT, null, null, 0));
        for (Map.Entry<String, CroesusProfitLog.Totals> e : totals.entrySet()) {
            CroesusProfitLog.Totals t = e.getValue();
            out.add(new Row("  " + e.getKey(), 0xFF000000 | ModChat.TEXT,
                    t.chests + " chests  cost " + DungeonChestValuer.formatCoins(t.cost)
                            + "  value " + DungeonChestValuer.formatCoins(t.value),
                    (t.profit >= 0 ? "+" : "") + DungeonChestValuer.formatCoins(t.profit),
                    0xFF000000 | (t.profit >= 0 ? ModChat.GOOD : ModChat.BAD)));
        }
    }

    /** "F7 x12, M7 x3" - killer560: "how many of each item from how many floors". */
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
        for (int i = 0; i < runs.size() && i < 8; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('#').append(runs.get(i));
        }
        if (runs.size() > 8) {
            sb.append(", +").append(runs.size() - 8);
        }
        return sb.toString();
    }

    private int maxScroll() {
        return Math.max(0, rows.size() * ROW_H - listH);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(scrollY * ROW_H * 2)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        graphics.outline(panelX, panelY, panelW, panelH, BORDER);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 30, 0xFF000000);
        graphics.outline(panelX, panelY, panelW, 30, BORDER);
        graphics.fill(panelX, panelY + 29, panelX + panelW, panelY + 30, ACCENT);
        graphics.text(this.font, "Croesus Profit", panelX + 10, panelY + 11, ACCENT, false);
        String claims = CroesusProfitLog.entryCount() + " claims logged";
        graphics.text(this.font, claims, panelX + panelW - 10 - this.font.width(claims), panelY + 11,
                0xFF000000 | ModChat.DIM, false);

        graphics.fill(listX, listY, listX + listW, listY + listH, 0xFF080808);
        graphics.outline(listX - 1, listY - 1, listW + 2, listH + 2, BORDER);
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
                                listX + 8 + leftW, rowY, 0xFF000000 | ModChat.DIM, false);
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
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, ACCENT);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
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
