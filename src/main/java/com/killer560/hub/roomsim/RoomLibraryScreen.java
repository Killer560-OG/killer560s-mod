package com.killer560.hub.roomsim;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.profit.ProfitPanels;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * What the room recorder still needs: every room touched so far that is not fully captured, worst first.
 *
 * <p>killer560 asked for this directly - "a gui option to show which rooms it hasn't seen all of yet". It reads
 * completeness rather than a seen/not-seen flag because a room is only ever partly loaded on any one visit, so
 * the useful question is not which rooms have been entered but which are still missing pieces.
 *
 * <p>Deliberately lists only what has been touched. There is no canonical list of every F7 room anywhere in this
 * codebase, so a "rooms you have never seen" count would be invented, and an invented number on a progress
 * screen is worse than no number.
 */
public class RoomLibraryScreen extends Screen {

    private static final int ROW_H = 12;

    private final Screen parent;
    private List<String> rows = List.of();
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int scroll;

    public RoomLibraryScreen(Screen parent) {
        super(Component.literal("Room Library"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, Math.max(320, Math.min((int) (this.width * 0.7), 520)));
        panelH = Math.min(this.height - 20, Math.max(200, Math.min((int) (this.height * 0.8), 420)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        RoomLibrary.load();
        rows = RoomLibrary.incomplete();

        addRenderableWidget(SettingsButtonWidget.builder(
                Component.literal(RoomRecorderFeature.isRunning() ? "§cStop Recorder" : "§aStart Recorder"), btn -> {
                    if (RoomRecorderFeature.isRunning()) {
                        RoomRecorderFeature.stop("button");
                    } else {
                        RoomRecorderFeature.start();
                    }
                    rebuildWidgets();
                }).bounds(panelX + 6, panelY + 34, 110, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Refresh"), btn -> {
            rows = RoomLibrary.incomplete();
            scroll = 0;
        }).bounds(panelX + 120, panelY + 34, 70, 18).build());
    }

    private int listY() {
        return panelY + 58;
    }

    private int listH() {
        return panelH - 58 - 8;
    }

    private int maxScroll() {
        return Math.max(0, rows.size() * ROW_H - listH());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(scrollY * ROW_H * 2)));
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xCC000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, ProfitPanels.PANEL_BG);
        g.outline(panelX, panelY, panelW, panelH, ProfitPanels.BORDER);
        g.fill(panelX, panelY, panelX + panelW, panelY + 30, 0xFF000000);
        g.fill(panelX, panelY + 29, panelX + panelW, panelY + 30, ProfitPanels.ACCENT);
        g.text(this.font, "ROOM LIBRARY", panelX + 10, panelY + 11, ProfitPanels.ACCENT, false);
        String summary = String.format(Locale.US, "%d complete of %d seen",
                RoomLibrary.completeCount(), RoomLibrary.roomCount());
        g.text(this.font, summary, panelX + panelW - 10 - this.font.width(summary), panelY + 11,
                ProfitPanels.DIM, false);

        int ly = listY();
        int lh = listH();
        g.fill(panelX + 6, ly, panelX + panelW - 6, ly + lh, ProfitPanels.INNER_BG);
        g.outline(panelX + 5, ly - 1, panelW - 10, lh + 2, ProfitPanels.BORDER);
        g.enableScissor(panelX + 6, ly, panelX + panelW - 6, ly + lh);
        try {
            if (rows.isEmpty()) {
                String none = RoomLibrary.roomCount() == 0
                        ? "Nothing captured yet - start the recorder in the Map Logger instance"
                        : "Every room seen so far is complete";
                g.text(this.font, none, panelX + 12, ly + 8, ProfitPanels.DIM, false);
            }
            for (int i = Math.max(0, scroll / ROW_H); i < rows.size(); i++) {
                int rowY = ly + i * ROW_H - scroll + 2;
                if (rowY > ly + lh) {
                    break;
                }
                g.text(this.font, this.font.plainSubstrByWidth(rows.get(i), panelW - 24),
                        panelX + 12, rowY, ProfitPanels.TEXT, false);
            }
        } finally {
            g.disableScissor();
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
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
