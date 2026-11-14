package com.killer560.hub.whatsnew;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What's New (killer560, 2026-11-11; restyled 2026-11-13: "a more distinct box ... more our style"): the mod menu's frame,
 * a row of date chips across the top (newest first, the open one lit like a selected menu card), and under it one
 * accent-ringed box holding the open update: its summary in a header strip, then every section with the mod menu's
 * section label, its blurb and its lines, scrolled with the wheel. Newer / Back / Older along the bottom. Reached from the
 * Themed Main Menu's What's New button and Home's What's New row. Data: {@link WhatsNewData}.
 */
public final class WhatsNewScreen extends Screen implements MenuThemedScreen {

    private static final int PAD = 8;
    private static final int CHIP_H = 18;
    private static final int CHIP_GAP = 4;
    private static final int STRIP_H = 20;
    private static final int LINE_H = 10;
    private static final DateTimeFormatter CHIP = DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    /** One drawn line of the open update: a section label, a blurb, a bullet line (first or continued) or a gap. */
    record Line(int kind, String text) {
        static final int SECTION = 0, BLURB = 1, ITEM = 2, MORE = 3, GAP = 4;
    }

    private final Screen parent;
    private List<WhatsNewData.Update> updates;
    private List<String> chipLabels = List.of();
    private int selected;
    /** The open update's lines, built when it is picked or the window resizes. */
    private final List<Line> lines = new ArrayList<>();
    private String longDate = "";
    private int scroll;
    private int chipFirst;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int chipX;
    private int chipY;
    private int chipW;
    private int boxX;
    private int boxY;
    private int boxW;
    private int boxH;
    private SettingsButtonWidget newer;
    private SettingsButtonWidget older;

    public WhatsNewScreen(Screen parent) {
        super(Component.literal("What's New"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(540, this.width - 8);
        panelH = Math.min(320, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(2, (this.height - panelH) / 2);
        chipX = panelX + PAD;
        chipY = panelY + ScreenChrome.HEADER_H + 10;
        chipW = MenuStyle.font().width("Sep 30") + 14;
        boxX = panelX + PAD + 4;
        boxY = chipY + CHIP_H + 10;
        boxW = panelW - 2 * PAD - 8;
        int buttonY = panelY + panelH - PAD - 20;
        boxH = buttonY - 10 - boxY;
        int bw = Math.min(110, (boxW - 2 * PAD) / 3);
        int cx = panelX + panelW / 2;
        newer = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("< Newer"), b -> select(selected - 1))
                .bounds(cx - bw / 2 - PAD - bw, buttonY, bw, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose()).primary()
                .bounds(cx - bw / 2, buttonY, bw, 20).build());
        older = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Older >"), b -> select(selected + 1))
                .bounds(cx + bw / 2 + PAD, buttonY, bw, 20).build());
        updates = WhatsNewData.get();
        loaded();
    }

    private int chipsFit() {
        return Math.max(1, (panelW - 2 * PAD + CHIP_GAP) / (chipW + CHIP_GAP));
    }

    /** Chip labels once the data is in, then the open update (kept across a resize). */
    private void loaded() {
        if (updates == null) {
            newer.active = older.active = false;
            return;
        }
        List<String> labels = new ArrayList<>();
        for (WhatsNewData.Update u : updates) {
            labels.add(format(u.date(), CHIP));
        }
        chipLabels = labels;
        select(selected);
    }

    private static String format(String isoDate, DateTimeFormatter f) {
        try {
            return LocalDate.parse(isoDate).format(f);
        } catch (Exception e) {
            return isoDate;
        }
    }

    /** Opens update {@code i} (clamped) at the top of the box, and keeps its chip in view. */
    void select(int i) {
        lines.clear();
        scroll = 0;
        if (updates == null || updates.isEmpty()) {
            newer.active = older.active = false;
            return;
        }
        selected = Math.max(0, Math.min(i, updates.size() - 1));
        newer.active = selected > 0;
        older.active = selected < updates.size() - 1;
        chipFirst = Math.max(Math.min(chipFirst, selected), selected - chipsFit() + 1);
        WhatsNewData.Update u = updates.get(selected);
        longDate = format(u.date(), LONG);
        int textW = boxW - 34;
        for (WhatsNewData.Section s : u.sections()) {
            lines.add(new Line(Line.SECTION, s.title()));
            for (String b : MenuStyle.wrap(s.blurb(), textW, 2)) {
                lines.add(new Line(Line.BLURB, b));
            }
            for (String item : s.items()) {
                List<String> parts = MenuStyle.wrap(item, textW - 8, 4);
                for (int k = 0; k < parts.size(); k++) {
                    lines.add(new Line(k == 0 ? Line.ITEM : Line.MORE, parts.get(k)));
                }
            }
            lines.add(new Line(Line.GAP, ""));
        }
    }

    private int bodyY() {
        return boxY + STRIP_H + 6;
    }

