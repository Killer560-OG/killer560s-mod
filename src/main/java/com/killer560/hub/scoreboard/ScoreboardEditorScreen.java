package com.killer560.hub.scoreboard;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.scoreboard.CustomScoreboardConfig.Row;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * SkyHanni-style visual editor for the Custom Scoreboard - killer560: "Redesign the custom scoreboard so it
 * shows more of preview style that i drag and drop things where I want them." Before this, Lines/Events/Chunked
 * Stats order was only editable with "▲"/"▼" arrow buttons (a 2026-09-16 tooltip sweep found those arrows are
 * literally labelled with the characters {@link com.killer560.hub.gui.SettingTooltips} has to special-case).
 * <p>
 * Left column: a live preview of the board, built from {@link CustomScoreboardFeature#previewLines()} - real
 * sidebar/tab data when you're on Skyblock/p3sim, SkyHanni-style sample text otherwise (he's usually configuring
 * this from the hub or the main menu) - plus the settings that don't make sense to drag: alignment, background,
 * title, number format. Right column: drag-and-drop reordering for Lines, Events and Chunked Stats, following
 * the exact interaction model {@link com.killer560.hub.hud.HudEditorScreen} already established for the HUD
 * editor (press to pick up, drag to move, release to drop; a plain click with no movement toggles instead).
 * Dragging a row into the "Disabled" panel turns it off; dragging one out of it turns it back on, at the
 * dropped position - so a reorder is always applied on top of whatever he already has, never a reset.
 * <p>
 * All three lists ({@link CustomScoreboardConfig#entries()}, {@link CustomScoreboardConfig#events()},
 * {@link CustomScoreboardConfig#chunkedStats()}) are edited in place and saved through the same
 * {@link CustomScoreboardConfig#save()} this mod already uses, so nothing about the persisted config format
 * changes - his existing setup loads unchanged, and the old arrow-based Lines/Events/Chunked Stats pages in
 * {@link com.killer560.hub.gui.tab.CustomScoreboardTab} still work exactly as before.
 */
public class ScoreboardEditorScreen extends Screen {

    private enum Section { LINES, EVENTS, STATS }

    private static final int ROW_H = 13;
    private static final int ACCENT = 0xFFCC6600;
    private static final int BORDER = 0xFF553311;
    private static final int MARGIN = 10;

    private final Screen parent;
    private CustomScoreboardConfig cfg;
    private Section section = Section.LINES;

    private record VisualRow(int fullIndex, String label, boolean enabled) {
    }

    private final List<VisualRow> visualRows = new ArrayList<>();

    // layout, computed in init()
    private int previewX, previewY, previewW, previewH;
    private int activePanelX, activePanelY, activePanelW, activePanelH;
    private int disabledPanelX, disabledPanelY, disabledPanelW, disabledPanelH;
    private int activeScroll = 0;
    private int disabledScroll = 0;

    private SettingsButtonWidget linesButton;
    private SettingsButtonWidget eventsButton;
    private SettingsButtonWidget statsButton;

    // drag state - mirrors HudEditorScreen: mouseClicked only arms a pending row, mouseDragged promotes it to
    // an actual drag, and a mouseReleased with no drag in between is treated as a plain toggle click instead.
    private int draggingFullIndex = -1;
    private boolean dragging = false;
    private String draggingLabel;
    private boolean hoverActive;
    private int hoverSlot;
    private double dragOffsetY;
    private double floatY;

    public ScoreboardEditorScreen(Screen parent) {
        super(Component.literal("Custom Scoreboard Editor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        cfg = CustomScoreboardConfig.getInstance();
        Minecraft client = Minecraft.getInstance();

        int leftW = Math.max(200, Math.min(280, this.width / 3));
        int leftX = MARGIN;
        int rightX = leftX + leftW + 14;
        int rightW = Math.max(240, this.width - rightX - MARGIN);

        previewX = leftX;
        previewY = 34;
        previewH = Math.min(280, Math.max(140, this.height / 2));
        previewW = leftW;

        int cy = previewY + previewH + 10;
        addRenderableWidget(cycleBtn(() -> "Text Align: §6" + cfg.getTextAlignment().label,
                () -> cfg.setTextAlignment(cfg.getTextAlignment().next()), leftX, cy, leftW));
        cy += 22;
        addRenderableWidget(cycleBtn(() -> "Title Align: §6" + cfg.getTitleAlignment().label,
                () -> cfg.setTitleAlignment(cfg.getTitleAlignment().next()), leftX, cy, leftW));
        cy += 22;
        addRenderableWidget(cycleBtn(() -> "Numbers: §6" + cfg.getNumberFormat().label,
                () -> cfg.setNumberFormat(cfg.getNumberFormat().next()), leftX, cy, leftW));
        cy += 22;
        addRenderableWidget(cycleBtn(() -> "Number Style: " + cfg.getNumberDisplayFormat().label,
                () -> cfg.setNumberDisplayFormat(cfg.getNumberDisplayFormat().next()), leftX, cy, leftW));
        cy += 22;
        addRenderableWidget(SettingsButtonWidget.builder(onOff("Background", cfg.isBackgroundEnabled()), btn -> {
                    cfg.setBackgroundEnabled(!cfg.isBackgroundEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Background", cfg.isBackgroundEnabled()));
                }).bounds(leftX, cy, leftW, 18).build());
        cy += 22;
        addRenderableWidget(SettingsButtonWidget.builder(onOff("Custom Title", cfg.isUseCustomTitle()), btn -> {
                    cfg.setUseCustomTitle(!cfg.isUseCustomTitle());
                    cfg.save();
                    this.rebuildWidgets();
                }).bounds(leftX, cy, leftW, 18).build());
        cy += 22;
        if (cfg.isUseCustomTitle()) {
            EditBox title = new EditBox(client.font, leftX, cy, leftW, 18, Component.literal("Title"));
            title.setMaxLength(256);
            title.setValue(cfg.getCustomTitle());
            title.setHint(Component.literal("§8Title"));
            title.setResponder(text -> {
                cfg.setCustomTitle(text);
                cfg.save();
            });
            addRenderableWidget(title);
        }

        int sectionBtnW = (rightW - 16) / 3;
        linesButton = SettingsButtonWidget.builder(sectionLabel(Section.LINES), btn -> switchSection(Section.LINES))
                .bounds(rightX, 30, sectionBtnW, 20).build();
        eventsButton = SettingsButtonWidget.builder(sectionLabel(Section.EVENTS), btn -> switchSection(Section.EVENTS))
                .bounds(rightX + sectionBtnW + 8, 30, sectionBtnW, 20).build();
        statsButton = SettingsButtonWidget.builder(sectionLabel(Section.STATS), btn -> switchSection(Section.STATS))
                .bounds(rightX + 2 * (sectionBtnW + 8), 30, sectionBtnW, 20).build();
        addRenderableWidget(linesButton);
        addRenderableWidget(eventsButton);
        addRenderableWidget(statsButton);

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset Order"), btn -> {
                    switch (section) {
                        case LINES -> cfg.resetEntries();
                        case EVENTS -> cfg.resetEvents();
                        case STATS -> cfg.resetChunkedStats();
                    }
                    cfg.save();
                    activeScroll = 0;
                    disabledScroll = 0;
                    rebuildVisualRows();
                }).bounds(rightX, 54, rightW, 18).build());

        int listsTop = 80;
        int listsBottom = this.height - 34;
        int totalListH = Math.max(90, listsBottom - listsTop - 26);
        activePanelX = rightX;
        activePanelY = listsTop + 12;
        activePanelW = rightW;
        activePanelH = (int) (totalListH * 0.6);
        disabledPanelX = rightX;
        disabledPanelY = activePanelY + activePanelH + 14;
        disabledPanelW = rightW;
        disabledPanelH = Math.max(40, totalListH - activePanelH);

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(this.width / 2 - 50, this.height - 26, 100, 20).build());

        rebuildVisualRows();
    }

    // ---- section / row bookkeeping ----

    private void switchSection(Section s) {
        if (section == s) {
            return;
        }
        section = s;
        activeScroll = 0;
        disabledScroll = 0;
        draggingFullIndex = -1;
        dragging = false;
        rebuildVisualRows();
        linesButton.setMessage(sectionLabel(Section.LINES));
        eventsButton.setMessage(sectionLabel(Section.EVENTS));
        statsButton.setMessage(sectionLabel(Section.STATS));
    }

    private Component sectionLabel(Section s) {
        String name = switch (s) {
            case LINES -> "Lines";
            case EVENTS -> "Events";
            case STATS -> "Stats";
        };
        return Component.literal(s == section ? "§6" + name : name);
    }

    private void rebuildVisualRows() {
        visualRows.clear();
        switch (section) {
            case LINES -> fill(cfg.entries(), e -> e.label);
            case EVENTS -> fill(cfg.events(), e -> e.label);
            case STATS -> fill(cfg.chunkedStats(), s -> s.label);
        }
    }

    private <E extends Enum<E>> void fill(List<Row<E>> rows, Function<E, String> label) {
        for (int i = 0; i < rows.size(); i++) {
            Row<E> row = rows.get(i);
            visualRows.add(new VisualRow(i, label.apply(row.id), row.enabled));
        }
    }

    private List<VisualRow> activeRows() {
        List<VisualRow> out = new ArrayList<>();
        for (VisualRow v : visualRows) {
            if (v.enabled()) {
                out.add(v);
            }
        }
        return out;
    }

    private List<VisualRow> disabledRows() {
        List<VisualRow> out = new ArrayList<>();
        for (VisualRow v : visualRows) {
            if (!v.enabled()) {
                out.add(v);
            }
        }
        return out;
    }

    // ---- drag/drop mutation (generic over ScoreboardEntry/ScoreboardEvent/ChunkedStat) ----

    private void applyDrop() {
        switch (section) {
            case LINES -> applyDropGeneric(cfg.entries(), draggingFullIndex, hoverActive, hoverSlot);
            case EVENTS -> applyDropGeneric(cfg.events(), draggingFullIndex, hoverActive, hoverSlot);
            case STATS -> applyDropGeneric(cfg.chunkedStats(), draggingFullIndex, hoverActive, hoverSlot);
        }
        cfg.save();
        rebuildVisualRows();
    }

    private static <E extends Enum<E>> void applyDropGeneric(List<Row<E>> rows, int fullIndex, boolean newEnabled, int slot) {
        if (fullIndex < 0 || fullIndex >= rows.size()) {
            return;
        }
        Row<E> row = rows.remove(fullIndex);
        row.enabled = newEnabled;
        int insertAt = insertionIndex(rows, newEnabled, slot);
        rows.add(Math.max(0, Math.min(rows.size(), insertAt)), row);
    }

    /** Index in {@code rows} where inserting keeps the row at {@code slot} among rows whose enabled state
     *  already equals {@code wantEnabled} - i.e. drop position within just the Active or just the Disabled
     *  panel, translated back into the single underlying ordered list the config actually stores. */
    private static <E extends Enum<E>> int insertionIndex(List<Row<E>> rows, boolean wantEnabled, int slot) {
        int count = 0;
        int lastMatch = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).enabled == wantEnabled) {
                if (count == slot) {
                    return i;
                }
                count++;
                lastMatch = i;
            }
        }
        return lastMatch + 1;
    }

    private void toggleFullIndex(int fullIndex) {
        switch (section) {
            case LINES -> toggleGeneric(cfg.entries(), fullIndex);
            case EVENTS -> toggleGeneric(cfg.events(), fullIndex);
            case STATS -> toggleGeneric(cfg.chunkedStats(), fullIndex);
        }
        cfg.save();
        rebuildVisualRows();
    }

    private static <E extends Enum<E>> void toggleGeneric(List<Row<E>> rows, int fullIndex) {
        if (fullIndex >= 0 && fullIndex < rows.size()) {
            rows.get(fullIndex).enabled = !rows.get(fullIndex).enabled;
        }
    }

    // ---- mouse ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != 0) {
            return false;
        }
        List<VisualRow> active = activeRows();
        List<VisualRow> disabled = disabledRows();
        VisualRow hit = rowAt(active, activePanelX, activePanelY, activePanelW, activePanelH, activeScroll, event.x(), event.y());
        boolean fromActive = hit != null;
        if (hit == null) {
            hit = rowAt(disabled, disabledPanelX, disabledPanelY, disabledPanelW, disabledPanelH, disabledScroll, event.x(), event.y());
        }
        if (hit == null) {
            return false;
        }
        draggingFullIndex = hit.fullIndex();
        draggingLabel = hit.label();
        hoverActive = fromActive;
        List<VisualRow> containing = fromActive ? active : disabled;
        hoverSlot = containing.indexOf(hit);
        int panelY = fromActive ? activePanelY : disabledPanelY;
        int scrollPx = fromActive ? activeScroll : disabledScroll;
        dragOffsetY = event.y() - (panelY + hoverSlot * ROW_H - scrollPx);
        floatY = event.y() - dragOffsetY;
        dragging = false;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingFullIndex >= 0) {
            dragging = true;
            floatY = event.y() - dragOffsetY;
            updateHover(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    private void updateHover(double mx, double my) {
        boolean inColumn = mx >= activePanelX && mx <= activePanelX + activePanelW;
        if (inColumn) {
            // Whichever panel's vertical band the pointer is nearer to, including the gap between them.
            double boundary = (activePanelY + activePanelH + disabledPanelY) / 2.0;
            hoverActive = my < boundary;
        }
        List<VisualRow> target = hoverActive ? activeRows() : disabledRows();
        target.removeIf(v -> v.fullIndex() == draggingFullIndex);
        int panelY = hoverActive ? activePanelY : disabledPanelY;
        int scrollPx = hoverActive ? activeScroll : disabledScroll;
        int slot = (int) Math.round((floatY - panelY + scrollPx) / (double) ROW_H);
        hoverSlot = Math.max(0, Math.min(target.size(), slot));
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingFullIndex >= 0) {
            if (dragging) {
                applyDrop();
            } else {
                // No movement between click and release - same as clicking the old on/off button directly.
                toggleFullIndex(draggingFullIndex);
            }
            draggingFullIndex = -1;
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (within(activePanelX, activePanelY, activePanelW, activePanelH, mouseX, mouseY)) {
            activeScroll = clampScroll(activeScroll - (int) Math.round(scrollY * ROW_H * 2), activeRows().size(), activePanelH);
            return true;
        }
        if (within(disabledPanelX, disabledPanelY, disabledPanelW, disabledPanelH, mouseX, mouseY)) {
            disabledScroll = clampScroll(disabledScroll - (int) Math.round(scrollY * ROW_H * 2), disabledRows().size(), disabledPanelH);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static boolean within(int x, int y, int w, int h, double mx, double my) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static int clampScroll(int v, int count, int panelH) {
        return Math.max(0, Math.min(Math.max(0, count * ROW_H - panelH), v));
    }

    private VisualRow rowAt(List<VisualRow> rows, int x, int y, int w, int h, int scrollPx, double mx, double my) {
        if (mx < x || mx > x + w || my < y || my > y + h) {
            return null;
        }
        int i = (int) ((my - y + scrollPx) / ROW_H);
        return i >= 0 && i < rows.size() ? rows.get(i) : null;
    }

    // ---- rendering ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xCC000000);
        g.text(this.font, "§eClick a row to toggle it. Drag to reorder, or drag it into/out of Disabled.",
                MARGIN, 10, 0xFFFFFFFF);
        boolean live = CustomScoreboardFeature.isActive();
        g.text(this.font, live ? "§7Preview: live data from your current board."
                : "§7Preview: sample data (not on Skyblock/p3sim right now).", MARGIN, 22, 0xFFFFFFFF);

        drawPreview(g);
        drawSectionPanels(g, mouseX, mouseY);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawPreview(GuiGraphicsExtractor g) {
        g.fill(previewX, previewY, previewX + previewW, previewY + previewH, 0xFF0D0D0D);
        g.outline(previewX, previewY, previewW, previewH, BORDER);
        Font font = this.font;
        List<ScoreboardLine> lines = CustomScoreboardFeature.previewLines();
        int boxW = Math.max(1, CustomScoreboardFeature.boxWidth(font, lines, cfg));
        int boxH = Math.max(1, CustomScoreboardFeature.boxHeight(font, lines, cfg));
        float scale = Math.min(1.5f, Math.min((previewW - 8f) / boxW, (previewH - 8f) / boxH));
        scale = Math.max(0.3f, scale);
        int drawW = Math.round(boxW * scale);
        int drawH = Math.round(boxH * scale);
        int px = previewX + (previewW - drawW) / 2;
        int py = previewY + (previewH - drawH) / 2;
        g.pose().pushMatrix();
        try {
            g.pose().translate(px, py);
            g.pose().scale(scale, scale);
            CustomScoreboardFeature.drawBoard(g, font, 0, 0, lines, cfg, false);
        } catch (RuntimeException ignored) {
            // A broken preview frame must never take the editor down with it.
        } finally {
            g.pose().popMatrix();
        }
    }

    private void drawSectionPanels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<VisualRow> active = activeRows();
        List<VisualRow> disabled = disabledRows();
        if (dragging) {
            active.removeIf(v -> v.fullIndex() == draggingFullIndex);
            disabled.removeIf(v -> v.fullIndex() == draggingFullIndex);
        }

        g.text(this.font, "§aActive (" + active.size() + ") - top to bottom order", activePanelX, activePanelY - 11, 0xFFFFFFFF);
        drawList(g, active, activePanelX, activePanelY, activePanelW, activePanelH, activeScroll,
                dragging && hoverActive ? hoverSlot : -1, mouseX, mouseY);

        g.text(this.font, "§7Disabled (" + disabled.size() + ") - drag here to turn off", disabledPanelX, disabledPanelY - 11, 0xFFFFFFFF);
        drawList(g, disabled, disabledPanelX, disabledPanelY, disabledPanelW, disabledPanelH, disabledScroll,
                dragging && !hoverActive ? hoverSlot : -1, mouseX, mouseY);

        if (dragging) {
            int fx = hoverActive ? activePanelX : disabledPanelX;
            int fw = hoverActive ? activePanelW : disabledPanelW;
            int fy = (int) Math.round(floatY);
            int bg = hoverActive ? 0xCC2E7D32 : 0xCC5A1F1F;
            g.fill(fx, fy, fx + fw, fy + ROW_H, bg);
            g.outline(fx, fy, fw, ROW_H, ACCENT);
            g.text(this.font, draggingLabel, fx + 4, fy + 3, 0xFFFFFFFF, false);
        }
    }

    private void drawList(GuiGraphicsExtractor g, List<VisualRow> rows, int x, int y, int w, int h, int scrollPx,
                          int insertSlot, int mouseX, int mouseY) {
        g.fill(x, y, x + w, y + h, 0xFF080808);
        g.outline(x - 1, y - 1, w + 2, h + 2, BORDER);
        g.enableScissor(x, y, x + w, y + h);
        try {
            for (int i = 0; i < rows.size(); i++) {
                int rowY = y + i * ROW_H - scrollPx;
                if (rowY + ROW_H < y || rowY > y + h) {
                    continue;
                }
                VisualRow row = rows.get(i);
                boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= rowY && mouseY < rowY + ROW_H;
                if (hovered) {
                    g.fill(x, rowY, x + w, rowY + ROW_H, 0xFF262626);
                } else if (i % 2 == 0) {
                    g.fill(x, rowY, x + w, rowY + ROW_H, 0xFF121212);
                }
                String text = (i + 1) + ". " + row.label();
                int color = row.enabled() ? 0xFFFFFFFF : 0xFF808080;
                g.text(this.font, this.font.plainSubstrByWidth(text, w - 8), x + 4, rowY + 3, color, false);
            }
            if (insertSlot >= 0) {
                int lineY = y + insertSlot * ROW_H - scrollPx;
                g.fill(x, lineY - 1, x + w, lineY + 1, ACCENT);
            }
        } finally {
            g.disableScissor();
        }
        drawScrollbar(g, x, y, w, h, scrollPx, rows.size());
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int x, int y, int w, int h, int scrollPx, int count) {
        int max = clampScroll(Integer.MAX_VALUE / 2, count, h);
        if (max <= 0) {
            return;
        }
        int trackX = x + w - 3;
        g.fill(trackX, y, trackX + 3, y + h, 0xFF1A1A1A);
        int contentH = Math.max(1, count * ROW_H);
        int thumbH = Math.max(10, h * h / contentH);
        int thumbY = y + (h - thumbH) * scrollPx / max;
        g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, ACCENT);
    }

    // ---- widget helpers ----

    private AbstractWidget cycleBtn(Supplier<String> label, Runnable advance, int x, int y, int w) {
        return SettingsButtonWidget.builder(Component.literal(label.get()), btn -> {
                    advance.run();
                    cfg.save();
                    btn.setMessage(Component.literal(label.get()));
                }).bounds(x, y, w, 18).build();
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
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
