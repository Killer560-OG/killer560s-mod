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

    /** Spawn Withers switch (default ON), saved beside the mode: {@code off} or absent. */
    private static final java.nio.file.Path WITHERS_FILE = com.killer560.hub.util.ModPaths.config("killer560smod-sim-boss-withers.txt");
    private static Boolean withers;

    public static boolean spawnWithers() {
        if (withers == null) {
            try {
                withers = !java.nio.file.Files.isRegularFile(WITHERS_FILE)
                        || !java.nio.file.Files.readString(WITHERS_FILE).trim().equalsIgnoreCase("off");
            } catch (java.io.IOException | RuntimeException e) {
                withers = true;
            }
        }
        return withers;
    }

    public static void setSpawnWithers(boolean on) {
        withers = on;
        try {
            java.nio.file.Files.createDirectories(WITHERS_FILE.getParent());
            java.nio.file.Files.writeString(WITHERS_FILE, on ? "on" : "off");
        } catch (java.io.IOException ignored) {
            // the choice still holds for this session
        }
    }

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

    private static final int SECTION_H = 40;
    private static final int SECTION_GAP = 10;

    private final Screen parent;
    private int panelX, panelY, panelW, panelH;
    /** Each section's box and title: {x, y, w} plus the title, drawn behind its buttons. */
    private final java.util.List<Object[]> sections = new java.util.ArrayList<>();
    private String roomSource = "Room: built-in";

    /** One phase's box: its title on top and its start buttons side by side under it. */
    private void section(String title, int x, int y, int w, Object[][] starts) {
        sections.add(new Object[]{x, y, w, title});
        int gap = 4;
        int n = starts.length;
        int bx = x + 5;
        int each = (w - 10 - gap * (n - 1)) / n;
        for (Object[] s : starts) {
            BossFight.Start start = (BossFight.Start) s[1];
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal((String) s[0]), b -> {
                McCompat.setScreen(this.minecraft, null);
                BossSim.start(this.minecraft, start, master || start == BossFight.Start.P5);
            }).bounds(bx, y + 17, each, 18).build());
            bx += each + gap;
        }
    }

    public BossStartScreen(Screen parent) {
        super(Component.literal("F7 Boss"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        roomSource = java.nio.file.Files.isRegularFile(BossArena.capturePath()) ? "Room: captured" : "Room: built-in";
        loadMode();
        panelW = Math.min(this.width - 16, 340);
        panelH = Math.min(this.height - 16, 250);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int bw = (panelW - 30) / 2;
        int x1 = panelX + 10;
        int x2 = x1 + bw + 10;
        int fullW = panelW - 20;
        // Five separate sections, one per phase (killer560, 2026-11-05: "have this split into 5 sections. each of
        // the sections more separated"): P1 | P2, then P3 across the panel, then P4 | P5.
        int y = panelY + 32;
        sections.clear();
        section("P1 Maxor", x1, y, bw, new Object[][]{{"Start", BossFight.Start.P1}});
        section("P2 Storm", x2, y, bw, new Object[][]{{"Start", BossFight.Start.P2}});
        y += SECTION_H + SECTION_GAP;
        section("P3 Goldor", x1, y, fullW, new Object[][]{{"Section 1", BossFight.Start.S1},
                {"Section 2", BossFight.Start.S2}, {"Section 3", BossFight.Start.S3},
                {"Section 4", BossFight.Start.S4}, {"Core", BossFight.Start.CORE}});
        y += SECTION_H + SECTION_GAP;
        section("P4 Necron", x1, y, bw, new Object[][]{{"Start", BossFight.Start.P4}});
        section("P5 Dragons", x2, y, bw, new Object[][]{{"Start", BossFight.Start.P5}});
        y += SECTION_H + SECTION_GAP + 2;
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal(modeLabel()), (b, dir) -> {
            master = !master;
            saveMode();
            b.setMessage(Component.literal(modeLabel()));
        }).bounds(x1, y, bw, 18).build());
        var withersBtn = addRenderableWidget(SettingsButtonWidget.builder(withersLabel(), btn -> {
            setSpawnWithers(!spawnWithers());
            btn.setMessage(withersLabel());
        }).bounds(x2, y, bw, 18).build());
        withersBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                com.killer560.hub.gui.SettingTooltips.describe("F7 Boss", "Spawn Withers"))));
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(x2, panelY + panelH - 24, bw, 18).build());
    }

    private static Component withersLabel() {
        return Component.literal("Spawn Withers: " + (spawnWithers() ? "§aON" : "§cOFF"));
    }

    /** A start's name as this screen shows it (the co-op screen reuses it). */
    public static String label(BossFight.Start s) {
        return switch (s) {
            case P1 -> "P1 Maxor";
            case P2 -> "P2 Storm";
            case S1 -> "P3 Section 1";
            case S2 -> "P3 Section 2";
            case S3 -> "P3 Section 3";
            case S4 -> "P3 Section 4";
            case CORE -> "P3 Core";
            case P4 -> "P4 Necron";
            case P5 -> "P5 Dragons";
        };
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
        String src = roomSource;
        MenuStyle.text(g, src, panelX + panelW - 10 - this.font.width(src), panelY + 11, MenuStyle.DIM);
        for (Object[] s : sections) {
            int x = (int) s[0], y = (int) s[1], w = (int) s[2];
            ScreenChrome.well(g, x, y, w, SECTION_H);
            MenuStyle.text(g, (String) s[3], x + 6, y + 5, MenuStyle.OR);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