    private int bodyH() {
        return boxY + boxH - 4 - bodyY();
    }

    private int maxScroll() {
        return Math.max(0, lines.size() * LINE_H - bodyH());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (updates == null && (updates = WhatsNewData.get()) != null) {
            loaded();
        }
        ScreenChrome.backdrop(g, this.width, this.height);
        ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        ScreenChrome.header(g, panelX, panelY, panelW, "What's New");
        drawBox(g);
        if (updates == null || updates.isEmpty()) {
            ScreenChrome.text(g, updates == null ? "Loading..." : "No updates listed.", boxX + 10, bodyY(), MenuStyle.DIM);
        } else {
            ScreenChrome.headerRight(g, panelX, panelY, panelW, updates.size() + " updates", MenuStyle.DIM);
            drawChips(g, mouseX, mouseY);
            drawBody(g);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** The content box: an accent glow and ring around a recessed well, with a header strip along its top. */
    private void drawBox(GuiGraphicsExtractor g) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.glow(r, boxX - 1, boxY - 1, boxW + 2, boxH + 2, MenuStyle.OR, 0x30);
        r.outline(boxX - 1, boxY - 1, boxW + 2, boxH + 2, MenuStyle.OR);
        MenuStyle.well(r, boxX, boxY, boxW, boxH, MenuStyle.WELL);
        MenuStyle.bevel(r, boxX + 1, boxY + 1, boxW - 2, STRIP_H, MenuStyle.HEADER_TOP, MenuStyle.HEADER_BOTTOM);
        r.fill(boxX + 1, boxY + 1 + STRIP_H, boxX + boxW - 1, boxY + 2 + STRIP_H, MenuStyle.OR);
        r.submit();
        if (updates != null && !updates.isEmpty()) {
            int dw = MenuStyle.font().width(longDate);
            String sum = MenuStyle.ellipsize(updates.get(selected).summary(), boxW - dw - 28);
            ScreenChrome.title(g, sum, boxX + 8, boxY + 7, false);
            ScreenChrome.text(g, longDate, boxX + boxW - 8 - dw, boxY + 7, MenuStyle.DIM);
        }
    }

    private void drawChips(GuiGraphicsExtractor g, int mx, int my) {
        GuiRects r = GuiRects.begin(g);
        int n = Math.min(chipsFit(), updates.size() - chipFirst);
        for (int k = 0; k < n; k++) {
            int x = chipX + k * (chipW + CHIP_GAP);
            boolean hover = mx >= x && mx < x + chipW && my >= chipY && my < chipY + CHIP_H;
            ScreenChrome.card(r, x, chipY, chipW, CHIP_H, hover, chipFirst + k == selected);
        }
        r.submit();
        for (int k = 0; k < n; k++) {
            int i = chipFirst + k;
            ScreenChrome.centered(g, chipLabels.get(i), chipX + k * (chipW + CHIP_GAP) + chipW / 2, chipY + 5,
                    i == selected ? MenuStyle.OR_HOVER : MenuStyle.TXT_SOFT);
        }
    }

    private void drawBody(GuiGraphicsExtractor g) {
        scroll = Math.min(scroll, maxScroll());
        int top = bodyY();
        int x = boxX + 10;
        g.enableScissor(boxX + 1, top - 2, boxX + boxW - 1, top + bodyH());
        int first = scroll / LINE_H;
        for (int i = first; i < lines.size(); i++) {
            int y = top + i * LINE_H - scroll;
            if (y > top + bodyH()) {
                break;
            }
            Line l = lines.get(i);
            switch (l.kind()) {
                case Line.SECTION -> ScreenChrome.section(g, l.text(), x, y, boxX + boxW - 14, false);
                case Line.BLURB -> ScreenChrome.text(g, l.text(), x, y, MenuStyle.DIM);
                case Line.ITEM -> {
                    g.fill(x + 2, y + 3, x + 5, y + 6, MenuStyle.OR);
                    ScreenChrome.text(g, l.text(), x + 10, y, MenuStyle.TXT);
                }
                case Line.MORE -> ScreenChrome.text(g, l.text(), x + 10, y, MenuStyle.TXT);
                default -> {
                }
            }
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            ScreenChrome.scrollbar(g, boxX + boxW - 7, top, bodyH(), scroll, maxScroll(), bodyH());
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        if (updates != null && !updates.isEmpty() && y >= chipY && y < chipY + CHIP_H && x >= chipX) {
            int k = (int) ((x - chipX) / (chipW + CHIP_GAP));
            if (k < chipsFit() && x - chipX - k * (chipW + CHIP_GAP) < chipW && chipFirst + k < updates.size()) {
                select(chipFirst + k);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) Math.signum(scrollY);
        if (mouseY < boxY) {
            select(selected - step);
        } else {
            scroll = Math.max(0, Math.min(scroll - step * 14, maxScroll()));
        }
        return true;
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return parent != null && parent.isPauseScreen();
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }
}
