package com.killer560.hub.gui.profit;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * A standalone profit screen for a tracker that used to live only in a settings tab (Experimentation Table,
 * and the shelved Mining Profit / Nucleus Runs): headline cards, a ranked list on the left, and on the right either a graph or a
 * few detail lines. Everything is drawn with {@link ProfitPanels}, the same pieces the Croesus screen uses; a
 * subclass only supplies the numbers.
 *
 * <p>Back (top right) and Escape both return to {@code parent} - the {@code /profit} hub when it was opened from
 * there, or the game when it was opened directly.
 */
public abstract class ProfitTrackerScreen extends Screen {

    /** One headline card. */
    protected record Card(String label, String value, int valueColor, String sub, int stripe) {
    }

    private final Screen parent;
    private final String heading;
    /** When Reset Totals was first clicked (0 = not armed); an array so {@link ProfitScopeBar} can hold it. */
    private final long[] resetArmedAt = {0L};

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    protected ProfitTrackerScreen(Screen parent, String heading) {
        super(Component.literal(heading));
        this.parent = parent;
        this.heading = heading;
    }

    protected abstract List<Card> cards();

    protected abstract String listTitle();

    /** Left list, one entry per line, best first; empty draws {@link #emptyText()}. */
    protected abstract List<String> listLines();

    protected abstract String emptyText();

    protected abstract String detailTitle();

    /** Right panel graph values (oldest first), or null to draw {@link #detailLines()} instead. */
    protected long[] graph() {
        return null;
    }

    protected List<String> detailLines() {
        return List.of();
    }

    /** A warning under the header (tracker switched off), or null. */
    protected abstract String note();

    /** Clears what the filter selects (an all-null filter is everything). */
    protected abstract void reset(ProfitScope.Filter filter);

    /** What the account / profile / mod profile switches currently select (the tracker keeps and saves it). */
    protected abstract ProfitScope.Filter filter();

    /** Persists {@link #filter()} after a switch changed it. */
    protected abstract void saveFilter();

    /** Every account / profile / mod profile this tracker has data for. */
    protected abstract java.util.Collection<ProfitScope.Key> keys();

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, Math.max(360, Math.min((int) (this.width * 0.8), 620)));
        panelH = Math.min(this.height - 20, Math.max(220, Math.min((int) (this.height * 0.85), 330)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        if (parent != null) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("< Back"), btn -> onClose())
                    .bounds(panelX + panelW - 62, panelY + 6, 56, 18).build());
        }
        ProfitScopeBar.add(this::addRenderableWidget, panelX + 6, panelY + 34, panelW - 12, filter(), this::keys,
                () -> {
                    saveFilter();
                    resetArmedAt[0] = 0L;
                    rebuildWidgets();
                });
        addRenderableWidget(ProfitScopeBar.resetButton(panelX + 6, panelY + panelH - 26, 150, 18, resetArmedAt,
                filter(), () -> reset(filter()), this::rebuildWidgets));
    }

    @Override
    public void tick() {
        super.tick();
        if (ProfitScopeBar.expired(resetArmedAt)) {
            rebuildWidgets(); // the red "Reset ...?" times out back to Reset Totals
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xCC000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, ProfitPanels.PANEL_BG);
        g.outline(panelX, panelY, panelW, panelH, ProfitPanels.BORDER);
        g.fill(panelX, panelY, panelX + panelW, panelY + 30, 0xFF000000);
        g.outline(panelX, panelY, panelW, 30, ProfitPanels.BORDER);
        g.fill(panelX, panelY + 29, panelX + panelW, panelY + 30, ProfitPanels.ACCENT);
        g.text(this.font, heading.toUpperCase(Locale.ROOT), panelX + 10, panelY + 11, ProfitPanels.ACCENT, false);

        int y = panelY + 36 + ProfitScopeBar.HEIGHT + 4;
        String note = note();
        if (note != null) {
            g.text(this.font, note, panelX + 8, y, ProfitPanels.BAD, false);
            y += 12;
        }

        int gap = 6;
        int cardH = 46;
        List<Card> cards = cards();
        int cardW = (panelW - 12 - gap * (cards.size() - 1)) / cards.size();
        int cx = panelX + 6;
        for (Card c : cards) {
            ProfitPanels.card(g, this.font, cx, y, cardW, cardH, c.label(), c.value(), c.valueColor(), c.sub(),
                    c.stripe());
            cx += cardW + gap;
        }

        int bodyY = y + cardH + gap;
        int bodyH = panelY + panelH - 32 - bodyY;
        int leftW = (panelW - 12 - gap) * 2 / 5;
        int rightW = panelW - 12 - gap - leftW;
        renderList(g, panelX + 6, bodyY, leftW, bodyH);
        renderDetail(g, panelX + 6 + leftW + gap, bodyY, rightW, bodyH);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void renderList(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, ProfitPanels.INNER_BG);
        g.outline(x, y, w, h, ProfitPanels.BORDER);
        g.text(this.font, listTitle(), x + 8, y + 8, ProfitPanels.DIM, false);
        List<String> lines = listLines();
        if (lines.isEmpty()) {
            g.text(this.font, emptyText(), x + 8, y + 24, ProfitPanels.DIM, false);
            return;
        }
        int rowY = y + 22;
        for (String line : lines) {
            if (rowY > y + h - 12) {
                break;
            }
            g.text(this.font, this.font.plainSubstrByWidth(line, w - 16), x + 8, rowY, ProfitPanels.TEXT, false);
            rowY += 12;
        }
    }

    private void renderDetail(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, ProfitPanels.INNER_BG);
        g.outline(x, y, w, h, ProfitPanels.BORDER);
        g.text(this.font, detailTitle(), x + 8, y + 8, ProfitPanels.DIM, false);
        long[] values = graph();
        if (values != null) {
            ProfitPanels.barGraph(g, this.font, x + 6, y + 22, w - 12, h - 28, values);
            return;
        }
        int rowY = y + 22;
        for (String line : detailLines()) {
            if (rowY > y + h - 12) {
                break;
            }
            g.text(this.font, this.font.plainSubstrByWidth(line, w - 16), x + 8, rowY, ProfitPanels.TEXT, false);
            rowY += 12;
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
}
