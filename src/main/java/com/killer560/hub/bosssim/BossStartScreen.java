package com.killer560.hub.bosssim;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The sim menu's "F7 Boss" entry: start the boss fight at any phase, straight into it (killer560, 2026-11-01: build all
 * of F7's phases, with a direct way in as well as the floor's portal). Master Mode adds P5.
 */
public final class BossStartScreen extends Screen {

    /** Saved so the choice survives a restart: {@code master} or {@code floor7}. */
    private static final java.nio.file.Path MODE_FILE = com.killer560.hub.util.ModPaths.config("killer560smod-sim-boss.txt");
    private static boolean master = true;

    private static void loadMode() {
        try {
            if (java.nio.file.Files.isRegularFile(MODE_FILE)) {
                master = !java.nio.file.Files.readString(MODE_FILE).trim().equalsIgnoreCase("floor7");
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // an unreadable file keeps Master Mode
        }
    }

    private static void saveMode() {
        try {
            java.nio.file.Files.createDirectories(MODE_FILE.getParent());
            java.nio.file.Files.writeString(MODE_FILE, master ? "master" : "floor7");
        } catch (java.io.IOException ignored) {
            // the choice still holds for this session
        }
    }

    private final Screen parent;
    private int panelX, panelY, panelW, panelH;

    public BossStartScreen(Screen parent) {
        super(Component.literal("F7 Boss"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        loadMode();
        panelW = Math.min(this.width - 16, 300);
        panelH = Math.min(this.height - 16, 250);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int bw = (panelW - 30) / 2;
        int x1 = panelX + 10;
        int x2 = x1 + bw + 10;
        int y = panelY + 34;
        Object[][] rows = {
                {"P1 Maxor", BossFight.Start.P1}, {"P2 Storm", BossFight.Start.P2},
                {"P3 Section 1", BossFight.Start.S1}, {"P3 Section 2", BossFight.Start.S2},
                {"P3 Section 3", BossFight.Start.S3}, {"P3 Section 4", BossFight.Start.S4},
                {"P3 Core", BossFight.Start.CORE}, {"P4 Necron", BossFight.Start.P4},
                {"P5 Dragons", BossFight.Start.P5}};
        for (int i = 0; i < rows.length; i++) {
            BossFight.Start start = (BossFight.Start) rows[i][1];
            int x = i % 2 == 0 ? x1 : x2;
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal((String) rows[i][0]), b -> {
                McCompat.setScreen(this.minecraft, null);
                BossSim.start(this.minecraft, start, master || start == BossFight.Start.P5);
            }).bounds(x, y, bw, 18).build());
            if (i % 2 == 1) {
                y += 22;
            }
        }
        y += 26;
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal(modeLabel()), (b, dir) -> {
            master = !master;
            saveMode();
            b.setMessage(Component.literal(modeLabel()));
        }).bounds(x1, y, bw, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(x2, panelY + panelH - 24, bw, 18).build());
    }

    private static String modeLabel() {
        return "Mode: " + (master ? "Master Mode" : "Floor 7");
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(g, this.width, this.height);
        ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(g, panelX, panelY, panelW);
        ScreenChrome.title(g, "F7 BOSS", panelX + 10, panelY + 11, false);
        String src = java.nio.file.Files.isRegularFile(BossArena.capturePath()) ? "Room: captured" : "Room: built-in";
        MenuStyle.text(g, src, panelX + panelW - 10 - this.font.width(src), panelY + 11, MenuStyle.DIM);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
