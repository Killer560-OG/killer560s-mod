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
    /** True while the next key press is being captured as the new pause key. */
    private boolean listeningForKey;

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
                }).bounds(panelX + 6, panelY + 34, (panelW - 12 - 8) / 3, 18).build());
        // Two rows, both sized from the panel rather than fixed pixels: the panel can be as narrow as 320 and a
        // hardcoded row overflowed it, putting a button off the edge at small GUI scales.
        var rcfg = RoomRecorderConfig.getInstance();
        int gap = 4;
        int third = (panelW - 12 - gap * 2) / 3;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Refresh"), btn -> {
            rows = RoomLibrary.incomplete();
            scroll = 0;
        }).bounds(panelX + 6 + third + gap, panelY + 34, third, 18).build());
        if (RoomRecorderFeature.isPaused()) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§aResume"), btn -> {
                RoomRecorderFeature.resume();
                rebuildWidgets();
            }).bounds(panelX + 6 + (third + gap) * 2, panelY + 34, third, 18).build());
        }
        int halfW = (panelW - 12 - gap) / 2;
        addRenderableWidget(SettingsButtonWidget.builder(
                Component.literal("Pause on 5-Puzzle Runs: " + (rcfg.isPauseOnFivePuzzles() ? "§aON" : "§cOFF")),
                btn -> {
                    rcfg.setPauseOnFivePuzzles(!rcfg.isPauseOnFivePuzzles());
                    rcfg.save();
                    rebuildWidgets();
                }).bounds(panelX + 6, panelY + 56, halfW, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(
                Component.literal(listeningForKey ? "Press any key..." : "Pause Key: " + keyName()), btn -> {
                    listeningForKey = true;
                    rebuildWidgets();
                }).bounds(panelX + 6 + halfW + gap, panelY + 56, halfW, 18).build());
    }

    /** Below BOTH button rows - the second row is at panelY+56 and is 18 tall. */
    private int listY() {
        return panelY + 80;
    }

    private int listH() {
        return panelH - 80 - 8;
    }

    private int maxScroll() {
        return Math.max(0, rows.size() * ROW_H - listH());
    }

    /** The readable name of the pause key, or "Not Set". */
    private static String keyName() {
        int code = RoomRecorderConfig.getInstance().getResumeKeyCode();
        if (code == com.killer560.hub.util.KeyUtil.NONE) {
            return "Not Set";
        }
        String glfw = org.lwjgl.glfw.GLFW.glfwGetKeyName(code, 0);
        return glfw == null ? ("key " + code) : glfw.toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent keyEvent) {
        if (listeningForKey) {
            listeningForKey = false;
            // Escape means "leave it alone", the same as every other keybind button in this mod.
            if (keyEvent.key() != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                RoomRecorderConfig cfg = RoomRecorderConfig.getInstance();
                cfg.setResumeKeyCode(keyEvent.key());
                cfg.save();
            }
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(keyEvent);
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
