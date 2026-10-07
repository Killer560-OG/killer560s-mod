package com.killer560.hub.scoreboard;

import com.killer560.hub.gui.DragListWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.scoreboard.ScoreboardListEditor.Section;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import com.killer560.hub.compat.McCompat;

/**
 * Full-screen editor for the Custom Scoreboard with a live preview - killer560: "Redesign the custom scoreboard so it
 * shows more of preview style that i drag and drop things where I want them."
 * <p>
 * Left column: a live preview of the board, built from {@link CustomScoreboardFeature#previewLines()} - real
 * sidebar/tab data when you're on Skyblock/p3sim, SkyHanni-style sample text otherwise - plus the settings that don't
 * make sense to drag: alignment, background, title, number format. Right column: Lines / Events / Stats, each the same
 * {@link ScoreboardListEditor} the settings tab uses (2026-10-07: Add dropdown, drag to reorder, trash can to remove -
 * SkyHanni's system, replacing this screen's old Active/Disabled panels and click-to-toggle).
 */
public class ScoreboardEditorScreen extends Screen {

    private static final int BORDER = 0xFF553311;
    private static final int MARGIN = 10;

    private final Screen parent;
    private CustomScoreboardConfig cfg;
    private static Section section = Section.LINES;

    private int previewX, previewY, previewW, previewH;

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
                () -> cfg.setTextAlignment(cfg.getTextAlignment().next()), () -> cfg.setTextAlignment(cfg.getTextAlignment().previous()), leftX, cy, leftW));
        cy += 22;
        addRenderableWidget(cycleBtn(() -> "Title Align: §6" + cfg.getTitleAlignment().label,
                () -> cfg.setTitleAlignment(cfg.getTitleAlignment().next()), () -> cfg.setTitleAlignment(cfg.getTitleAlignment().previous()), leftX, cy, leftW));
        cy += 22;
        addRenderableWidget(cycleBtn(() -> "Numbers: §6" + cfg.getNumberFormat().label,
                () -> cfg.setNumberFormat(cfg.getNumberFormat().next()), () -> cfg.setNumberFormat(cfg.getNumberFormat().previous()), leftX, cy, leftW));
        cy += 22;
        addRenderableWidget(cycleBtn(() -> "Number Style: " + cfg.getNumberDisplayFormat().label,
                () -> cfg.setNumberDisplayFormat(cfg.getNumberDisplayFormat().next()), () -> cfg.setNumberDisplayFormat(cfg.getNumberDisplayFormat().previous()), leftX, cy, leftW));
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
        int sx = rightX;
        for (Section s : Section.values()) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal(s == section ? "§6" + s.title : s.title),
                    btn -> {
                        section = s;
                        this.rebuildWidgets();
                    }).bounds(sx, 30, sectionBtnW, 20).build());
            sx += sectionBtnW + 8;
        }

        List<AbstractWidget> list = new ArrayList<>();
        int listTop = ScoreboardListEditor.buildControls(list, section, rightX, 56, rightW, this::rebuildWidgets);
        int rows = Math.max(3, (this.height - 34 - listTop - 4) / DragListWidget.ROW_H);
        ScoreboardListEditor.buildList(list, section, rightX, listTop, rightW, rows, this::rebuildWidgets);
        for (AbstractWidget w : list) {
            addRenderableWidget(w);
        }

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(this.width / 2 - 50, this.height - 26, 100, 20).build());
    }

    // ---- rendering ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xCC000000);
        g.text(this.font, "§eDrag a line to move it, the trash can takes it off, Add puts one back.",
                MARGIN, 10, 0xFFFFFFFF);
        boolean live = CustomScoreboardFeature.isActive();
        g.text(this.font, live ? "§7Preview: live data from your current board."
                : "§7Preview: sample data (not on Skyblock/p3sim right now).", MARGIN, 22, 0xFFFFFFFF);

        drawPreview(g);

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

    // ---- widget helpers ----

    private AbstractWidget cycleBtn(Supplier<String> label, Runnable advance, Runnable regress, int x, int y, int w) {
        return SettingsButtonWidget.builder(Component.literal(label.get()), btn -> {
                    advance.run();
                    cfg.save();
                    btn.setMessage(Component.literal(label.get()));
                }).secondaryPress(btn -> {
                    // killer560, 2026-09-27: "if i right click then it goes back one".
                    regress.run();
                    cfg.save();
                    btn.setMessage(Component.literal(label.get()));
                }).bounds(x, y, w, 18).build();
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
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
